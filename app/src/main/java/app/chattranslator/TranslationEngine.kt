package app.chattranslator

import android.os.SystemClock
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

sealed interface TranslationResult {
    /** Still detecting / downloading / translating. */
    data object Pending : TranslationResult
    /** Already in the target language, or not a language we can translate. */
    data object NotNeeded : TranslationResult
    data class Done(val text: String, val sourceLanguage: String) : TranslationResult
    data class Failed(val at: Long) : TranslationResult
}

/**
 * Detects the language of each message and translates it on the device with ML Kit.
 * Results are cached per message text, so scrolling back and forth costs nothing.
 * Must be used from the main thread.
 */
class TranslationEngine(targetLanguage: String) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val identifier = LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder().setConfidenceThreshold(MIN_CONFIDENCE).build()
    )
    private val modelManager = RemoteModelManager.getInstance()
    private val translators = HashMap<String, Translator>()
    private val readyLanguages = HashSet<String>()
    private val cache = LruCache<String, TranslationResult>(CACHE_SIZE)
    private val inFlight = HashSet<String>()
    private var generation = 0

    var target: String = targetLanguage
        private set

    /** Called when a translation finished and the screen should be redrawn. */
    var onUpdated: (() -> Unit)? = null

    /** Called the first time a language pack has to be downloaded. */
    var onDownloadStarted: ((language: String) -> Unit)? = null

    fun setTarget(language: String) {
        if (language == target) return
        target = language
        generation++
        cache.clear()
        inFlight.clear()
        readyLanguages.clear()
        translators.values.forEach { it.close() }
        translators.clear()
    }

    /** Returns what is known right now and starts work in the background if needed. */
    fun lookup(text: String): TranslationResult {
        when (val cached = cache[text]) {
            null -> Unit
            is TranslationResult.Failed ->
                if (SystemClock.elapsedRealtime() - cached.at < RETRY_AFTER_MS) return cached
            else -> return cached
        }
        if (!inFlight.add(text)) return TranslationResult.Pending

        val startedIn = generation
        scope.launch {
            val result = try {
                translate(text)
            } catch (e: Exception) {
                TranslationResult.Failed(SystemClock.elapsedRealtime())
            }
            if (startedIn != generation) return@launch // target language changed meanwhile
            inFlight.remove(text)
            cache[text] = result
            if (result !is TranslationResult.NotNeeded) onUpdated?.invoke()
        }
        return TranslationResult.Pending
    }

    private suspend fun translate(text: String): TranslationResult {
        val tag = identifier.identifyLanguage(text).await()
        val source = LanguageTags.toSupported(tag, AppSettings.supportedLanguages)
        if (source == null || source == target) return TranslationResult.NotNeeded

        val translator = translators.getOrPut(source) {
            Translation.getClient(
                TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build()
            )
        }
        ensureDownloaded(source, translator)
        val translated = translator.translate(text).await().trim()
        if (translated.isEmpty() || translated.equals(text, ignoreCase = true)) return TranslationResult.NotNeeded
        return TranslationResult.Done(translated, source)
    }

    private suspend fun ensureDownloaded(source: String, translator: Translator) {
        if (source in readyLanguages) return
        val missing = listOf(source, target).any {
            !modelManager.isModelDownloaded(TranslateRemoteModel.Builder(it).build()).await()
        }
        if (missing) onDownloadStarted?.invoke(source)
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        readyLanguages += source
    }

    fun close() {
        scope.cancel()
        identifier.close()
        translators.values.forEach { it.close() }
        translators.clear()
    }

    private companion object {
        const val MIN_CONFIDENCE = 0.5f
        const val CACHE_SIZE = 1000
        const val RETRY_AFTER_MS = 30_000L
    }
}
