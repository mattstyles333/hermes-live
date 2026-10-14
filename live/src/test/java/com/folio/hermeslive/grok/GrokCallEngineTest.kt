package com.folio.hermeslive.grok

import com.folio.hermes.realtime.GrokConfig
import com.folio.hermeslive.audio.AudioSource
import com.folio.hermeslive.call.CallPhase
import com.folio.hermeslive.call.CallRequest
import com.folio.hermeslive.call.Earcon
import com.folio.hermeslive.call.FakeAudio
import com.folio.hermeslive.call.FakeEffects
import com.folio.hermeslive.cascade.TestRuntime
import com.folio.hermeslive.data.LiveSettings
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Grok engine behind the [com.folio.hermeslive.call.VoiceCall] port, on fakes (audio routes,
 * effects, Bill's runtime) and a small fake realtime server: no key opens no socket; a call
 * sends the session config from Settings and the mic resampled to 24 kHz; offline offers Bill.
 */
class GrokCallEngineTest {
    private val executor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
    private val server = MockWebServer().apply { start() }
    private val received = LinkedBlockingQueue<JsonObject>()
    private val sockets = LinkedBlockingQueue<WebSocket>()
    private val audio = FakeAudio()
    private val effects = FakeEffects()
    private val rt = TestRuntime(scope)
    private val offCalls = mutableListOf<Int>()
    private val network = object : GrokNetwork {
        @Volatile var up = true
        override fun online(): Boolean = up
        override val changes = MutableSharedFlow<Boolean>()
    }
    private var key: String? = "test-key"
    private val lives = mutableListOf<Boolean>()

    private fun accept() {
        server.enqueue(
            MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("""{"type":"session.created"}""")
                    webSocket.send("""{"type":"conversation.created","conversation":{"id":"conv_9"}}""")
                    sockets.put(webSocket)
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    received.put(Json.parseToJsonElement(text).jsonObject)
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }
            }).build(),
        )
    }

    private fun take(type: String): JsonObject {
        while (true) {
            val o = received.poll(10, TimeUnit.SECONDS) ?: throw AssertionError("no $type")
            if (o["type"]!!.jsonPrimitive.content == type) return o
        }
    }

    private fun engine(settings: LiveSettings = LiveSettings(grokVoice = "rex", grokModel = "grok-voice-3.0"), billMemo: Boolean = false) = GrokCallEngine(
        scope = scope,
        settings = { settings },
        runtime = { rt.runtime },
        key = { key },
        client = OkHttpClient(),
        audioFactory = { GrokAudioFactory { GrokAudioIo(PacedTone(), QuietPlayback()) } },
        audio = audio,
        effects = effects,
        offCall = { running -> offCalls += running; true },
        network = network,
        billMemo = { billMemo },
        onLive = { lives += it },
        baseConfig = GrokConfig(url = server.url("/v1/realtime").toString().replaceFirst("http", "ws"), reconnectAttempts = 0),
    )

    private fun <T> onEngine(block: () -> T): T = runBlocking { kotlinx.coroutines.withContext(scope.coroutineContext) { block() } }

    private fun await(state: StateFlow<com.folio.hermeslive.call.CallUiState>, test: (com.folio.hermeslive.call.CallUiState) -> Boolean) =
        runBlocking { withTimeout(10_000) { state.first(test) } }

    @After fun tearDown() {
        scope.cancel()
        sockets.forEach { runCatching { it.cancel() } }
        server.close()
        executor.shutdownNow()
    }

    @Test fun withoutAnXaiKeyNoSocketOpens() {
        key = null
        val e = engine()
        onEngine { e.start(CallRequest(entry = "test")) }
        await(e.state) { it.phase == CallPhase.Failed || it.phase == CallPhase.Ended }
        assertTrue(e.state.value.error!!.contains("xAI key"))
        assertEquals("no request at all", 0, server.requestCount)
        assertTrue("no tasks running: no off-call service", offCalls.isEmpty())
    }

    @Test fun aCallSendsTheSettingsAndTheMicAt24Khz() {
        accept()
        val e = engine()
        onEngine { e.start(CallRequest(entry = "test")) }
        val request = server.takeRequest(10, TimeUnit.SECONDS)!!
        assertEquals("Bearer test-key", request.headers["Authorization"])
        assertEquals("grok-voice-3.0", request.url.queryParameter("model"))
        val session = take("session.update")["session"]!!.jsonObject
        assertEquals("rex", session["voice"]!!.jsonPrimitive.content)
        await(e.state) { it.phase == CallPhase.Live }
        assertTrue(Earcon.Connected in effects.earcons)
        assertEquals(listOf(true), lives)
        // 20 ms of 16 kHz mic (320 samples) is 480 samples at 24 kHz (479 for the very first chunk: no carry yet).
        val pcm = Base64.getDecoder().decode(take("input_audio_buffer.append")["audio"]!!.jsonPrimitive.content)
        assertTrue(pcm.size == 480 * 2 || pcm.size == 479 * 2)
        val next = Base64.getDecoder().decode(take("input_audio_buffer.append")["audio"]!!.jsonPrimitive.content)
        assertEquals(480 * 2, next.size)
        rt.running.value = 2
        onEngine { e.end() }
        assertEquals(CallPhase.Ended, e.state.value.phase)
        assertEquals("the off-call service keeps the tasks", listOf(2), offCalls)
        assertEquals(listOf(true, false), lives)
        assertTrue(e.state.value.summary!!.reason.contains("Grok Voice"))
    }

    @Test fun offlineSaysSoAndOffersBillsMemoMode() {
        accept()
        val e = engine(billMemo = true)
        onEngine { e.start(CallRequest(entry = "test")) }
        val ws = sockets.poll(10, TimeUnit.SECONDS)!!
        await(e.state) { it.phase == CallPhase.Live }
        network.up = false
        ws.close(1011, "gone")
        await(e.state) { it.offerBillMemo }
        assertTrue(e.state.value.notice!!.contains("offline"))
        assertFalse(e.state.value.phase == CallPhase.Ended)
        onEngine { e.end() }
    }

    /** The mic at its real pace: 20 ms per read (a device fake, not a test wait). */
    private class PacedTone : AudioSource {
        override var onRouteChanged: (() -> Unit)? = null
        override fun open() = Unit
        override fun read(buffer: ShortArray): Int {
            Thread.sleep(20)
            buffer.fill(1000)
            return buffer.size
        }
        override fun close() = Unit
    }

    private class QuietPlayback : GrokPlayback {
        override val idle = MutableStateFlow(true)
        override fun write(pcm: ByteArray) = Unit
        override fun stop() = Unit
        override fun playedMs(): Long = 0
        override fun level(): Float = 0f
        override fun release() = Unit
    }
}
