package app.chattranslator

import android.content.Context
import android.speech.tts.TextToSpeech
import android.widget.Toast
import java.util.Locale

/** Reads text aloud in its own language, using the phone's text-to-speech voices. */
class Speaker(private val context: Context) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: Pair<String, String>? = null

    fun speak(text: String, language: String) {
        val engine = tts
        if (engine == null) {
            pending = text to language
            tts = TextToSpeech(context.applicationContext) { status ->
                ready = status == TextToSpeech.SUCCESS
                if (!ready) toast("Text-to-speech isn't available on this phone.")
                pending?.let { (t, l) -> pending = null; if (ready) say(t, l) }
            }
            return
        }
        if (ready) say(text, language) else pending = text to language
    }

    private fun say(text: String, language: String) {
        val engine = tts ?: return
        val result = engine.setLanguage(Locale.forLanguageTag(language))
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            toast("No ${AppSettings.displayName(language)} voice installed. Add one in Settings → Text-to-speech.")
            return
        }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "chat-translator")
    }

    fun stop() = tts?.stop()

    fun shutdown() {
        tts?.shutdown()
        tts = null
        ready = false
    }

    private fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}
