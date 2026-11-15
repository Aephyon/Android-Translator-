package app.chattranslator

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Runs the real SDK against a local stand-in for the Anthropic API: checks what we send and parse. */
class ClaudeTranslatorWireTest {
    private val server = MockWebServer()
    private var lastPath = ""
    private var lastBody = ""
    private val lastHeaders = HashMap<String, String>()

    @Before fun start() = server.start()

    @After fun stop() = server.shutdown()

    private fun respond(status: Int, body: String) =
        server.enqueue(MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body))

    private fun takeRequest() {
        val request = server.takeRequest()
        lastPath = request.path.orEmpty()
        lastBody = request.body.readUtf8()
        request.headers.forEach { (k, v) -> lastHeaders[k.lowercase()] = v }
    }

    private fun translator() = ClaudeTranslator("test-key", server.url("/").toString().trimEnd('/'))

    private fun message(text: String, stopReason: String = "end_turn") = JSONObject()
        .put("id", "msg_test").put("type", "message").put("role", "assistant").put("model", "claude-haiku-5-5")
        .put("content", org.json.JSONArray().put(JSONObject().put("type", "text").put("text", text)))
        .put("stop_reason", stopReason).put("stop_sequence", JSONObject.NULL)
        .put("usage", JSONObject().put("input_tokens", 10).put("output_tokens", 20))
        .toString()

    @Test fun sendsBatchWithContextAndParsesTranslations() {
        respond(200, message("""{"translations":[{"id":0,"language":"es","translation":"Kom je zondag eten?"}]}"""))
        val results = translator().translateMessages(
            listOf(ClaudePrompt.Item(0, "¿Vienes a cenar el domingo?")), listOf("Hola familia"), "nl",
        )
        takeRequest()
        assertEquals(listOf(ClaudePrompt.Result(0, "es", "Kom je zondag eten?")), results)

        assertTrue(lastPath, lastPath.startsWith("/v1/messages"))
        assertEquals("test-key", lastHeaders["x-api-key"])
        assertEquals(null, lastHeaders["authorization"]) // API keys don't go in a Bearer header
        val body = JSONObject(lastBody)
        assertEquals("claude-haiku-5-5", body.getString("model"))
        assertEquals("disabled", body.getJSONObject("thinking").getString("type"))
        assertTrue(!body.has("fallbacks")) // Haiku has no server-side fallback
        assertEquals("low", body.getJSONObject("output_config").getString("effort"))
        val format = body.getJSONObject("output_config").getJSONObject("format")
        assertEquals("json_schema", format.getString("type"))
        assertEquals("object", format.getJSONObject("schema").getString("type"))
        assertEquals(ClaudePrompt.MESSAGES_SYSTEM, body.get("system"))
        val user = JSONObject(body.getJSONArray("messages").getJSONObject(0).getString("content"))
        assertEquals("Dutch", user.getString("target_language"))
        assertEquals("Hola familia", user.getJSONArray("context").getString(0))
    }

    @Test fun rejectedKeyIsReportedAsSuch() {
        respond(401, """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""")
        try {
            translator().translateMessages(listOf(ClaudePrompt.Item(0, "Hola")), emptyList(), "nl")
            fail("expected KeyRejected")
        } catch (e: ClaudeTranslator.KeyRejected) {
            // expected
        }
    }

    @Test fun refusalIsAnError() {
        respond(200, message("", stopReason = "refusal"))
        try {
            translator().translateMessages(listOf(ClaudePrompt.Item(0, "Hola")), emptyList(), "nl")
            fail("expected an exception")
        } catch (e: IllegalStateException) {
            // expected: the engine falls back to on-device translation
        }
    }

    @Test fun replyIsPlainText() {
        respond(200, message("¡Hasta el domingo!"))
        assertEquals("¡Hasta el domingo!", translator().translateReply("Tot zondag!", "nl", "es", listOf("¿Vienes a cenar el domingo?")))
        takeRequest()
        val body = JSONObject(lastBody)
        assertEquals(ClaudePrompt.REPLY_SYSTEM, body.get("system"))
        val content = body.getJSONArray("messages").getJSONObject(0).getString("content")
        assertTrue(content.contains("from Dutch into Spanish"))
        assertTrue(content.contains("- ¿Vienes a cenar el domingo?"))
    }
}
