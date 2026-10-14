package com.folio.hermes.openrouter

import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Transcript cleanup on chat completions (the cleanup slice), on MockWebServer. */
class CleanupClientTest {
    private val server = MockWebServer().apply { start() }

    @After fun tearDown() = server.close()

    private fun test(block: suspend () -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    private val input = CleanupInput("check the castral aviators", "check the castral aviator", listOf("castral"), listOf("Kestrel"))

    private fun chat(content: String) = jsonResponse(
        """{"choices":[{"message":{"content":${JsonPrimitive(content)}}}]}""",
    )

    @Test fun theRequestIsTheBenchsShape() = test {
        server.enqueue(chat("""{"text": "check the Kestrel aviators"}"""))
        val result = server.openRouter().cleanup.clean(input, 1_500)
        assertEquals(OpenRouterResult.Ok("check the Kestrel aviators"), result)
        val r = server.takeRequest()
        assertEquals("/api/v1/chat/completions", r.url.encodedPath)
        val body = Json.parseToJsonElement(r.body!!.utf8()).jsonObject
        assertEquals("deepseek/deepseek-v4.1-flash", body["model"]!!.jsonPrimitive.content)
        assertEquals("0", body["temperature"]!!.jsonPrimitive.content)
        assertEquals("json_object", body["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("false", body["reasoning"]!!.jsonObject["enabled"]!!.jsonPrimitive.content)
        val messages = body["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(CleanupClient.SYSTEM, messages[0]["content"]!!.jsonPrimitive.content)
        val user = Json.parseToJsonElement(messages[1]["content"]!!.jsonPrimitive.content).jsonObject
        assertEquals("check the castral aviators", user["heard"]!!.jsonPrimitive.content)
        assertEquals("check the castral aviator", user["other"]!!.jsonPrimitive.content)
        assertEquals(listOf("castral"), user["unsure"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("Kestrel"), user["vocabulary"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    /** The prompt is the bench's `SYSTEM`, byte for byte (sha256 of the Python string in `jevprobe/cleanup.py`). */
    @Test fun thePromptIsTheMeasuredOne() {
        val sha = MessageDigest.getInstance("SHA-256").digest(CleanupClient.SYSTEM.toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals("84c0f86ee419fbdea195c955fc9de55904969e48b27928bfe0c2845c17f60ffa", sha)
    }

    @Test fun aBadReplyIsAFailure() = test {
        server.enqueue(chat("Sure! Here you go: check the Kestrel aviators"))
        assertEquals(OpenRouterResult.Failed(FailureKind.BadBody, 200), server.openRouter().cleanup.clean(input, 1_500))
        server.enqueue(chat("""{"text": ""}"""))
        assertTrue(server.openRouter().cleanup.clean(input, 1_500) is OpenRouterResult.Failed)
        server.enqueue(jsonResponse("""{"error":"x"}""", code = 500))
        assertEquals(OpenRouterResult.Failed(FailureKind.Http, 500), server.openRouter().cleanup.clean(input, 1_500))
        assertEquals("no retry: one request per failure", 3, server.requestCount)
    }

    @Test fun aReplyWrappedInProseOrFencesStillReads() {
        assertEquals("in stock", CleanupClient.parseText("```json\n{\"text\": \"in stock\"}\n```"))
    }

    @Test fun noKeyMakesNoRequestAndTelemetryCarriesNoText() = test {
        val sink = CapturingSink()
        assertEquals(OpenRouterResult.Failed(FailureKind.NoKey), server.openRouter(key = null, sink = sink).cleanup.clean(input, 1_500))
        assertEquals(0, server.requestCount)
        server.enqueue(chat("""{"text": "check the Kestrel aviators"}"""))
        server.openRouter(sink = sink).cleanup.clean(input, 1_500)
        assertFalse(sink.text(), sink.text().contains("castral") || sink.text().contains("Kestrel") || sink.text().contains(FAKE_KEY))
        assertTrue(sink.events.all { it.first == OpenRouter.EVENT && it.second["call"] == CleanupClient.LABEL })
    }
}
