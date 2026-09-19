package com.pocketssh.app

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

private const val PREFS = "app_settings"
private const val KEY_LANGUAGE_TAG = "language_tag"

// MainActivity is a plain FragmentActivity (BiometricPrompt needs a FragmentActivity host, not
// AppCompatActivity), so AppCompatDelegate's per-app-locale backport has nothing to hook into —
// its attachBaseContext wrapping only fires for AppCompatActivity. Applying the locale by hand
// here, via a manual attachBaseContext override, works the same way on every minSdk without that
// dependency.
object AppLocale {
    fun get(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LANGUAGE_TAG, null)

    fun set(context: Context, tag: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (tag == null) remove(KEY_LANGUAGE_TAG) else putString(KEY_LANGUAGE_TAG, tag)
        }.apply()
    }

    fun wrap(base: Context): Context {
        val tag = get(base) ?: return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}
