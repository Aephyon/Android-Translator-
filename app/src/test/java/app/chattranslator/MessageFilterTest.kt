package app.chattranslator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageFilterTest {
    @Test fun keepsNormalMessages() {
        assertEquals("¿Vienes a cenar el domingo?", MessageFilter.clean("  ¿Vienes a cenar el domingo?\n"))
    }

    @Test fun dropsTimestamps() {
        assertNull(MessageFilter.clean("14:05"))
        assertNull(MessageFilter.clean("9:41 PM"))
        assertNull(MessageFilter.clean("9.41 a.m."))
    }

    @Test fun dropsEmojiNumbersAndBlanks() {
        assertNull(MessageFilter.clean("😂😂👍"))
        assertNull(MessageFilter.clean("12345"))
        assertNull(MessageFilter.clean("   "))
        assertNull(MessageFilter.clean(null))
        assertNull(MessageFilter.clean("a"))
    }

    @Test fun dropsBareLinks() {
        assertNull(MessageFilter.clean("https://example.com/foto"))
        assertEquals("Mira esto https://example.com", MessageFilter.clean("Mira esto https://example.com"))
    }

    @Test fun capsVeryLongText() {
        assertEquals(MessageFilter.MAX_LENGTH, MessageFilter.clean("a".repeat(5000))!!.length)
    }
}
