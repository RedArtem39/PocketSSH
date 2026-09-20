package com.pocketssh.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** What gets painted behind the keypad. Every style except [Solid] animates continuously. */
enum class LockBackgroundStyle { Aurora, Waves, Grid, Rain, Solid }

enum class LockKeyShape { Circle, Squircle, Rounded }

/** How a key is filled: frosted, opaque, outline-only, or nothing but the label. */
enum class LockKeyStyle { Glass, Solid, Outline, Ghost }

/**
 * Icons a key can show instead of (or next to) its label. Kept as an enum rather than a raw
 * icon name so a corrupted or downgraded preferences blob can never resolve to a missing
 * drawable — unknown values fall back to [None].
 */
enum class LockGlyph { None, Check, Call, Arrow, LockOpen, Bolt, Terminal, Heart, Star, Key, Power, Shield }

/**
 * Everything the user can tweak about the lock screen. Purely cosmetic: [labels] and [glyphs]
 * change what a key *looks* like, never which digit it types, so a customised pad still enters
 * the same PIN.
 */
data class LockAppearance(
    val accent: Long = DefaultAccent,
    val background: LockBackgroundStyle = LockBackgroundStyle.Aurora,
    val keyShape: LockKeyShape = LockKeyShape.Circle,
    val keyStyle: LockKeyStyle = LockKeyStyle.Glass,
    /** Index = the digit the key types. Blank means the key is drawn empty. */
    val labels: List<String> = DefaultLabels,
    /** Index = the digit the key types. */
    val glyphs: List<LockGlyph> = DefaultGlyphs,
    val confirmGlyph: LockGlyph = LockGlyph.Check,
    /** 0 means "follow [accent]". */
    val confirmColor: Long = 0L,
    /** Blank means the localized default title. */
    val greeting: String = "",
    val showClock: Boolean = true,
    val showHint: Boolean = true,
    val haptics: Boolean = true,
    /** Submit automatically once the typed length matches the stored PIN length. */
    val autoSubmit: Boolean = true,
    val keySizeDp: Int = DefaultKeySize,
) {
    fun label(digit: Int): String = labels.getOrElse(digit) { digit.toString() }

    fun glyph(digit: Int): LockGlyph = glyphs.getOrElse(digit) { LockGlyph.None }

    fun withLabel(digit: Int, value: String): LockAppearance =
        copy(labels = labels.mapIndexed { i, old -> if (i == digit) value else old })

    fun withGlyph(digit: Int, value: LockGlyph): LockAppearance =
        copy(glyphs = glyphs.mapIndexed { i, old -> if (i == digit) value else old })

    companion object {
        const val DefaultAccent = 0xFF65D6ADL
        const val DefaultKeySize = 72
        const val MinKeySize = 54
        const val MaxKeySize = 88
        const val MaxLabelLength = 3

        val DefaultLabels: List<String> = (0..9).map { it.toString() }
        val DefaultGlyphs: List<LockGlyph> = List(10) { LockGlyph.None }

        /** Swatches offered in the customiser. The first one matches the app's own accent. */
        val AccentPresets: List<Long> = listOf(
            DefaultAccent,
            0xFF93B7FF,
            0xFFB18CFF,
            0xFFFF8FB1,
            0xFFFFB86B,
            0xFFFFE066,
            0xFF6EE7F9,
            0xFF7CE38B,
            0xFFFF6B6B,
            0xFFE2E8F0,
        )
    }
}

/**
 * Persists [LockAppearance] in the same plain preferences file as the language choice — none of
 * this is secret, and keeping it out of the Keystore-encrypted store means the lock screen can
 * paint itself without ever touching the key that guards the profiles.
 */
class LockAppearanceStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): LockAppearance {
        val raw = prefs.getString(KEY, null) ?: return LockAppearance()
        return runCatching { decode(JSONObject(raw)) }.getOrDefault(LockAppearance())
    }

    fun save(appearance: LockAppearance) {
        prefs.edit().putString(KEY, encode(appearance).toString()).apply()
    }

    private fun encode(a: LockAppearance) = JSONObject().apply {
        put("accent", a.accent)
        put("background", a.background.name)
        put("keyShape", a.keyShape.name)
        put("keyStyle", a.keyStyle.name)
        put("labels", JSONArray(a.labels))
        put("glyphs", JSONArray(a.glyphs.map { it.name }))
        put("confirmGlyph", a.confirmGlyph.name)
        put("confirmColor", a.confirmColor)
        put("greeting", a.greeting)
        put("showClock", a.showClock)
        put("showHint", a.showHint)
        put("haptics", a.haptics)
        put("autoSubmit", a.autoSubmit)
        put("keySize", a.keySizeDp)
    }

    private fun decode(json: JSONObject): LockAppearance {
        val defaults = LockAppearance()
        return LockAppearance(
            accent = json.optLong("accent", defaults.accent),
            background = json.optEnum("background", defaults.background),
            keyShape = json.optEnum("keyShape", defaults.keyShape),
            keyStyle = json.optEnum("keyStyle", defaults.keyStyle),
            labels = json.optStrings("labels", LockAppearance.DefaultLabels)
                .map { it.take(LockAppearance.MaxLabelLength) },
            glyphs = json.optStrings("glyphs", LockAppearance.DefaultGlyphs.map { it.name })
                .map { name -> LockGlyph.entries.firstOrNull { it.name == name } ?: LockGlyph.None },
            confirmGlyph = json.optEnum("confirmGlyph", defaults.confirmGlyph),
            confirmColor = json.optLong("confirmColor", defaults.confirmColor),
            greeting = json.optString("greeting", defaults.greeting),
            showClock = json.optBoolean("showClock", defaults.showClock),
            showHint = json.optBoolean("showHint", defaults.showHint),
            haptics = json.optBoolean("haptics", defaults.haptics),
            autoSubmit = json.optBoolean("autoSubmit", defaults.autoSubmit),
            keySizeDp = json.optInt("keySize", defaults.keySizeDp)
                .coerceIn(LockAppearance.MinKeySize, LockAppearance.MaxKeySize),
        )
    }

    // A list that came back the wrong length would desync "index == the digit this key types",
    // so a malformed array is dropped wholesale rather than padded.
    private fun JSONObject.optStrings(name: String, fallback: List<String>): List<String> {
        val array = optJSONArray(name) ?: return fallback
        if (array.length() != fallback.size) return fallback
        return (0 until array.length()).map { array.optString(it, fallback[it]) }
    }

    private inline fun <reified T : Enum<T>> JSONObject.optEnum(name: String, fallback: T): T {
        val raw = optString(name, fallback.name)
        return enumValues<T>().firstOrNull { it.name == raw } ?: fallback
    }

    private companion object {
        const val PREFS = "app_settings"
        const val KEY = "lock_appearance"
    }
}
