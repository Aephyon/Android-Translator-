package app.chattranslator

import android.os.SystemClock
import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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

    /** Called (once per key) when Claude rejects the API key; translations fall back meanwhile. */
    var onClaudeKeyRejected: (() -> Unit)? = null

    /**
     * When set, foreign messages are translated by Claude (with [chatContext] as context) and the
     * on-device translator is only the backup for when Claude can't be reached.
     */
    var claude: ClaudeTranslator? = null
        set(value) {
            if (field === value) return
            field?.let { old -> scope.launch(Dispatchers.IO) { runCatching { old.close() } } }
            field = value
            claudeKeyReported = false
            resetCache()
        }
    private var claudeKeyReported = false

    /** The messages currently on screen, oldest first: context for Claude. */
    var chatContext: List<String> = emptyList()

    private val waitingForClaude = LinkedHashMap<String, CompletableDeferred<ClaudePrompt.Result?>>()
    private var claudeFlush: Job? = null

    fun setTarget(language: String) {
        if (language == target) return
        target = language
        readyLanguages.clear()
        translators.values.forEach { it.close() }
        translators.clear()
        resetCache()
    }

    private fun resetCache() {
        generation++
        cache.clear()
        inFlight.clear()
        waitingForClaude.values.forEach { it.cancel() }
        waitingForClaude.clear()
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
                Log.w(TAG, "Translation failed", e)
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
        Log.d(TAG, "Detected '$tag' -> $source for ${text.take(40)}")
        // Clearly in the user's own language: nothing to do (and nothing to pay Claude for).
        if (source == target) return TranslationResult.NotNeeded
        if (claude != null) {
            try {
                return viaClaude(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Claude unavailable, using on-device translation", e)
            }
        }
        if (source == null) return TranslationResult.NotNeeded
        return onDevice(text, source)
    }

    private suspend fun onDevice(text: String, source: String): TranslationResult {
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

    // ---- Claude ---------------------------------------------------------------------------

    /** Queues [text] for the next Claude batch and waits for its result. */
    private suspend fun viaClaude(text: String): TranslationResult {
        val deferred = waitingForClaude.getOrPut(text) { CompletableDeferred() }
        if (claudeFlush?.isActive != true) {
            claudeFlush = scope.launch {
                delay(CLAUDE_BATCH_WAIT_MS) // collect the other messages on screen into one request
                flushToClaude()
            }
        }
        val result = deferred.await() ?: return TranslationResult.NotNeeded
        return if (ClaudePrompt.isUseful(text, result, target)) TranslationResult.Done(result.translation, result.language)
        else TranslationResult.NotNeeded
    }

    private fun flushToClaude() {
        val translator = claude ?: return
        while (waitingForClaude.isNotEmpty()) {
            val batch = waitingForClaude.entries.take(CLAUDE_BATCH_SIZE).map { it.key to it.value }
            batch.forEach { waitingForClaude.remove(it.first) }
            val items = batch.mapIndexed { i, (text, _) -> ClaudePrompt.Item(i, text) }
            val texts = batch.map { it.first }.toSet()
            val context = chatContext.filter { it !in texts }.takeLast(CLAUDE_CONTEXT_SIZE)
            val language = target
            scope.launch {
                try {
                    val results = withContext(Dispatchers.IO) { translator.translateMessages(items, context, language) }
                        .associateBy { it.id }
                    batch.forEachIndexed { i, (_, deferred) -> deferred.complete(results[i]) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (e is ClaudeTranslator.KeyRejected && !claudeKeyReported) {
                        claudeKeyReported = true
                        onClaudeKeyRejected?.invoke()
                    }
                    batch.forEach { (_, deferred) -> deferred.completeExceptionally(e) }
                }
            }
        }
    }

    private suspend fun ensureDownloaded(source: String, translator: Translator) {
        if (source in readyLanguages) return
        val missing = listOf(source, target).any {
            !modelManager.isModelDownloaded(TranslateRemoteModel.Builder(it).build()).await()
        }
        if (missing) onDownloadStarted?.invoke(source)
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        Log.i(TAG, "Language pack ready: $source -> $target")
        readyLanguages += source
    }

    private val replyTranslators = HashMap<String, Translator>()

    /**
     * Translates text the user typed from [from] into [to] (for replies). Downloads the language
     * packs on first use. Throws if translation isn't possible (e.g. no internet for a download).
     */
    suspend fun translateReply(text: String, from: String, to: String): String {
        if (from == to) return text
        claude?.let { translator ->
            try {
                val context = chatContext
                return withContext(Dispatchers.IO) { translator.translateReply(text, from, to, context) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Claude unavailable for reply, using on-device translation", e)
            }
        }
        val translator = replyTranslators.getOrPut("$from>$to") {
            Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(to).build())
        }
        val missing = listOf(from, to).any {
            !modelManager.isModelDownloaded(TranslateRemoteModel.Builder(it).build()).await()
        }
        if (missing) onDownloadStarted?.invoke(to)
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        return translator.translate(text).await().trim()
    }

    fun close() {
        claude = null
        replyTranslators.values.forEach { it.close() }
        replyTranslators.clear()
        scope.cancel()
        identifier.close()
        translators.values.forEach { it.close() }
        translators.clear()
    }

    private companion object {
        const val TAG = "ChatTranslator"
        const val MIN_CONFIDENCE = 0.5f
        const val CACHE_SIZE = 1000
        const val RETRY_AFTER_MS = 30_000L
        const val CLAUDE_BATCH_WAIT_MS = 250L
        const val CLAUDE_BATCH_SIZE = 25
        const val CLAUDE_CONTEXT_SIZE = 30
    }
}
