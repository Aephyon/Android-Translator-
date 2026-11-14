package app.chattranslator

/** Maps a language tag from ML Kit's language identifier onto a code the translator supports. */
object LanguageTags {
    /** Old ISO codes some libraries still emit. */
    private val legacy = mapOf("iw" to "he", "in" to "id", "ji" to "yi", "fil" to "tl")

    fun toSupported(tag: String?, supported: Set<String>): String? {
        if (tag.isNullOrBlank() || tag == "und") return null
        val parts = tag.lowercase().split('-', '_')
        // e.g. "hi-Latn": Hindi typed in Latin letters. The translator only reads native script.
        if (parts.drop(1).contains("latn")) return null
        val base = legacy[parts[0]] ?: parts[0]
        return base.takeIf { it in supported }
    }
}
