package com.folio.hermes.openrouter

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.MultipartReader
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cloud speech-to-text through OpenRouter (`/audio/transcriptions`, cloud STT slice), on MockWebServer. */
class TranscriptionTest {
    private val server = MockWebServer().apply { start() }

    @After fun tearDown() = server.close()

    private fun test(block: suspend () -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    /** A tiny stand-in for a WAV (the client does not look inside). */
    private val wav = ByteArray(64) { it.toByte() }

    /** The multipart parts of [r], by name: text values, and the file's bytes under "file". */
    private fun parts(r: RecordedRequest): Map<String, ByteArray> {
        val boundary = r.headers["Content-Type"]!!.substringAfter("boundary=")
        val out = LinkedHashMap<String, ByteArray>()
        MultipartReader(Buffer().write(r.body!!), boundary).use { reader ->
            while (true) {
                val part = reader.nextPart() ?: break
                val name = part.headers["Content-Disposition"]!!.substringAfter("name=\"").substringBefore('"')
                out[name] = part.body.readByteArray()
            }
        }
        return out
    }

    @Test fun theUploadIsMultipartWithModelFileAndTheUsersWords() = test {
        server.enqueue(jsonResponse("""{"text":" Email Halden and tell them. ","usage":{"seconds":3.2,"cost":0.0004}}"""))
        val router = server.openRouter()
        val result = router.transcription.transcribe(wav, CloudSttModels.ASSEMBLYAI, listOf("Halden", " halden ", "Kestrel", ""), timeoutMs = 3_000)
        assertEquals(OpenRouterResult.Ok("Email Halden and tell them."), result)
        val r = server.takeRequest()
        assertEquals("POST", r.method)
        assertEquals("/api/v1/audio/transcriptions", r.url.encodedPath)
        assertEquals("Bearer $FAKE_KEY", r.headers["Authorization"])
        assertTrue(r.headers["Content-Type"]!!.startsWith("multipart/form-data"))
        val p = parts(r)
        assertEquals(listOf("model", "file", "provider"), p.keys.toList())
        assertEquals("assemblyai/universal-3-5-pro", p.getValue("model").decodeToString())
        assertArrayEquals(wav, p.getValue("file"))
        val provider = Json.parseToJsonElement(p.getValue("provider").decodeToString()).jsonObject
        val terms = provider["options"]!!.jsonObject["assemblyai"]!!.jsonObject["keyterms_prompt"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals("trimmed, repeats and blanks dropped", listOf("Halden", "Kestrel"), terms)
    }

    @Test fun aModelThatTakesNoWordsGetsNone() = test {
        server.enqueue(jsonResponse("""{"text":"Hi."}"""))
        server.openRouter().transcription.transcribe(wav, CloudSttModels.ELEVENLABS, listOf("Halden"), 3_000)
        assertEquals(listOf("model", "file"), parts(server.takeRequest()).keys.toList())
        server.enqueue(jsonResponse("""{"text":"Hi."}"""))
        server.openRouter().transcription.transcribe(wav, CloudSttModels.ASSEMBLYAI, emptyList(), 3_000)
        assertEquals("no words: no provider part", listOf("model", "file"), parts(server.takeRequest()).keys.toList())
    }

    @Test fun aCustomModelIdIsSentAsTypedWithNoWords() = test {
        server.enqueue(jsonResponse("""{"text":"Hi."}"""))
        val custom = CloudSttModels.of("someone/new-stt-2")
        assertNull(custom.vocabulary)
        assertNull(custom.dollarsPerHour)
        server.openRouter().transcription.transcribe(wav, custom, listOf("Halden"), 3_000)
        val p = parts(server.takeRequest())
        assertEquals("someone/new-stt-2", p.getValue("model").decodeToString())
        assertFalse("provider" in p)
        assertEquals(CloudSttModels.ASSEMBLYAI, CloudSttModels.of("assemblyai/universal-3-5-pro"))
    }

    @Test fun termsAreCappedPerModel() {
        val many = (1..300).map { "word$it" }
        val provider = TranscriptionClient.provider(CloudSttModels.ASSEMBLYAI, many)!!
        val terms = Json.parseToJsonElement(provider).jsonObject["options"]!!.jsonObject["assemblyai"]!!.jsonObject["keyterms_prompt"]!!.jsonArray
        assertEquals(CloudSttModels.ASSEMBLYAI.maxTerms, terms.size)
    }

    @Test fun noKeyMakesNoRequest() = test {
        val sink = CapturingSink()
        val result = server.openRouter(key = null, sink = sink).transcription.transcribe(wav, CloudSttModels.ASSEMBLYAI, emptyList(), 3_000)
        assertEquals(OpenRouterResult.Failed(FailureKind.NoKey), result)
        assertEquals(0, server.requestCount)
        assertEquals(TranscriptionClient.EVENT_ERROR, sink.events.single().first)
    }

    @Test fun failuresAreKindsAndTelemetryCarriesNoContent() = test {
        val sink = CapturingSink()
        val router = server.openRouter(sink = sink)
        server.enqueue(jsonResponse("""{"error":{"message":"bad audio"}}""", code = 400))
        assertEquals(OpenRouterResult.Failed(FailureKind.Http, 400), router.transcription.transcribe(wav, CloudSttModels.ASSEMBLYAI, listOf("SECRET-NAME"), 3_000))
        server.enqueue(jsonResponse("""{"nope":1}"""))
        assertEquals(OpenRouterResult.Failed(FailureKind.BadBody), router.transcription.transcribe(wav, CloudSttModels.ASSEMBLYAI, emptyList(), 3_000))
        server.enqueue(jsonResponse("""{"text":"late"}""").newBuilder().headersDelay(2, TimeUnit.SECONDS).build())
        assertEquals(OpenRouterResult.Failed(FailureKind.Timeout), router.transcription.transcribe(wav, CloudSttModels.ASSEMBLYAI, emptyList(), 300))
        server.enqueue(jsonResponse("""{"text":"SECRET-TRANSCRIPT"}"""))
        assertTrue(router.transcription.transcribe(wav, CloudSttModels.ASSEMBLYAI, emptyList(), 3_000) is OpenRouterResult.Ok)

        assertEquals(
            listOf(TranscriptionClient.EVENT_ERROR, TranscriptionClient.EVENT_ERROR, TranscriptionClient.EVENT_TIMEOUT, TranscriptionClient.EVENT_OK),
            sink.events.map { it.first },
        )
        assertTrue("model and ms on every event", sink.events.all { (_, a) -> a["model"] == "assemblyai/universal-3-5-pro" && a["ms"] is Long })
        assertFalse("not openrouter.call as well", sink.events.any { it.first == "openrouter.call" })
        val text = sink.text()
        listOf("SECRET-NAME", "SECRET-TRANSCRIPT", "bad audio", "late", FAKE_KEY).forEach { assertFalse("$it leaked", it in text) }
    }

    @Test fun offlineIsOffline() = test {
        val router = server.openRouter()
        server.close()
        val result = router.transcription.transcribe(wav, CloudSttModels.ASSEMBLYAI, emptyList(), 3_000)
        assertEquals(OpenRouterResult.Failed(FailureKind.Offline), result)
    }
}
