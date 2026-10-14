package com.folio.hermeslive.data

import com.folio.hermes.cascade.AudioClip
import com.folio.hermes.cascade.DirectoryClipStore
import com.folio.hermes.cascade.TtsCache
import com.folio.hermes.openrouter.SpeechFormat
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The voice lists and previews (ondemand slice) against a local server: OpenRouter's speech
 * models (the response's shape as checked live on 2026-10-14, trimmed), cached a day and falling
 * back; xAI's voices only with a key; previews cached so the second costs nothing.
 */
class VoiceCatalogTest {
    private val server = MockWebServer().apply { start() }
    private val dir: File = Files.createTempDirectory("voices").toFile()
    private var now = 1_000_000_000L

    @After fun tearDown() {
        server.close()
        dir.deleteRecursively()
    }

    private fun catalog() = VoiceCatalog(
        OkHttpClient(), dir, clock = { now },
        openRouterUrl = server.url("/api/v1/models?output_modalities=speech").toString(),
        xaiUrl = server.url("/v1/tts/voices").toString(),
    )

    private fun ok(body: String) = server.enqueue(MockResponse.Builder().code(200).body(body).build())

    @Test fun openRouterSpeechModelsWithTheirVoicesThenTheCache() = runBlocking {
        ok(MODELS)
        val first = catalog().ttsModels()
        assertEquals(VoiceSource.Live, first.source)
        assertEquals(listOf("elevenlabs/eleven-v4-turbo", "x-ai/grok-voice-tts-1.0"), first.items.map { it.id })
        val eleven = first.items.first()
        assertTrue("bill" in eleven.voices)
        assertEquals(20.0, eleven.usdPerMillionChars!!, 0.001)
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("speech", request.url.queryParameter("output_modalities"))
        assertNull("no key is sent for the public model list", request.headers["Authorization"])
        val again = catalog().ttsModels()
        assertEquals("within a day: the saved list, no request", VoiceSource.Cached, again.source)
        assertEquals(1, server.requestCount)
        now += VoiceCatalog.DAY_MS
        server.enqueue(MockResponse.Builder().code(503).build())
        val stale = catalog().ttsModels()
        assertEquals("a failed refresh keeps the saved list", VoiceSource.Cached, stale.source)
        assertEquals(2, stale.items.size)
    }

    @Test fun nothingFetchedAndNothingSavedMeansBillOnElevenLabs() = runBlocking {
        server.enqueue(MockResponse.Builder().code(500).build())
        val list = catalog().ttsModels()
        assertEquals(VoiceSource.BuiltIn, list.source)
        assertEquals(listOf(VoiceCatalog.DEFAULT_TTS), list.items)
        assertEquals(listOf("bill"), list.items.single().voices)
    }

    @Test fun xaiVoicesNeedAKeyElseTheDocumentedOnes() = runBlocking {
        val none = catalog().grokVoices(null)
        assertEquals(VoiceSource.BuiltIn, none.source)
        assertTrue(none.items.any { it.id == "eve" })
        assertEquals("no key: no request", 0, server.requestCount)
        ok("""{"voices":[{"voice_id":"eve","name":"Eve"},{"voice_id":"Nova","name":"Nova"},{"name":"no id"}]}""")
        val live = catalog().grokVoices("test-xai-key")
        assertEquals(VoiceSource.Live, live.source)
        assertEquals(listOf(VoiceOption("eve", "Eve"), VoiceOption("nova", "Nova")), live.items)
        assertEquals("Bearer test-xai-key", server.takeRequest(5, TimeUnit.SECONDS)!!.headers["Authorization"])
    }

    @Test fun aRefusedXaiKeyFallsBackToTheBuiltInVoices() = runBlocking {
        server.enqueue(MockResponse.Builder().code(401).build())
        val list = catalog().grokVoices("bad-key", force = true)
        assertEquals(VoiceSource.BuiltIn, list.source)
    }

    @Test fun choosingAModelKeepsTheVoiceIfItCanElseBillElseTheFirst() {
        val eleven = TtsModelInfo("e", "E", listOf("george", "bill"))
        val gemini = TtsModelInfo("g", "G", listOf("Zephyr", "Puck"))
        assertEquals("george", VoiceCatalog.voiceFor(eleven, "george"))
        assertEquals("bill", VoiceCatalog.voiceFor(eleven, "Zephyr"))
        assertEquals("Zephyr", VoiceCatalog.voiceFor(gemini, "bill"))
    }

    @Test fun malformedListsParseToNothing() {
        assertTrue(VoiceCatalog.parseOpenRouter("not json").isEmpty())
        assertTrue(VoiceCatalog.parseOpenRouter("""{"data":[{"id":"x/no-voices","supported_voices":null}]}""").isEmpty())
        assertTrue(VoiceCatalog.parseXai("""{"voices":"eve"}""").isEmpty())
    }

    // ── previews ─────────────────────────────────────────────────────────────────────────────

    private val cache = TtsCache(DirectoryClipStore(File(dir, "tts")), 10_000_000)
    private val played = mutableListOf<AudioClip>()

    @Test fun aCustomPreviewPlaysAndTheSecondIsFromTheCache() = runBlocking {
        var fetched = 0
        val p = VoicePreview({ cache }, { _, _, _ -> fetched++; AudioClip(ByteArray(960), SpeechFormat.Pcm()) }, { null }, XaiTts(OkHttpClient(), server.url("/v1/tts").toString())) { played += it }
        assertEquals(PreviewOutcome.Played, p.custom("elevenlabs/eleven-v4-turbo", "george"))
        assertEquals(PreviewOutcome.Played, p.custom("elevenlabs/eleven-v4-turbo", "george"))
        assertEquals(1, fetched)
        assertEquals(2, played.size)
        assertEquals(PreviewOutcome.Played, p.custom("elevenlabs/eleven-v4-turbo", "bill"))
        assertEquals(2, fetched)
        val noKey = VoicePreview({ cache }, null, { null }, XaiTts(OkHttpClient())) { played += it }
        assertEquals(PreviewOutcome.NoKey, noKey.custom("m/x", "bill"))
    }

    @Test fun aGrokPreviewAsksXaiForPcmOnceWithTheKey() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body(okio.Buffer().write(ByteArray(4_800))).build())
        val xai = XaiTts(OkHttpClient(), server.url("/v1/tts").toString())
        var key: String? = null
        val p = VoicePreview({ cache }, null, { key }, xai) { played += it }
        assertEquals("no xAI key: no request", PreviewOutcome.NoKey, p.grok("ara"))
        assertEquals(0, server.requestCount)
        key = "test-xai-key"
        assertEquals(PreviewOutcome.Played, p.grok("ara"))
        val r = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("Bearer test-xai-key", r.headers["Authorization"])
        val body = r.body!!.utf8()
        assertTrue(body, "\"voice_id\":\"ara\"" in body && "\"codec\":\"pcm\"" in body && "\"sample_rate\":24000" in body)
        assertEquals(24_000, played.last().format.sampleRateHz)
        key = null
        assertEquals("cached: plays without a key or a request", PreviewOutcome.Played, p.grok("ara"))
        assertEquals(1, server.requestCount)
        server.enqueue(MockResponse.Builder().code(400).build())
        key = "test-xai-key"
        assertEquals(PreviewOutcome.Failed, p.grok("nobody"))
    }

    private companion object {
        /** OpenRouter's `/models?output_modalities=speech` shape (2026-10-14), trimmed to two models and made-up prices. */
        val MODELS = """
            {"data":[
              {"id":"elevenlabs/eleven-v4-turbo","name":"ElevenLabs: Eleven v4 Turbo","pricing":{"prompt":"0.00002","completion":"0"},
               "architecture":{"output_modalities":["speech"]},"supported_voices":["george","sarah","bill","will"]},
              {"id":"x-ai/grok-voice-tts-1.0","name":"xAI: Grok Voice TTS","pricing":{"prompt":"0.000015"},"supported_voices":["eve","ara","rex","sal","leo"]},
              {"id":"some/no-voice-model","name":"No voices","supported_voices":[]}
            ]}
        """.trimIndent()
    }
}
