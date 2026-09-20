package com.pocketssh.app.update

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

data class ReleaseInfo(
    val tag: String,
    val title: String,
    val version: Version,
    val notes: String,
    val apkUrl: String?,
    val apkName: String?,
    val apkSize: Long,
    /** The companion downgrade helper, published alongside the app since v0.5.0. */
    val recoveryUrl: String?,
    val recoverySize: Long,
    val publishedAt: String,
    val prerelease: Boolean,
)

/**
 * A dotted version, compared numerically. Anything after the numbers (a `-rc1` suffix, say) is
 * kept for display but ignored when comparing — this app has never published one, and guessing
 * at pre-release ordering would be worse than treating them as equal.
 */
data class Version(val parts: List<Int>, val raw: String) : Comparable<Version> {
    override fun compareTo(other: Version): Int {
        for (i in 0 until maxOf(parts.size, other.parts.size)) {
            val mine = parts.getOrElse(i) { 0 }
            val theirs = other.parts.getOrElse(i) { 0 }
            if (mine != theirs) return mine.compareTo(theirs)
        }
        return 0
    }

    override fun toString(): String = raw

    companion object {
        fun parse(raw: String): Version {
            val trimmed = raw.trim().removePrefix("v").removePrefix("V")
            val numeric = trimmed.takeWhile { it.isDigit() || it == '.' }
            val parts = numeric.split('.').mapNotNull { it.toIntOrNull() }
            return Version(parts, trimmed)
        }
    }
}

/**
 * Reads releases straight from the GitHub REST API. No token: the app only ever reads a public
 * repository, and the unauthenticated limit of 60 requests an hour per address is far more than
 * an update check needs.
 */
object GitHubReleases {
    private const val TIMEOUT_MS = 15_000

    /** Newest first, as GitHub returns them. Drafts are skipped; pre-releases are marked. */
    fun list(repo: String): List<ReleaseInfo> {
        val body = get("https://api.github.com/repos/$repo/releases?per_page=20")
        val array = JSONArray(body)
        return (0 until array.length())
            .map { array.getJSONObject(it) }
            .filterNot { it.optBoolean("draft", false) }
            .map(::parseRelease)
    }

    private fun parseRelease(json: JSONObject): ReleaseInfo {
        val tag = json.optString("tag_name")
        val assets = json.optJSONArray("assets") ?: JSONArray()
        val apks = (0 until assets.length())
            .map { assets.getJSONObject(it) }
            .filter { it.optString("name").endsWith(".apk", ignoreCase = true) }
        // Both APKs are attached to the same release, so they are told apart by name rather than
        // by position — asset order is not something the API promises.
        val recovery = apks.firstOrNull { it.optString("name").contains("recovery", ignoreCase = true) }
        val apk = apks.firstOrNull { it !== recovery }
        return ReleaseInfo(
            tag = tag,
            title = json.optString("name").ifBlank { tag },
            version = Version.parse(tag),
            notes = json.optString("body").trim(),
            apkUrl = apk?.optString("browser_download_url"),
            apkName = apk?.optString("name"),
            apkSize = apk?.optLong("size") ?: 0L,
            recoveryUrl = recovery?.optString("browser_download_url"),
            recoverySize = recovery?.optLong("size") ?: 0L,
            publishedAt = json.optString("published_at"),
            prerelease = json.optBoolean("prerelease", false),
        )
    }

    private fun get(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "PocketSSH")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                // GitHub puts the reason (rate limit, missing repo) in the error body, and that
                // is far more useful to show than a bare status code.
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val message = runCatching { JSONObject(detail).optString("message") }.getOrNull()
                throw IllegalStateException(message?.takeIf { it.isNotBlank() } ?: "HTTP $code")
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
