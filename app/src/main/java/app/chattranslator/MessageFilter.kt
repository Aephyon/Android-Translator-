package app.chattranslator

/** Decides which pieces of on-screen text are worth sending to the translator. */
object MessageFilter {
    /** Longer texts are cut off; ML Kit is meant for sentences, not essays. */
    const val MAX_LENGTH = 3000

    private val time = Regex("""^\d{1,2}[:.]\d{2}(\s?[ap]\.?\s?m\.?)?$""", RegexOption.IGNORE_CASE)
    private val url = Regex("""^(https?://|www\.)\S+$""", RegexOption.IGNORE_CASE)

    /** Returns the text to translate, or null when it is a timestamp, link, emoji-only, etc. */
    fun clean(raw: CharSequence?): String? {
        val text = raw?.toString()?.trim() ?: return null
        if (text.count { it.isLetter() } < 2) return null
        if (time.matches(text) || url.matches(text)) return null
        return text.take(MAX_LENGTH)
    }
}
