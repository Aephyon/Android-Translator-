package app.chattranslator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanguageTagsTest {
    private val supported = setOf("es", "en", "nl", "he", "id", "zh", "tl")

    @Test fun plainCodes() {
        assertEquals("es", LanguageTags.toSupported("es", supported))
        assertEquals("zh", LanguageTags.toSupported("zh", supported))
    }

    @Test fun undeterminedOrUnsupported() {
        assertNull(LanguageTags.toSupported("und", supported))
        assertNull(LanguageTags.toSupported(null, supported))
        assertNull(LanguageTags.toSupported("xx", supported))
    }

    @Test fun legacyCodes() {
        assertEquals("he", LanguageTags.toSupported("iw", supported))
        assertEquals("id", LanguageTags.toSupported("in", supported))
        assertEquals("tl", LanguageTags.toSupported("fil", supported))
    }

    @Test fun romanizedScriptsAreSkipped() {
        assertNull(LanguageTags.toSupported("zh-Latn", supported))
    }

    @Test fun regionSuffixIsIgnored() {
        assertEquals("es", LanguageTags.toSupported("es-MX", supported))
    }
}
