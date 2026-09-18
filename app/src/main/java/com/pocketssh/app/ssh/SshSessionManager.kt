package com.pocketssh.app.ssh

import android.content.Context
import com.pocketssh.app.data.AuthType
import com.pocketssh.app.data.SecureProfileStore
import com.pocketssh.app.data.ServerProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.io.File
import java.security.MessageDigest
import java.security.PublicKey
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val title: String) : ConnectionState
    data class Failed(val message: String) : ConnectionState
}

data class HostKeyRequest(
    val host: String,
    val port: Int,
    val algorithm: String,
    val fingerprint: String,
)

private data class HostKeyGate(val request: HostKeyRequest, val latch: CountDownLatch = CountDownLatch(1)) {
    @Volatile var accepted = false
    @Volatile var remember = false
}

class SshSessionManager(
    private val context: Context,
    private val secureStore: SecureProfileStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()
    private val _output = MutableStateFlow("")
    val output: StateFlow<String> = _output.asStateFlow()
    private val _hostKeyRequest = MutableStateFlow<HostKeyRequest?>(null)
    val hostKeyRequest: StateFlow<HostKeyRequest?> = _hostKeyRequest.asStateFlow()

    @Volatile private var hostKeyGate: HostKeyGate? = null
    @Volatile private var ssh: SSHClient? = null
    @Volatile private var session: Session? = null
    @Volatile private var shell: Session.Shell? = null
    private var connectionJob: Job? = null

    fun connect(profile: ServerProfile) {
        disconnect(false)
        _output.value = "Connecting to ${profile.username}@${profile.host}:${profile.port}…\r\n"
        _state.value = ConnectionState.Connecting
        connectionJob = scope.launch {
            var temporaryKey: File? = null
            try {
                val client = SSHClient()
                ssh = client
                client.connectTimeout = 15_000
                client.timeout = 30_000
                client.addHostKeyVerifier(object : HostKeyVerifier {
                    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean =
                        verifyHostKey(hostname, port, key)

                    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> =
                        emptyList()
                })
                client.connect(profile.host, profile.port)
                when (profile.authType) {
                    AuthType.PASSWORD -> client.authPassword(profile.username, profile.password)
                    AuthType.PRIVATE_KEY -> {
                        temporaryKey = File.createTempFile("pocketssh-key-", ".pem", context.cacheDir).apply {
                            writeText(profile.privateKey)
                            setReadable(false, false)
                            setWritable(false, false)
                            setReadable(true, true)
                            setWritable(true, true)
                        }
                        val provider = if (profile.keyPassphrase.isBlank()) {
                            client.loadKeys(temporaryKey.absolutePath)
                        } else {
                            client.loadKeys(temporaryKey.absolutePath, profile.keyPassphrase)
                        }
                        client.authPublickey(profile.username, provider)
                    }
                }
                temporaryKey?.delete()
                temporaryKey = null

                val activeSession = client.startSession()
                session = activeSession
                activeSession.allocatePTY("xterm-256color", 120, 40, 0, 0, emptyMap())
                val activeShell = activeSession.startShell()
                shell = activeShell
                _state.value = ConnectionState.Connected(profile.name)
                appendOutput("Connected.\r\n")

                val buffer = ByteArray(8192)
                while (client.isConnected) {
                    val count = activeShell.inputStream.read(buffer)
                    if (count < 0) break
                    if (count > 0) appendOutput(buffer.decodeToString(0, count))
                }
                if (_state.value is ConnectionState.Connected) {
                    _state.value = ConnectionState.Disconnected
                    appendOutput("\r\nConnection closed.\r\n")
                }
            } catch (error: Exception) {
                temporaryKey?.delete()
                if (_state.value !is ConnectionState.Disconnected) {
                    val message = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
                    _state.value = ConnectionState.Failed(message)
                    appendOutput("\r\nError: $message\r\n")
                }
                closeResources()
            }
        }
    }

    private fun verifyHostKey(host: String, port: Int, key: PublicKey): Boolean {
        val fingerprint = "SHA256:" + android.util.Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(key.encoded),
            android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
        )
        secureStore.knownFingerprint(host, port)?.let { return it == fingerprint }

        val gate = HostKeyGate(HostKeyRequest(host, port, key.algorithm, fingerprint))
        hostKeyGate = gate
        _hostKeyRequest.value = gate.request
        val answered = gate.latch.await(2, TimeUnit.MINUTES)
        _hostKeyRequest.value = null
        hostKeyGate = null
        if (answered && gate.accepted && gate.remember) {
            secureStore.rememberFingerprint(host, port, fingerprint)
        }
        return answered && gate.accepted
    }

    fun answerHostKey(accepted: Boolean, remember: Boolean) {
        hostKeyGate?.let {
            it.accepted = accepted
            it.remember = remember
            it.latch.countDown()
        }
    }

    fun send(text: String) {
        scope.launch {
            runCatching {
                shell?.outputStream?.apply {
                    write(text.toByteArray(Charsets.UTF_8))
                    flush()
                }
            }.onFailure { appendOutput("\r\nWrite failed: ${it.message}\r\n") }
        }
    }

    fun disconnect(showMessage: Boolean = true) {
        hostKeyGate?.latch?.countDown()
        connectionJob?.cancel()
        closeResources()
        _hostKeyRequest.value = null
        _state.value = ConnectionState.Disconnected
        if (showMessage) appendOutput("\r\nDisconnected.\r\n")
    }

    private fun closeResources() {
        runCatching { shell?.close() }
        runCatching { session?.close() }
        runCatching { ssh?.disconnect() }
        runCatching { ssh?.close() }
        shell = null
        session = null
        ssh = null
    }

    private fun appendOutput(text: String) {
        val combined = _output.value + text
        _output.value = if (combined.length > 200_000) combined.takeLast(160_000) else combined
    }

    fun close() {
        disconnect(false)
        scope.cancel()
    }
}
