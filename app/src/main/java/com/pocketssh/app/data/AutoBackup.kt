package com.pocketssh.app.data

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Backups that restore themselves, written before every update and after every change.
 *
 * The problem this solves: profiles are sealed with an AndroidKeystore key, and uninstalling the
 * app destroys that key, so a downgrade or a reinstall would otherwise lose everything. A manual
 * backup already covers that, but only if you remember the passphrase you chose — and the whole
 * point here is not having to.
 *
 * So the passphrase is derived from ANDROID_ID instead of being chosen. On Android 8.0 and up
 * that value is scoped to the app signing key, the user and the device, and — the part this
 * relies on — it "does not change on package uninstall or reinstall, as long as the signing key
 * is the same". minSdk is 26, so that holds everywhere this runs.
 *
 * What that buys: a reinstalled PocketSSH can open its own backup with no input at all. What it
 * costs, stated plainly because it is not obvious:
 *
 *  - The file is readable only on this device, by a build signed with this key. Another device,
 *    another app, or a rebuild under a different keystore cannot open it.
 *  - A factory reset changes ANDROID_ID, so older auto backups become unreadable.
 *  - Moving to a new phone needs the manual passphrase backup in Settings. This is not that.
 */
class AutoBackup(private val context: Context, private val folder: StorageFolder) {

    fun isAvailable(): Boolean = folder.isConfigured

    /**
     * Writes a backup of [profiles] and prunes old ones. Returns the file name, or null when
     * there is no folder to write to or the write failed.
     */
    fun write(profiles: List<ServerProfile>, versionName: String): String? {
        if (profiles.isEmpty()) return null
        val payload = BackupCodec.export(profiles, passphrase())
        val name = "$PREFIX$versionName-${stamp()}$SUFFIX"
        if (!folder.write(name, "application/octet-stream", payload.toByteArray(Charsets.UTF_8))) return null
        folder.prune(PREFIX, KEEP)
        return name
    }

    fun list(): List<StoredFile> = folder.list(PREFIX)

    fun latest(): StoredFile? = list().firstOrNull()

    /**
     * Reads back an auto backup. Returns null when the file is missing, is not one of ours, or
     * was written by a different device or signing key — all of which surface as a decrypt
     * failure and none of which are worth distinguishing to the caller.
     */
    fun restore(file: StoredFile): List<ServerProfile>? {
        val bytes = folder.readUri(file.uri) ?: return null
        return runCatching { BackupCodec.import(bytes.toString(Charsets.UTF_8), passphrase()) }.getOrNull()
    }

    fun restoreLatest(): List<ServerProfile>? = latest()?.let(::restore)

    // Not a device fingerprint for tracking: it never leaves the device and is only used as key
    // material. The suffix keeps it from colliding with any other use of the same value.
    @SuppressLint("HardwareIds")
    private fun passphrase(): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        return "pocketssh.auto.v1:$androidId"
    }

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    private companion object {
        const val PREFIX = "pocketssh-auto-"
        const val SUFFIX = ".psb"

        /** Enough history to step back past a bad update without filling the folder. */
        const val KEEP = 8
    }
}
