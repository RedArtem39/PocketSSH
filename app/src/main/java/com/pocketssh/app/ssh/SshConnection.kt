package com.pocketssh.app.ssh

import android.content.Context
import com.pocketssh.app.data.AuthType
import com.pocketssh.app.data.SecureProfileStore
import com.pocketssh.app.data.ServerProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.io.File
import java.security.MessageDigest
import java.security.PublicKey
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class HostKeyRequest(
    val host: String,
    val port: Int,
    val algorithm: String,
    val fingerprint: String,
)

private class HostKeyGate(val request: HostKeyRequest, val latch: CountDownLatch = CountDownLatch(1)) {
    @Volatile var accepted = false
    @Volatile var remember = false
}

/**
 * Shared connect + authenticate + TOFU host-key verification, used by both the interactive
 * shell session and the SFTP browser so the auth/host-key logic only lives in one place.
 */
class SshConnection(private val secureStore: SecureProfileStore) {
    private val _hostKeyRequest = MutableStateFlow<HostKeyRequest?>(null)
    val hostKeyRequest: StateFlow<HostKeyRequest?> = _hostKeyRequest.asStateFlow()

    @Volatile private var gate: HostKeyGate? = null

    fun answerHostKey(accepted: Boolean, remember: Boolean) {
        gate?.let {
            it.accepted = accepted
            it.remember = remember
            it.latch.countDown()
        }
    }

    /** Blocking: connects, verifies the host key (TOFU), and authenticates. Call from an IO thread. */
    fun connectAndAuth(context: Context, profile: ServerProfile): SSHClient {
        val client = SSHClient()
        try {
            client.connectTimeout = 15_000
            client.timeout = 30_000
            client.addHostKeyVerifier(object : HostKeyVerifier {
                override fun verify(hostname: String, port: Int, key: PublicKey): Boolean =
                    verifyHostKey(hostname, port, key)

                override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
            })
            client.connect(profile.host, profile.port)

            var temporaryKey: File? = null
            try {
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
            } finally {
                temporaryKey?.delete()
            }
            return client
        } catch (error: Exception) {
            runCatching { client.disconnect() }
            runCatching { client.close() }
            throw error
        }
    }

    private fun verifyHostKey(host: String, port: Int, key: PublicKey): Boolean {
        val fingerprint = "SHA256:" + android.util.Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(key.encoded),
            android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
        )
        secureStore.knownFingerprint(host, port)?.let { return it == fingerprint }

        val newGate = HostKeyGate(HostKeyRequest(host, port, key.algorithm, fingerprint))
        gate = newGate
        _hostKeyRequest.value = newGate.request
        val answered = newGate.latch.await(2, TimeUnit.MINUTES)
        _hostKeyRequest.value = null
        gate = null
        if (answered && newGate.accepted && newGate.remember) {
            secureStore.rememberFingerprint(host, port, fingerprint)
        }
        return answered && newGate.accepted
    }
}
