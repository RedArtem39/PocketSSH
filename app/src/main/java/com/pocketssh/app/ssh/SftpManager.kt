package com.pocketssh.app.ssh

import android.content.Context
import com.pocketssh.app.data.ServerProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.SFTPClient
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet

data class RemoteEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
)

sealed interface SftpState {
    data object Disconnected : SftpState
    data object Connecting : SftpState
    data class Ready(val path: String, val entries: List<RemoteEntry>) : SftpState
    data class Failed(val message: String) : SftpState
}

class SftpManager(
    private val context: Context,
    private val connection: SshConnection,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<SftpState>(SftpState.Disconnected)
    val state: StateFlow<SftpState> = _state.asStateFlow()

    val hostKeyRequest: StateFlow<HostKeyRequest?> = connection.hostKeyRequest
    fun answerHostKey(accepted: Boolean, remember: Boolean) = connection.answerHostKey(accepted, remember)

    @Volatile private var ssh: SSHClient? = null
    @Volatile private var sftp: SFTPClient? = null
    @Volatile private var currentPath: String = "/"

    fun open(profile: ServerProfile) {
        closeQuietly()
        _state.value = SftpState.Connecting
        scope.launch {
            try {
                val client = connection.connectAndAuth(context, profile)
                ssh = client
                val sftpClient = client.newSFTPClient()
                sftp = sftpClient
                val home = runCatching { sftpClient.canonicalize(".") }.getOrDefault("/")
                listDirectory(home)
            } catch (error: Exception) {
                if (_state.value != SftpState.Disconnected) {
                    val message = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
                    _state.value = SftpState.Failed(message)
                }
                closeQuietly()
            }
        }
    }

    fun navigate(path: String) {
        scope.launch { listDirectory(path) }
    }

    fun up() {
        val parent = currentPath.trimEnd('/').substringBeforeLast('/', "").ifBlank { "/" }
        navigate(parent)
    }

    fun refresh() {
        navigate(currentPath)
    }

    private fun listDirectory(path: String) {
        val client = sftp ?: return
        try {
            val entries = client.ls(path)
                .filterNot { it.name == "." || it.name == ".." }
                .map { info ->
                    RemoteEntry(
                        name = info.name,
                        path = info.path,
                        isDirectory = info.isDirectory,
                        size = info.attributes.size,
                    )
                }
                .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            currentPath = path
            _state.value = SftpState.Ready(path, entries)
        } catch (error: Exception) {
            val message = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
            _state.value = SftpState.Failed(message)
        }
    }

    private fun childPath(name: String) = if (currentPath.endsWith("/")) "$currentPath$name" else "$currentPath/$name"

    fun upload(name: String, input: InputStream, onDone: (Result<Unit>) -> Unit) {
        val client = sftp
        val target = childPath(name)
        scope.launch {
            val result = runCatching {
                requireNotNull(client) { "Not connected" }
                client.open(target, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)).use { remote ->
                    remote.RemoteFileOutputStream().use { out -> input.use { it.copyTo(out) } }
                    Unit
                }
            }
            withContext(Dispatchers.Main) { onDone(result) }
            if (result.isSuccess) refresh()
        }
    }

    fun download(entry: RemoteEntry, output: OutputStream, onDone: (Result<Unit>) -> Unit) {
        val client = sftp
        scope.launch {
            val result = runCatching {
                requireNotNull(client) { "Not connected" }
                client.open(entry.path, EnumSet.of(OpenMode.READ)).use { remote ->
                    remote.RemoteFileInputStream().use { input -> output.use { input.copyTo(it) } }
                    Unit
                }
            }
            withContext(Dispatchers.Main) { onDone(result) }
        }
    }

    fun delete(entry: RemoteEntry, onDone: (Result<Unit>) -> Unit) {
        val client = sftp
        scope.launch {
            val result = runCatching {
                requireNotNull(client) { "Not connected" }
                if (entry.isDirectory) client.rmdir(entry.path) else client.rm(entry.path)
            }
            withContext(Dispatchers.Main) { onDone(result) }
            if (result.isSuccess) refresh()
        }
    }

    fun mkdir(name: String, onDone: (Result<Unit>) -> Unit) {
        val client = sftp
        val target = childPath(name)
        scope.launch {
            val result = runCatching {
                requireNotNull(client) { "Not connected" }
                client.mkdir(target)
            }
            withContext(Dispatchers.Main) { onDone(result) }
            if (result.isSuccess) refresh()
        }
    }

    private fun closeQuietly() {
        runCatching { sftp?.close() }
        runCatching { ssh?.disconnect() }
        runCatching { ssh?.close() }
        sftp = null
        ssh = null
    }

    fun close() {
        connection.answerHostKey(false, false)
        closeQuietly()
        currentPath = "/"
        _state.value = SftpState.Disconnected
    }

    fun destroy() {
        close()
        scope.cancel()
    }
}
