package app.chattranslator

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudePromptTest {
    @Test fun requestCarriesTargetContextAndMessages() {
        val json = JSONObject(
            ClaudePrompt.messagesRequest(
                listOf(ClaudePrompt.Item(0, "¿Vienes?"), ClaudePrompt.Item(1, "Je suis malade")),
                listOf("Hola a todos"),
                "Dutch",
            )
        )
        assertEquals("Dutch", json.getString("target_language"))
        assertEquals("Hola a todos", json.getJSONArray("context").getString(0))
        val translate = json.getJSONArray("translate")
        assertEquals(2, translate.length())
        assertEquals(1, translate.getJSONObject(1).getInt("id"))
        assertEquals("Je suis malade", translate.getJSONObject(1).getString("text"))
    }

    @Test fun parsesStructuredAnswer() {
        val results = ClaudePrompt.parseMessages(
            """{"translations":[{"id":0,"language":"ES","translation":" Kom je? "},{"id":1,"language":"fr-FR","translation":"Ik ben ziek"}]}"""
        )
        assertEquals(listOf(ClaudePrompt.Result(0, "es", "Kom je?"), ClaudePrompt.Result(1, "fr", "Ik ben ziek")), results)
    }

    @Test fun normalizesLanguageCodes() {
        assertEquals("es", ClaudePrompt.normalizeLanguage("es-MX"))
        assertEquals("pt", ClaudePrompt.normalizeLanguage("PT_br"))
        assertEquals("und", ClaudePrompt.normalizeLanguage("spa"))
        assertEquals("und", ClaudePrompt.normalizeLanguage(""))
    }

    @Test fun skipsTranslationsThatAddNothing() {
        assertFalse(ClaudePrompt.isUseful("Hallo", ClaudePrompt.Result(0, "nl", "Hallo"), "nl"))
        assertFalse(ClaudePrompt.isUseful("Jajaja", ClaudePrompt.Result(0, "und", "jajaja"), "nl"))
        assertFalse(ClaudePrompt.isUseful("Hola", ClaudePrompt.Result(0, "es", " "), "nl"))
        assertTrue(ClaudePrompt.isUseful("Hola", ClaudePrompt.Result(0, "es", "Hoi"), "nl"))
    }

    @Test fun schemaRequiresEveryField() {
        @Suppress("UNCHECKED_CAST")
        val item = ((ClaudePrompt.MESSAGES_SCHEMA["properties"] as Map<String, Any>)["translations"] as Map<String, Any>)["items"] as Map<String, Any>
        assertEquals(listOf("id", "language", "translation"), item["required"])
        assertEquals(false, item["additionalProperties"])
    }
}
