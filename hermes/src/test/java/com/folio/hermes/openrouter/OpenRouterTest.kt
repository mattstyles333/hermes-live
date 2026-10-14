package com.folio.hermes.openrouter

import com.folio.hermes.telemetry.TelemetrySink
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** A placeholder that can never match a real OpenRouter key's format. */
internal const val FAKE_KEY = "test-key-not-real"

/** Records every telemetry event, to prove what would be sent. */
internal class CapturingSink : TelemetrySink {
    val events = CopyOnWriteArrayList<Pair<String, Map<String, Any?>>>()
    override fun event(name: String, attrs: Map<String, Any?>, warn: Boolean) {
        events += name to attrs
    }
    fun text(): String = events.joinToString("\n") { (n, a) -> "$n $a" }
}

internal fun MockWebServer.openRouter(
    key: String? = FAKE_KEY,
    sink: TelemetrySink = TelemetrySink.None,
    tweak: (OpenRouterConfig) -> OpenRouterConfig = { it },
): OpenRouter = OpenRouter(
    config = tweak(
        OpenRouterConfig(
            baseUrl = url("/api/v1").toString(),
            retryBackoffMs = 30,
            jevTimeoutMs = 2_000,
            writerTimeoutMs = 3_000,
            streamReadTimeoutMs = 3_000,
            speechTimeoutMs = 3_000,
        ),
    ),
    key = { key },
    telemetry = sink,
)

internal fun jsonResponse(body: String, code: Int = 200): MockResponse =
    MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

class OpenRouterTest {
    private val server = MockWebServer().apply { start() }

    @After fun tearDown() = server.close()

