package com.folio.hermes.cascade

import com.folio.hermes.openrouter.OpenRouterResult
import com.folio.hermes.openrouter.SpeechFormat
import com.folio.hermes.openrouter.openRouter
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A fake `/audio/speech`: 24 kHz PCM, 10 bytes per character; counts requests and peak concurrency. */
internal class FakeTts {
    val server = MockWebServer()
    val requests = AtomicInteger()
    val inFlight = AtomicInteger()
    val peak = AtomicInteger()
    @Volatile var failText: String? = null
    @Volatile var delayMs = 0L

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.incrementAndGet()
                val now = inFlight.incrementAndGet()
                peak.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                try {
                    if (delayMs > 0) Thread.sleep(delayMs)
                    val input = Regex("\"input\":\"([^\"]*)\"").find(request.body!!.utf8())?.groupValues?.get(1).orEmpty()
                    if (input == failText) return MockResponse.Builder().code(500).body("{}").build()
                    return MockResponse.Builder().addHeader("Content-Type", "audio/pcm;rate=24000;channels=1")
                        .body(Buffer().write(ByteArray(input.length * 10))).build()
                } finally {
                    inFlight.decrementAndGet()
                }
            }
        }
        server.start()
    }

    fun voice(cache: TtsCache = TtsCache(MemoryClipStore()), voice: String = "bill", model: String = "elevenlabs/eleven-v4-turbo"): BillVoice =
        BillVoice(server.openRouter().speech, cache, { voice }, { model })
}

class AudioCacheTest {
    private val tts = FakeTts()

    @After fun tearDown() = tts.server.close()

    private fun clip(bytes: Int) = AudioClip(ByteArray(bytes) { it.toByte() }, SpeechFormat.Pcm())

    @Test fun keyIsSha256OfModelVoiceFormatText() {
        val k = TtsCache.key("m", "bill", SpeechFormat.Pcm(), "On it.")
        assertEquals(64, k.length)
        assertTrue(k.all { it in "0123456789abcdef" })
        assertNotEquals(k, TtsCache.key("m", "other", SpeechFormat.Pcm(), "On it."))
        assertNotEquals(k, TtsCache.key("m2", "bill", SpeechFormat.Pcm(), "On it."))
        assertNotEquals(k, TtsCache.key("m", "bill", SpeechFormat.Mp3, "On it."))
        assertEquals(k, TtsCache.key("m", "bill", SpeechFormat.Pcm(16_000), "On it."))
    }

    @Test fun lruEvictsTheLeastRecentlyUsed() {
        var t = 0L
        val cache = TtsCache(MemoryClipStore(), maxBytes = 3 * (1_000 + 12), now = { ++t })
        cache.put("a", clip(1_000))
        cache.put("b", clip(1_000))
        cache.put("c", clip(1_000))
        assertTrue(cache.get("a") != null) // a is now most recent
        cache.put("d", clip(1_000))
        assertNull("b was least recently used", cache.get("b"))
        assertTrue(cache.contains("a") && cache.contains("c") && cache.contains("d"))
        assertTrue(cache.sizeBytes <= 3 * 1_012)
    }

    @Test fun directoryStoreSurvivesARestartWithItsFormat() {
        val dir = Files.createTempDirectory("tts").toFile()
        val pcm = AudioClip(byteArrayOf(1, 2, 3, 4), SpeechFormat.Pcm(22_050, 1, 16))
        TtsCache(DirectoryClipStore(dir)).put("k", pcm)
        val again = TtsCache(DirectoryClipStore(dir)).get("k")!!
        assertArrayEquals(pcm.bytes, again.bytes)
        assertEquals(22_050, again.format.sampleRateHz)
        dir.deleteRecursively()
    }

    @Test fun aCachedLinePlaysWithNoRequest() = runBlocking {
        val voice = tts.voice()
        val first = voice.clip("Got it.")
        assertTrue(first is OpenRouterResult.Ok)
        assertEquals(1, tts.requests.get())
        val again = voice.clip("Got it.")
        assertEquals(70, (again as OpenRouterResult.Ok).value.bytes.size)
        assertEquals("second play came from the cache", 1, tts.requests.get())
        assertTrue(voice.isCached("Got it."))
        assertFalse(voice.isCached("Something new."))
    }

    @Test fun rendererFetchesOnlyChangedLinesFourAtATime() = runBlocking {
        val bank = PhraseBank.load()
        val cache = TtsCache(MemoryClipStore())
        tts.delayMs = 20
        val first = PhraseRenderer(tts.voice(cache), telemetry = com.folio.hermes.telemetry.TelemetrySink.None).render(bank)
        assertEquals(RenderProgress(total = 285, done = 285, cached = 0, failed = 0, finished = true), first)
        assertEquals(285, tts.requests.get())
        assertTrue("at most 4 at a time, saw ${tts.peak.get()}", tts.peak.get() <= 4)

        // The next version changes one line and adds one slot: only those two lines are fetched.
        val slots = bank.slots.map { if (it.id == "ack.do") it.copy(lines = it.lines.dropLast(1) + "Righto.") else it } +
            PhraseSlot("ack.extra", "ack", "new", listOf("Brand new line."))
        val v4 = PhraseBank(4, bank.groups, slots)
        val second = PhraseRenderer(tts.voice(cache), telemetry = com.folio.hermes.telemetry.TelemetrySink.None).render(v4)
        assertEquals(287, tts.requests.get())
        assertEquals(284, second.cached)
        assertEquals(286, second.total)

        // A new voice re-renders everything (new keys).
        tts.delayMs = 0
        PhraseRenderer(tts.voice(cache, voice = "other"), telemetry = com.folio.hermes.telemetry.TelemetrySink.None).render(v4)
        assertEquals(287 + 286, tts.requests.get())
    }

    @Test fun rendererSkipsAFailureAfterOneRetry() = runBlocking {
        val bank = PhraseBank(1, emptyMap(), listOf(PhraseSlot("a.b", "ack", "u", listOf("Fine.", "Broken."))))
        tts.failText = "Broken."
        val renderer = PhraseRenderer(tts.voice(), telemetry = com.folio.hermes.telemetry.TelemetrySink.None)
        val progress = renderer.render(bank)
        assertEquals(1, progress.failed)
        assertEquals(1, progress.done)
        assertEquals("one request for Fine., two for Broken.", 3, tts.requests.get())
        assertEquals(progress, renderer.progress.value)
    }
}
