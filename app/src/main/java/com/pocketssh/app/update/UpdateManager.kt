package com.pocketssh.app.update

import android.content.Context
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import com.pocketssh.app.BuildConfig
import com.pocketssh.app.data.StorageFolder
import com.pocketssh.app.data.StoredFile
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

sealed interface InstallResult {
    data object Started : InstallResult
    data object NeedsPermission : InstallResult
    data class Failed(val message: String) : InstallResult
}

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState

    /** Checked successfully and this build is the newest published one. */
    data class UpToDate(val checkedAt: Long) : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState
    data class Downloading(val release: ReleaseInfo, val downloaded: Long, val total: Long) : UpdateState
    data class ReadyToInstall(val release: ReleaseInfo, val file: File) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Checks GitHub for a newer release, downloads its APK and hands it to the system installer.
 *
 * Installing is as far as an ordinary app can go. The system shows its own confirmation and the
 * user has to allow installs from this source once; silent installation needs privileges only a
 * system installer has.
 */
class UpdateManager(
    private val context: Context,
    private val folder: StorageFolder,
    private val repo: String = BuildConfig.UPDATE_REPO,
    private val currentVersion: Version = Version.parse(BuildConfig.VERSION_NAME),
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state = _state.asStateFlow()

    private val _releases = MutableStateFlow<List<ReleaseInfo>>(emptyList())
    val releases = _releases.asStateFlow()

    fun currentVersionName(): String = currentVersion.raw

    suspend fun check(includePrereleases: Boolean = false) {
        _state.value = UpdateState.Checking
        val result = withContext(Dispatchers.IO) { runCatching { GitHubReleases.list(repo) } }
        result.onSuccess { all ->
            _releases.value = all
            val newest = all.filter { includePrereleases || !it.prerelease }
                .maxByOrNull { it.version }
            _state.value = when {
                newest != null && newest.version > currentVersion && newest.apkUrl != null ->
                    UpdateState.Available(newest)
                else -> UpdateState.UpToDate(System.currentTimeMillis())
            }
        }.onFailure {
            _state.value = UpdateState.Failed(it.message ?: "Could not reach GitHub")
        }
    }

    /**
     * Downloads [release] into the cache, then copies it to the shared folder when one is set up.
     * The cache copy is what gets installed; the shared copy is what a later downgrade needs,
     * since the cache goes away with the app.
     */
    suspend fun download(release: ReleaseInfo) {
        val url = release.apkUrl ?: run {
            _state.value = UpdateState.Failed("This release has no APK attached")
            return
        }
        _state.value = UpdateState.Downloading(release, 0, release.apkSize)
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val target = File(cacheDir(), release.apkName ?: "update-${release.version}.apk")
                fetch(url, target) { read, total ->
                    _state.value = UpdateState.Downloading(release, read, total)
                }
                target
            }
        }
        result.onSuccess { file ->
            archive(file)
            _state.value = UpdateState.ReadyToInstall(release, file)
        }.onFailure {
            _state.value = UpdateState.Failed(it.message ?: "Download failed")
        }
    }

    /**
     * Streams the APK into a [PackageInstaller] session and commits it, which lands the user in
     * Android's own "update this app?" dialog by way of [InstallStatusReceiver].
     *
     * An ACTION_VIEW intent on the APK would be shorter, but it is a content type like any other
     * and every app that claims it joins the chooser — on the device this was tested against
     * that meant picking the package installer out of a list that included a terminal emulator
     * and a chat app. A session goes straight to the real installer.
     *
     * The distinction between "permission not granted" and "it genuinely failed" is kept because
     * the first is something the user can act on and the second is not.
     */
    fun install(file: File): InstallResult {
        if (!canRequestInstalls()) return InstallResult.NeedsPermission
        if (!file.isFile || file.length() == 0L) return InstallResult.Failed("The downloaded file is missing")
        return commitSession(file.name) { session ->
            file.inputStream().use { input ->
                session.openWrite(SESSION_ENTRY, 0, file.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
        }
    }

    /**
     * Same, for an APK kept in the shared folder. Note this can only succeed while PocketSSH is
     * still installed, and Android rejects a lower version code over a higher one — which is why
     * the rollback flow asks the user to uninstall first and open the file themselves.
     */
    fun installFromFolder(stored: StoredFile): InstallResult {
        if (!canRequestInstalls()) return InstallResult.NeedsPermission
        val bytes = folder.readUri(stored.uri) ?: return InstallResult.Failed("Could not read the saved APK")
        return commitSession(stored.name) { session ->
            session.openWrite(SESSION_ENTRY, 0, bytes.size.toLong()).use { output ->
                output.write(bytes)
                session.fsync(output)
            }
        }
    }

    private fun commitSession(label: String, write: (PackageInstaller.Session) -> Unit): InstallResult {
        InstallStatusReceiver.lastFailure.value = null
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        return runCatching {
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                write(session)
                val intent = Intent(context, InstallStatusReceiver::class.java)
                    .setAction(ACTION_INSTALL_STATUS)
                    .putExtra("label", label)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
                session.commit(pending.intentSender)
            }
            InstallResult.Started
        }.getOrElse { InstallResult.Failed(it.message ?: "Could not start the install") }
    }

    /** APKs kept in the shared folder, newest first. */
    fun archivedApks(): List<StoredFile> = folder.list(APK_PREFIX)

    fun canRequestInstalls(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun isRecoveryInstalled(): Boolean =
        runCatching { context.packageManager.getPackageInfo(RECOVERY_PACKAGE, 0) }.isSuccess

    /**
     * Hands a saved APK to the recovery app, which survives PocketSSH being uninstalled and can
     * therefore install the older build afterwards — the step this app cannot perform for itself.
     * The read grant travels with the intent; recovery copies the file out immediately, because
     * the permission belongs to a package that is about to disappear.
     */
    fun launchRecovery(stored: StoredFile): InstallResult {
        val intent = Intent()
            .setClassName(RECOVERY_PACKAGE, "$RECOVERY_PACKAGE.RecoveryService")
            .putExtra("apk_uri", stored.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return runCatching {
            // A plain service, not an activity: the helper has no interface. Started while this
            // app is in the foreground, which is what makes a background service start legal.
            context.startService(intent)
            InstallResult.Started
        }.getOrElse { InstallResult.Failed(it.message ?: "Recovery helper would not start") }
    }

    /** Downloads the recovery APK attached to [release] and offers it for install. */
    suspend fun installRecovery(release: ReleaseInfo): InstallResult {
        val url = release.recoveryUrl ?: return InstallResult.Failed("This release has no recovery APK")
        if (!canRequestInstalls()) return InstallResult.NeedsPermission
        val file = withContext(Dispatchers.IO) {
            runCatching {
                File(cacheDir(), "PocketSSH-Recovery.apk").also { fetch(url, it) { _, _ -> } }
            }
        }.getOrElse { return InstallResult.Failed(it.message ?: "Download failed") }
        archive(file)
        return install(file)
    }

    /** Opens the system page where installs from this app are allowed. */
    fun installPermissionIntent(): Intent =
        Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${context.packageName}"))

    fun uninstallIntent(): Intent =
        Intent(Intent.ACTION_DELETE, Uri.parse("package:${context.packageName}"))

    fun reset() {
        _state.value = UpdateState.Idle
    }

    private fun archive(file: File) {
        if (!folder.isConfigured) return
        runCatching {
            folder.write("$APK_PREFIX${file.name}", "application/vnd.android.package-archive", file.readBytes())
            folder.prune(APK_PREFIX, KEEP_APKS)
        }
    }

    private fun cacheDir(): File = File(context.cacheDir, "updates").apply { mkdirs() }

    private fun fetch(url: String, target: File, onProgress: (Long, Long) -> Unit) {
        var current = URL(url)
        var connection = open(current)
        var redirects = 0
        // GitHub serves release assets from a redirect to object storage, and
        // HttpURLConnection will not follow one that crosses protocols.
        while (connection.responseCode in 300..399 && redirects < MAX_REDIRECTS) {
            val location = connection.getHeaderField("Location") ?: break
            connection.disconnect()
            current = URL(current, location)
            connection = open(current)
            redirects++
        }
        try {
            if (connection.responseCode !in 200..299) throw IllegalStateException("HTTP ${connection.responseCode}")
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: 0L
            var read = 0L
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        read += count
                        onProgress(read, total)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: URL) = (url.openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 60_000
        instanceFollowRedirects = false
        setRequestProperty("User-Agent", "PocketSSH")
    }

    private companion object {
        const val APK_PREFIX = "pocketssh-apk-"
        const val RECOVERY_PACKAGE = "com.pocketssh.recovery"
        const val SESSION_ENTRY = "package"
        const val ACTION_INSTALL_STATUS = "com.pocketssh.app.INSTALL_STATUS"
        const val KEEP_APKS = 3
        const val MAX_REDIRECTS = 5
    }
}