    private fun test(block: suspend () -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    private val state = buildJsonObject { put("heard", "SECRET-TRANSCRIPT") }
    private val questions = mapOf("turn" to JevQuestion("Is it done?", mapOf("done" to "yes", "keep_listening" to "no")))

    @Test fun noKeyMakesNoRequestAtAll() = test {
        val sink = CapturingSink()
        val router = server.openRouter(key = "  ", sink = sink)
        assertFalse(router.hasKey)
        assertEquals(OpenRouterResult.Failed(FailureKind.NoKey), router.jev.decide(state, questions))
        assertEquals(OpenRouterResult.Failed(FailureKind.NoKey), router.writer.write("s", "p"))
        assertEquals(OpenRouterResult.Failed(FailureKind.NoKey), router.checkKey())
        assertFalse("no warm-up without a key", router.warm())
        try {
            router.writer.stream("s", "p").toList()
            fail("stream without a key")
        } catch (e: OpenRouterException) {
            assertEquals(FailureKind.NoKey, e.failure.kind)
        }
        try {
            router.speech.speak("hello").toList()
            fail("speech without a key")
        } catch (e: OpenRouterException) {
            assertEquals(FailureKind.NoKey, e.failure.kind)
        }
        assertEquals(0, server.requestCount)
    }

    /** Latency: one small GET /key opens the connection ahead of a call; its answer (even a refusal) is ignored. */
    @Test fun warmOpensTheConnectionWithOneSmallRequest() = test {
        server.enqueue(jsonResponse("""{"data":{}}"""))
        server.enqueue(jsonResponse("{}", code = 401))
        val sink = CapturingSink()
        val router = server.openRouter(sink = sink)
        assertTrue(router.warm())
        assertTrue("a refused key still warmed the connection", router.warm())
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/v1/key", request.url.encodedPath)
        assertEquals(2, server.requestCount)
        assertTrue(sink.events.all { (name, attrs) -> name == "openrouter.call" && attrs["call"] == "warm" })
        assertFalse(FAKE_KEY in sink.text())
        assertEquals(15_000, router.http.pingIntervalMillis)
    }

    @Test fun jevSendsSystemOneShapeAndParsesAnswers() = test {
        server.enqueue(
            jsonResponse(
                """{"answers":{"turn":{"type":"choice","choice":"done","probabilities":{"done":0.9,"keep_listening":0.1},"confidence":0.9,"extra":1}},"usage":{}}""",
            ),
        )
        val result = server.openRouter().jev.decide(state, questions)
        val answers = (result as OpenRouterResult.Ok).value
        assertEquals("done", answers["turn"]!!.choice)
        assertEquals(0.9, answers["turn"]!!.confidence, 1e-9)
        val request = server.takeRequest()
        assertEquals("/api/v1/systemone", request.url.encodedPath)
        assertEquals("Bearer $FAKE_KEY", request.headers["Authorization"])
        assertEquals("Hermes Live", request.headers["X-Title"])
        val body = request.jsonBody()
        assertEquals("typesafe/jev-1.13", body["model"]!!.jsonPrimitive.content)
        assertEquals("SECRET-TRANSCRIPT", body["state"]!!.jsonObject["heard"]!!.jsonPrimitive.content)
        val turn = body["questions"]!!.jsonObject["turn"]!!.jsonObject
        assertEquals("choice", turn["type"]!!.jsonPrimitive.content)
        assertEquals(setOf("done", "keep_listening"), turn["criteria"]!!.jsonObject.keys)
    }

    @Test fun jevFailuresAreClassifiedAndNeverRetried() = test {
        server.enqueue(jsonResponse("""{"error":"no"}""", 401))
        server.enqueue(jsonResponse("""{"error":"busy"}""", 503))
        server.enqueue(jsonResponse("""{"answers":{}}"""))
        val router = server.openRouter()
        assertEquals(OpenRouterResult.Failed(FailureKind.Refused, 401), router.jev.decide(state, questions))
        assertEquals(OpenRouterResult.Failed(FailureKind.Http, 503), router.jev.decide(state, questions))
        assertEquals(OpenRouterResult.Failed(FailureKind.BadBody, 200), router.jev.decide(state, questions))
        assertEquals(3, server.requestCount)
    }

    @Test fun jevTimeoutIsTimeoutAndOfflineIsOffline() = test {
        server.enqueue(jsonResponse("""{"answers":{"turn":{"choice":"done","confidence":1}}}""").newBuilder().headersDelay(1, TimeUnit.SECONDS).build())
        val slow = server.openRouter().jev.decide(state, questions, timeoutMs = 200)
        assertEquals(OpenRouterResult.Failed(FailureKind.Timeout), slow)
        val dead = MockWebServer().apply { start() }
        val url = dead.url("/api/v1").toString()
        dead.close()
        val offline = OpenRouter(OpenRouterConfig(baseUrl = url), key = { FAKE_KEY }, telemetry = TelemetrySink.None)
        assertEquals(OpenRouterResult.Failed(FailureKind.Offline), offline.jev.decide(state, questions))
    }

    @Test fun checkKeyReadsTheKeyEndpoint() = test {
        server.enqueue(jsonResponse("""{"data":{}}"""))
        server.enqueue(jsonResponse("""{"error":"bad"}""", 401))
        val router = server.openRouter()
        assertEquals(OpenRouterResult.Ok(true), router.checkKey())
        assertEquals(OpenRouterResult.Ok(false), router.checkKey())
        assertEquals("/api/v1/key", server.takeRequest().url.encodedPath)
    }

    @Test fun writerTurnsReasoningOffAndRetriesOnceAfterA5xx() = test {
        server.enqueue(jsonResponse("""{"error":"x"}""", 502))
        server.enqueue(jsonResponse("""{"choices":[{"message":{"content":"  On it. Started.  "}}]}"""))
        val result = server.openRouter().writer.write("system", "prompt")
        assertEquals(OpenRouterResult.Ok("On it. Started."), result)
        assertEquals(2, server.requestCount)
        val body = server.takeRequest().jsonBody()
        assertEquals("deepseek/deepseek-v4.1-flash", body["model"]!!.jsonPrimitive.content)
        assertFalse(body["reasoning"]!!.jsonObject["enabled"]!!.jsonPrimitive.boolean)
        assertNull(body["stream"])
    }

    @Test fun writerDoesNotRetryA4xx() = test {
        server.enqueue(jsonResponse("""{"error":"x"}""", 400))
        assertEquals(OpenRouterResult.Failed(FailureKind.Http, 400), server.openRouter().writer.write("s", "p"))
        assertEquals(1, server.requestCount)
    }

    @Test fun writerStreamSkipsCommentsAndStopsAtDone() = test {
        val sse = listOf(
            ": OPENROUTER PROCESSING",
            "",
            """data: {"choices":[{"delta":{"content":"On it."}}]}""",
            "",
            """data: {"choices":[{"delta":{"content":" The stock check"}}]}""",
            """data: {"choices":[{"delta":{"content":" is 2.5 days"}}]}""",
            """data: {"choices":[{"delta":{"content":" out."}}]}""",
            """data: {"choices":[{"delta":{},"finish_reason":"stop"}]}""",
            "data: [DONE]",
            """data: {"choices":[{"delta":{"content":"AFTER DONE"}}]}""",
            "",
        ).joinToString("\n")
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/event-stream").chunkedBody(sse, 17).build())
        val router = server.openRouter()
        val sentences = router.writer.sentences("s", "p").toList()
        assertEquals(listOf("On it.", "The stock check is 2.5 days out."), sentences)
        assertTrue(server.takeRequest().jsonBody()["stream"]!!.jsonPrimitive.boolean)
    }

    @Test fun writerStreamErrorChunkFails() = test {
        server.enqueue(
            MockResponse.Builder().addHeader("Content-Type", "text/event-stream")
                .body("""data: {"choices":[{"delta":{"content":"Hi"}}]}""" + "\n\n" + """data: {"error":{"code":502,"message":"upstream"}}""" + "\n\n")
                .build(),
        )
        val got = mutableListOf<String>()
        try {
            server.openRouter().writer.stream("s", "p").collect { got += it }
            fail("expected failure")
        } catch (e: OpenRouterException) {
            assertEquals(FailureKind.Http, e.failure.kind)
            assertEquals(502, e.failure.httpStatus)
        }
        assertEquals(listOf("Hi"), got)
        assertEquals(1, server.requestCount) // something was emitted: never retried, never heard twice
    }

    @Test fun writerStreamRetriesBeforeAnyText() = test {
        server.enqueue(jsonResponse("{}", 503))
        server.enqueue(MockResponse.Builder().body("data: {\"choices\":[{\"delta\":{\"content\":\"Sure.\"}}]}\n\ndata: [DONE]\n").build())
        assertEquals(listOf("Sure."), server.openRouter().writer.stream("s", "p").toList())
        assertEquals(2, server.requestCount)
    }

    @Test fun speechWholeClipReadsRateFromContentType() = test {
        val pcm = ByteArray(4_410) { it.toByte() }
        server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "audio/pcm;rate=22050;channels=1")
                .addHeader("X-Generation-Id", "gen-1")
                .body(Buffer().write(pcm))
                .build(),
        )
        val parts = server.openRouter().speech.speak("Testing Bill.", delivery = SpeechDelivery.WholeClip).toList()
        val started = parts.first() as SpeechAudio.Started
        assertEquals(SpeechFormat.Pcm(22_050, 1, 16), started.format)
        assertEquals("gen-1", started.generationId)
        val chunks = parts.filterIsInstance<SpeechAudio.Chunk>()
        assertEquals(1, chunks.size)
        assertArrayEquals(pcm, chunks.single().bytes)
        assertEquals(SpeechAudio.Finished(4_410, 100), parts.last())
        val request = server.takeRequest()
        assertEquals("/api/v1/audio/speech", request.url.encodedPath)
        val body = request.jsonBody()
        assertEquals("elevenlabs/eleven-v4-turbo", body["model"]!!.jsonPrimitive.content)
        assertEquals("bill", body["voice"]!!.jsonPrimitive.content)
        assertEquals("pcm", body["response_format"]!!.jsonPrimitive.content)
        assertEquals("Testing Bill.", body["input"]!!.jsonPrimitive.content)
    }

    @Test fun speechDefaultsTo24kWithoutARate() = test {
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "audio/pcm").body(Buffer().write(ByteArray(480))).build())
        val parts = server.openRouter().speech.speak("x").toList()
        assertEquals(SpeechFormat.Pcm(24_000), (parts.first() as SpeechAudio.Started).format)
        assertEquals(10L, (parts.last() as SpeechAudio.Finished).durationMs)
    }

    @Test fun speechStreamedEmitsBeforeTheBodyEndsOnWholeFrames() = test {
        val pcm = ByteArray(6_001) { (it % 251).toByte() }
        server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "audio/pcm;rate=24000;channels=1")
                .chunkedBody(Buffer().write(pcm), 1_001)
                .throttleBody(1_001, 100, TimeUnit.MILLISECONDS)
                .build(),
        )
        val t0 = System.nanoTime()
        val arrivals = mutableListOf<Long>()
        val chunks = mutableListOf<ByteArray>()
        server.openRouter().speech.speak("x", delivery = SpeechDelivery.Streamed).collect { part ->
            if (part is SpeechAudio.Chunk) {
                arrivals += (System.nanoTime() - t0) / 1_000_000
                chunks += part.bytes
            }
        }
        val total = (System.nanoTime() - t0) / 1_000_000
        assertTrue("several chunks: ${chunks.size}", chunks.size >= 3)
        assertTrue("first audio ${arrivals.first()} ms well before the end $total ms", arrivals.first() < total - 200)
        chunks.forEach { assertEquals("whole 16-bit frames", 0, it.size % 2) }
        // The odd last byte has no pair: it is held back, never played as half a sample.
        assertArrayEquals(pcm.copyOf(6_000), chunks.fold(ByteArray(0)) { acc, b -> acc + b })
    }

    @Test fun speechNon2xxEmitsNoAudio() = test {
        server.enqueue(jsonResponse("""{"error":"x"}""", 402))
        val got = mutableListOf<SpeechAudio>()
        try {
            server.openRouter().speech.speak("x").collect { got += it }
            fail("expected failure")
        } catch (e: OpenRouterException) {
            assertEquals(OpenRouterResult.Failed(FailureKind.Http, 402), e.failure)
        }
        assertTrue(got.isEmpty())
        assertEquals(1, server.requestCount)
    }

    @Test fun speechRetriesOnceThenGivesUp() = test {
        server.enqueue(jsonResponse("{}", 500))
        server.enqueue(jsonResponse("{}", 500))
        try {
            server.openRouter().speech.speak("x").toList()
            fail("expected failure")
        } catch (e: OpenRouterException) {
            assertEquals(500, e.failure.httpStatus)
        }
        assertEquals(2, server.requestCount)
    }

    @Test fun telemetryCarriesNoContentAndNoKey() = test {
        val sink = CapturingSink()
        server.enqueue(jsonResponse("""{"answers":{"turn":{"choice":"done","confidence":0.9}}}"""))
        server.enqueue(jsonResponse("""{"choices":[{"message":{"content":"SECRET-ANSWER"}}]}"""))
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "audio/pcm").body(Buffer().write(ByteArray(48))).build())
        server.enqueue(jsonResponse("""{"error":"SECRET-ERROR"}""", 500))
        val router = server.openRouter(sink = sink) { it.copy(writerRetries = 0) }
        router.jev.decide(state, questions)
        router.writer.write("SECRET-SYSTEM", "SECRET-PROMPT")
        router.speech.speak("SECRET-SPOKEN").toList()
        router.writer.write("SECRET-SYSTEM", "SECRET-PROMPT")
        assertEquals(4, sink.events.size)
        val all = sink.text()
        listOf("SECRET", FAKE_KEY, "Bearer").forEach { assertFalse("telemetry leaked $it: $all", all.contains(it)) }
        assertEquals(listOf("jev", "writer", "tts", "writer"), sink.events.map { it.second["call"] })
        assertEquals(500, sink.events.last().second["http_status"])
    }

    @Test fun frameAlignerCarriesTheOddByte() {
        val aligner = FrameAligner(2)
        assertNull(aligner.push(byteArrayOf(1)))
        assertArrayEquals(byteArrayOf(1, 2), aligner.push(byteArrayOf(2, 3)))
        assertArrayEquals(byteArrayOf(3, 4, 5, 6), aligner.push(byteArrayOf(4, 5, 6)))
    }

    @Test fun contentTypeParsing() {
        val pcm = SpeechFormat.Pcm()
        assertEquals(SpeechFormat.Pcm(16_000, 2), SpeechFormat.fromContentType("audio/pcm; rate=16000; channels=2", pcm))
        assertEquals(pcm, SpeechFormat.fromContentType("audio/pcm;rate=abc", pcm))
        assertEquals(SpeechFormat.Mp3, SpeechFormat.fromContentType("audio/mpeg", SpeechFormat.Mp3))
        assertEquals(24_000 * 2L, SpeechFormat.Pcm().bytesFor(1_000))
    }

    private fun mockwebserver3.RecordedRequest.jsonBody(): JsonObject =
        com.folio.hermes.HermesJson.parseToJsonElement(body!!.utf8()).jsonObject
}
