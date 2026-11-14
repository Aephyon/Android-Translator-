package app.chattranslator

import android.content.Context
import android.content.SharedPreferences
import com.google.mlkit.nl.translate.TranslateLanguage
import java.util.Locale

class AppSettings(context: Context) {
    val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Everything gets translated into this language (an ML Kit code such as "nl" or "en"). */
    var targetLanguage: String
        get() = prefs.getString(KEY_TARGET, null) ?: defaultTarget()
        set(value) = prefs.edit().putString(KEY_TARGET, value).apply()

    /** Off means the overlay stays hidden and WhatsApp shows the originals. */
    var showTranslations: Boolean
        get() = prefs.getBoolean(KEY_SHOW, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW, value).apply()

    /** Vertical position of the floating toggle button, in pixels; -1 = not moved yet. */
    var toggleY: Int
        get() = prefs.getInt(KEY_TOGGLE_Y, -1)
        set(value) = prefs.edit().putInt(KEY_TOGGLE_Y, value).apply()

    private fun defaultTarget(): String =
        LanguageTags.toSupported(Locale.getDefault().language, supportedLanguages) ?: TranslateLanguage.ENGLISH

    companion object {
        const val KEY_TARGET = "target_language"
        const val KEY_SHOW = "show_translations"
        const val KEY_TOGGLE_Y = "toggle_y"

        val supportedLanguages: Set<String> by lazy { TranslateLanguage.getAllLanguages().toSet() }

        fun displayName(code: String): String =
            Locale.forLanguageTag(code).getDisplayName(Locale.getDefault())
                .replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }
}
