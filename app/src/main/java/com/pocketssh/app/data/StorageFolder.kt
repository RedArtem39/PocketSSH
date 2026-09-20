package com.pocketssh.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/**
 * A user-chosen folder outside the app's own storage, used for automatic backups and for keeping
 * installed APKs around so a downgrade is possible.
 *
 * It has to be outside app storage because both of those exist precisely for the case where the
 * app is uninstalled — anything under filesDir or cacheDir goes with it. The folder is picked
 * once through the storage access framework and the grant is persisted, so later writes need no
 * further interaction.
 *
 * The grant itself does not survive an uninstall: Android drops persisted URI permissions along
 * with the package. After a reinstall the user has to point at the folder once more, which is
 * one tap, and the contents are still there.
 */
class StorageFolder(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val uri: Uri? get() = prefs.getString(KEY_TREE, null)?.let(Uri::parse)

    val isConfigured: Boolean get() = root() != null

    /** Human-readable name for the settings screen; null when nothing is chosen or reachable. */
    fun displayName(): String? = root()?.name

    /**
     * Records the folder and takes a persistable grant on it. Returns false if the grant could
     * not be taken, in which case nothing is stored — a remembered folder we cannot write to
     * would fail silently at the worst possible moment.
     */
    fun remember(uri: Uri): Boolean {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
            .onFailure { return false }
        prefs.edit().putString(KEY_TREE, uri.toString()).apply()
        return root() != null
    }

    fun forget() {
        uri?.let { current ->
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.releasePersistableUriPermission(current, flags) }
        }
        prefs.edit().remove(KEY_TREE).apply()
    }

    fun write(name: String, mimeType: String, bytes: ByteArray): Boolean {
        val root = root() ?: return false
        return runCatching {
            // Replace rather than let the provider suffix the name with "(1)" — callers rely on
            // being able to find a file back by the exact name they wrote.
            root.findFile(name)?.delete()
            val file = root.createFile(mimeType, name) ?: return false
            context.contentResolver.openOutputStream(file.uri)?.use { it.write(bytes) } ?: return false
            true
        }.getOrDefault(false)
    }

    fun read(name: String): ByteArray? {
        val file = root()?.findFile(name)?.takeIf { it.isFile } ?: return null
        return runCatching {
            context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
        }.getOrNull()
    }

    fun readUri(uri: Uri): ByteArray? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }.getOrNull()

    /** Files whose name starts with [prefix], newest first. */
    fun list(prefix: String): List<StoredFile> {
        val root = root() ?: return emptyList()
        return runCatching {
            root.listFiles()
                .filter { it.isFile && it.name?.startsWith(prefix) == true }
                .map { StoredFile(it.name.orEmpty(), it.uri, it.length(), it.lastModified()) }
                .sortedByDescending { it.modifiedAt }
        }.getOrDefault(emptyList())
    }

    fun delete(name: String): Boolean = root()?.findFile(name)?.delete() ?: false

    /** Keeps the [keep] newest files matching [prefix] and deletes the rest. */
    fun prune(prefix: String, keep: Int) {
        list(prefix).drop(keep).forEach { stored ->
            runCatching { DocumentFile.fromSingleUri(context, stored.uri)?.delete() }
        }
    }

    // Resolved on each call rather than cached: the user can revoke the grant or delete the
    // folder from a file manager at any time, and a cached handle would keep reporting success.
    private fun root(): DocumentFile? {
        val current = uri ?: return null
        val tree = runCatching { DocumentFile.fromTreeUri(context, current) }.getOrNull()
        return tree?.takeIf { it.isDirectory && it.canWrite() }
    }

    private companion object {
        const val PREFS = "app_settings"
        const val KEY_TREE = "storage_tree_uri"
    }
}

data class StoredFile(val name: String, val uri: Uri, val size: Long, val modifiedAt: Long)
