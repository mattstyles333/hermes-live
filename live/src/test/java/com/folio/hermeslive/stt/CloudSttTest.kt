package com.folio.hermeslive.stt

import com.folio.hermes.cascade.CascadeSettings
import com.folio.hermes.cascade.TranscriptPass
import com.folio.hermes.cascade.ports.SttEvent
import com.folio.hermes.openrouter.OpenRouter
import com.folio.hermes.openrouter.OpenRouterConfig
import com.folio.hermes.telemetry.TelemetrySink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cloud speech-to-text in the accurate pass (cloud STT slice): which text is chosen (by arrival,
 * never waiting past the on-device pass), the fallback on error, timeout and offline, the event
 * the caption and Jev get, and no audio request at all on device.
 */
class CloudSttTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun tearDown() = scope.cancel()

    private val fast = listOf(FastWord("we", 0.9), FastWord("have", 0.9), FastWord("in", 0.9), FastWord("sto", 0.3))
    private val fastText = "We have in sto"

    /** Runs device decodes only when [runAll] is called (the device pass "finishes" then). */
    private class ManualExecutor : java.util.concurrent.AbstractExecutorService() {
        private val queue = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) = synchronized(queue) { queue.addLast(command) }
        fun runAll() {
            while (true) (synchronized(queue) { queue.removeFirstOrNull() } ?: return).run()
        }
        override fun shutdown() = Unit
        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
        override fun isShutdown() = false
        override fun isTerminated() = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
    }

    /** A cloud whose answer the test gives ([answer]); records each call. */
    private class ScriptedCloud : CloudStt {
        val calls = CopyOnWriteArrayList<Triple<ByteArray, String, List<String>>>()
        val answer = CompletableDeferred<String?>()
        val asked = CountDownLatch(1)
        override suspend fun transcribe(wav: ByteArray, model: String, terms: List<String>, timeoutMs: Long): String? {
            calls += Triple(wav, model, terms)
            asked.countDown()
            return answer.await()
        }
    }

    private class Rig(
        val cloud: CloudStt?,
        model: String? = "assemblyai/universal-3-5-pro",
        val device: ManualExecutor = ManualExecutor(),
        scope: CoroutineScope,
        offline: FakeOffline = FakeOffline { "We have in stoke." },
    ) {
        val revised = CopyOnWriteArrayList<Pair<String, String>>()
        val revisedOnce = CountDownLatch(1)
        val pass = SecondPass(
            engine = offline, hotwords = { "" }, uncertainBelow = { 0.45 }, vocabulary = { listOf("Kestrel", "Halden") },
            executor = device, cloud = cloud, cloudModel = { model }, onCloud = { f, t ->
                revised += f to t
                revisedOnce.countDown()
            }, cloudScope = scope,
        )
    }

    private fun test(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(10_000) { block() } }

    @Test fun theCloudTextIsTheAccuratePassWhenItArrivesFirst() = test {
        val cloud = ScriptedCloud()
        val rig = Rig(cloud, scope = scope)
        rig.pass.submit(FloatArray(1_600), fast)
        assertTrue(cloud.asked.await(5, TimeUnit.SECONDS))
        val (wav, model, terms) = cloud.calls.single()
        assertEquals("assemblyai/universal-3-5-pro", model)
        assertEquals(listOf("Kestrel", "Halden"), terms)
        assertEquals("a 16 kHz mono WAV of the segment", 44 + 1_600 * 2, wav.size)
        cloud.answer.complete("We have in stock.")
        val t = rig.pass.accurate()!!
        assertEquals("We have in stock.", t.text)
        assertEquals(TranscriptPass.Accurate, t.pass)
        assertEquals(listOf(fastText to "We have in stock."), rig.revised)
        assertEquals(1, rig.pass.lastTiming!!.cloudUsed)
    }

    @Test fun theDeviceTextStandsWhenTheCloudIsNotInByThen() = test {
        val cloud = ScriptedCloud()
        val rig = Rig(cloud, scope = scope)
        rig.pass.submit(FloatArray(1_600), fast)
        val result = async { rig.pass.accurate() }
        rig.device.runAll() // the on-device pass finishes; the cloud has not answered
        val t = result.await()!!
        assertEquals("never waits for the cloud past the device pass", "We have in stoke.", t.text)
        assertEquals(0, rig.pass.lastTiming!!.cloudUsed)
        cloud.answer.complete("We have in stock.")
        assertTrue("a late cloud text after the turn is not reported", rig.revised.isEmpty())
    }

    @Test fun aCloudTextAlreadyInBeatsTheDevicePass() = test {
        val cloud = ScriptedCloud()
        val rig = Rig(cloud, scope = scope)
        rig.pass.submit(FloatArray(1_600), fast)
        cloud.answer.complete("We have in stock.")
        assertTrue(rig.revisedOnce.await(5, TimeUnit.SECONDS))
        rig.device.runAll()
        assertEquals("We have in stock.", rig.pass.accurate()!!.text)
    }

    @Test fun aFailedCloudFallsBackToTheDevice() = test {
        // Error, timeout and offline all come back as null from the cloud port.
        val cloud = ScriptedCloud()
        val rig = Rig(cloud, scope = scope)
        rig.pass.submit(FloatArray(1_600), fast)
        cloud.answer.complete(null)
        val result = async { rig.pass.accurate() }
        rig.device.runAll()
        assertEquals("We have in stoke.", result.await()!!.text)
        assertTrue(rig.revised.isEmpty())
    }

    @Test fun aThrowingCloudFallsBackToTheDevice() = test {
        val rig = Rig(CloudStt { _, _, _, _ -> error("boom") }, scope = scope)
        rig.pass.submit(FloatArray(1_600), fast)
        val result = async { rig.pass.accurate() }
        rig.device.runAll()
        assertEquals("We have in stoke.", result.await()!!.text)
    }

    @Test fun onDeviceTheCloudIsNeverAsked() = test {
        val cloud = ScriptedCloud()
        val rig = Rig(cloud, model = null, scope = scope)
        rig.pass.submit(FloatArray(1_600), fast)
        val result = async { rig.pass.accurate() }
        rig.device.runAll()
        assertEquals("We have in stoke.", result.await()!!.text)
        assertTrue(cloud.calls.isEmpty())
        assertEquals(0, rig.pass.lastTiming!!.cloudSent)
    }

    @Test fun anIgnoredTurnCancelsItsUpload() = test {
        val cloud = ScriptedCloud()
        val rig = Rig(cloud, scope = scope)
        rig.pass.submit(FloatArray(1_600), fast)
        assertTrue(cloud.asked.await(5, TimeUnit.SECONDS))
        rig.pass.drop()
        cloud.answer.complete("We have in stock.")
        assertTrue(rig.revised.isEmpty())
        assertEquals(0, rig.pass.heldCount)
    }

    @Test fun cloudUncertaintyUsesTheKeyTokenRule() = test {
        val cloud = ScriptedCloud()
        val rig = Rig(cloud, scope = scope)
        rig.pass.submit(FloatArray(1_600), listOf(FastWord("check", 0.9), FastWord("castrel", 0.9), FastWord("stock", 0.9)))
        cloud.answer.complete("Check Kestrel stock.")
        val t = rig.pass.accurate()!!
        assertEquals(listOf(false, true, false), t.words.map { it.uncertain })
        assertEquals(listOf("castrel"), t.words[1].alternatives)
    }

    @Test fun wavHeaderIs16kMono16Bit() {
        val wav = Wav.encode(floatArrayOf(0f, 1f, -1f))
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals(1, b.getShort(22).toInt()) // mono
        assertEquals(16_000, b.getInt(24))
        assertEquals(16, b.getShort(34).toInt())
        assertEquals(6, b.getInt(40))
        assertEquals(32767, b.getShort(46).toInt())
        assertEquals(-32767, b.getShort(48).toInt())
    }

    @Test fun materialMeansTheWordsDiffer() {
        assertTrue(CloudMateriality.material("Check what we have in sto", "Check what we have in stock."))
        assertTrue(CloudMateriality.material("Email holden", "Email Halden."))
        assertFalse(CloudMateriality.material("Check stock", "check stock."))
        assertFalse(CloudMateriality.material("Um check the stock", "Check the stock."))
        assertFalse(CloudMateriality.material("Order forty two", "Order 42."))
    }

    // ── the off state, end to end on the real client ────────────────────────────────────────

    private val server = MockWebServer().apply { start() }

    @After fun closeServer() = server.close()

    private fun openRouter() = OpenRouter(OpenRouterConfig(baseUrl = server.url("/api/v1").toString()), key = { "test-key-not-real" }, telemetry = TelemetrySink.None)

    /** [frames], then silence until closed (so the call does not end and drop its audio). */
    private class OpenSource(private val frames: List<FloatArray>) : com.folio.hermeslive.audio.AudioSource {
        private var i = 0
        override var onRouteChanged: (() -> Unit)? = null
        override fun open() = Unit
        override fun read(buffer: ShortArray): Int {
            val f = frames.getOrNull(i++)
            if (f == null) {
                Thread.sleep(2)
                buffer.fill(0)
            } else {
                for (k in buffer.indices) buffer[k] = (f[k] * 32768f).toInt().toShort()
            }
            return buffer.size
        }
        override fun close() = Unit
    }

    private class Call(val stt: SherpaSpeechToText, val events: List<SttEvent>) {
        /** Segments sent to the cloud by the last accurate pass (decided as the segment ended). */
        val cloudSent: Int get() = stt.secondPass.lastTiming!!.cloudSent
    }

    /** A whole segment through [SherpaSpeechToText] with [settings], to the accurate pass. */
    private fun call(settings: CascadeSettings, online: Boolean = true, block: suspend (Call) -> Unit = {}) = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body("""{"text":"Hello there."}""").build())
        val stt = SherpaSpeechToText(
            SttEngines(FakeVad(), FakeStreaming(), FakeOffline()), OpenSource(List(5) { silence() } + frames(12)), settings = { settings },
            cloud = OpenRouterCloudStt(openRouter().transcription, online = { online }),
        )
        val events = CopyOnWriteArrayList<SttEvent>()
        val ended = CompletableDeferred<Unit>()
        val revisedSeen = CompletableDeferred<Unit>()
        val collecting = scope.async {
            stt.events.collect {
                events += it
                if (it is SttEvent.SpeechEndedAt) ended.complete(Unit)
                if (it is SttEvent.Revised) revisedSeen.complete(Unit)
            }
        }
        stt.start()
        try {
            withTimeout(5_000) { ended.await() }
            // Cloud mode: let the cloud answer first (a text arriving after the pass is not reported).
            if (settings.cloudSttModel != null && online) withTimeout(5_000) { revisedSeen.await() }
            withTimeout(5_000) { stt.accurate("u1") }
            block(Call(stt, events))
        } finally {
            stt.stop()
            collecting.cancel()
        }
    }

    @Test fun onDeviceNoAudioRequestIsEverMade() = call(CascadeSettings(accuratePass = true)) { c ->
        assertEquals("nothing was sent as the segment ended", 0, c.cloudSent)
        assertEquals(0, server.requestCount)
        assertTrue(c.events.none { it is SttEvent.Revised })
    }

    @Test fun offlineNoAudioRequestIsMade() = call(CascadeSettings(accuratePass = true, cloudSttModel = "assemblyai/universal-3-5-pro"), online = false) {
        assertEquals(0, server.requestCount)
    }

    @Test fun inCloudModeTheSegmentIsSentAndRevised() = call(CascadeSettings(accuratePass = true, cloudSttModel = "assemblyai/universal-3-5-pro")) { c ->
        assertEquals(1, c.cloudSent)
        assertEquals("the cloud text was the accurate pass", 1, c.stt.secondPass.lastTiming!!.cloudUsed)
        val revised = c.events.filterIsInstance<SttEvent.Revised>().single()
        assertEquals(1, server.requestCount)
        assertEquals("/api/v1/audio/transcriptions", server.takeRequest().url.encodedPath)
        assertEquals("Hello there.", revised.text)
        assertTrue(revised.material)
        assertEquals("the revision names the fast segment as its Final read", c.events.filterIsInstance<SttEvent.Final>().single().text, revised.fast)
    }
}
