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

    /** Language the user last replied in (Learn panel), or null if never. */
    var replyLanguage: String?
        get() = prefs.getString(KEY_REPLY, null)
        set(value) = prefs.edit().putString(KEY_REPLY, value).apply()

    /** Languages the user understands: messages in these are left untranslated. */
    var excludedLanguages: Set<String>
        get() = prefs.getStringSet(KEY_EXCLUDED, emptySet())!!.toSet()
        set(value) = prefs.edit().putStringSet(KEY_EXCLUDED, value.toSet()).apply()

    /** Translate with Claude (needs [claudeApiKey]) instead of only on the phone. */
    var useClaude: Boolean
        get() = prefs.getBoolean(KEY_USE_CLAUDE, false)
        set(value) = prefs.edit().putBoolean(KEY_USE_CLAUDE, value).apply()

    /** The user's own Anthropic API key. Stored in this app's private storage (not backed up). */
    var claudeApiKey: String?
        get() = prefs.getString(KEY_CLAUDE_KEY, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_CLAUDE_KEY, value?.trim()).apply()

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
        const val KEY_REPLY = "reply_language"
        const val KEY_USE_CLAUDE = "use_claude"
        const val KEY_EXCLUDED = "excluded_languages"
        const val KEY_CLAUDE_KEY = "claude_api_key"

        val supportedLanguages: Set<String> by lazy { TranslateLanguage.getAllLanguages().toSet() }

        /** English language name for prompts ("nl" -> "Dutch"). */
        fun englishName(code: String): String =
            Locale.forLanguageTag(code).getDisplayName(Locale.ENGLISH).ifBlank { code }

        fun displayName(code: String): String =
            Locale.forLanguageTag(code).getDisplayName(Locale.getDefault())
                .replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }
}
