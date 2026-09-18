package com.pocketssh.app.ssh

import android.content.Context
import com.pocketssh.app.data.ServerProfile
import com.pocketssh.app.terminal.TerminalEmulator
import com.pocketssh.app.terminal.TerminalSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val title: String) : ConnectionState
    data class Failed(val message: String) : ConnectionState
}

class SshSessionManager(
    private val context: Context,
    private val connection: SshConnection,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    @Volatile private var termCols = 120
    @Volatile private var termRows = 40
    private val emulator = TerminalEmulator(termCols, termRows, onResponse = { text -> writeRaw(text) })
    private val _screen = MutableStateFlow(emulator.snapshot())
    val screen: StateFlow<TerminalSnapshot> = _screen.asStateFlow()

    val hostKeyRequest: StateFlow<HostKeyRequest?> = connection.hostKeyRequest
    fun answerHostKey(accepted: Boolean, remember: Boolean) = connection.answerHostKey(accepted, remember)

    @Volatile private var ssh: SSHClient? = null
    @Volatile private var session: Session? = null
    @Volatile private var shell: Session.Shell? = null
    private var connectionJob: Job? = null

    @Volatile private var screenDirty = false
    private var publishJob: Job? = null

    fun connect(profile: ServerProfile) {
        disconnect(false)
        emulator.reset()
        feed("Connecting to ${profile.username}@${profile.host}:${profile.port}…\r\n")
        _state.value = ConnectionState.Connecting
        connectionJob = scope.launch {
            try {
                val client = connection.connectAndAuth(context, profile)
                ssh = client
                val activeSession = client.startSession()
                session = activeSession
                activeSession.allocatePTY("xterm-256color", termCols, termRows, 0, 0, emptyMap())
                val activeShell = activeSession.startShell()
                shell = activeShell
                _state.value = ConnectionState.Connected(profile.name)
                feed("\u001B[32mConnected.\u001B[0m\r\n")

                val buffer = ByteArray(8192)
                while (client.isConnected) {
                    val count = activeShell.inputStream.read(buffer)
                    if (count < 0) break
                    if (count > 0) feed(buffer.decodeToString(0, count))
                }
                if (_state.value is ConnectionState.Connected) {
                    _state.value = ConnectionState.Disconnected
                    feed("\r\n\u001B[33mConnection closed.\u001B[0m\r\n")
                }
            } catch (error: Exception) {
                if (_state.value !is ConnectionState.Disconnected) {
                    val message = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
                    _state.value = ConnectionState.Failed(message)
                    feed("\r\n\u001B[31mError: $message\u001B[0m\r\n")
                }
                closeResources()
            }
        }
    }

    fun resize(cols: Int, rows: Int) {
        if (cols <= 0 || rows <= 0 || (cols == termCols && rows == termRows)) return
        termCols = cols
        termRows = rows
        emulator.resize(cols, rows)
        publishScreen()
        val activeShell = shell ?: return
        scope.launch { runCatching { activeShell.changeWindowDimensions(cols, rows, 0, 0) } }
    }

    fun setCellPixelSize(width: Float, height: Float) {
        emulator.setCellPixelSize(width, height)
    }

    fun send(text: String) {
        scope.launch {
            if (!writeRaw(text)) feed("\r\n\u001B[31mWrite failed\u001B[0m\r\n")
        }
    }

    private fun writeRaw(text: String): Boolean = runCatching {
        shell?.outputStream?.apply {
            write(text.toByteArray(Charsets.UTF_8))
            flush()
        }
    }.isSuccess

    fun disconnect(showMessage: Boolean = true) {
        connection.answerHostKey(false, false)
        connectionJob?.cancel()
        closeResources()
        _state.value = ConnectionState.Disconnected
        if (showMessage) feed("\r\n\u001B[33mDisconnected.\u001B[0m\r\n")
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

    // Feeding the emulator is cheap; snapshotting the whole grid and pushing it to Compose is not.
    // Chatty output (find /, yes, a busy log) can call this dozens of times per socket read burst,
    // so publishes are coalesced to roughly one per frame instead of one per read().
    private fun feed(text: String) {
        emulator.feed(text)
        screenDirty = true
        if (publishJob?.isActive != true) {
            publishJob = scope.launch {
                while (screenDirty) {
                    screenDirty = false
                    publishScreen()
                    delay(16)
                }
            }
        }
    }

    private fun publishScreen() {
        _screen.value = emulator.snapshot()
    }

    fun close() {
        disconnect(false)
        scope.cancel()
    }
}
