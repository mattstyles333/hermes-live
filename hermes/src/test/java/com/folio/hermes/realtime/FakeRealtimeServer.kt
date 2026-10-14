package com.folio.hermes.realtime

import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals

/**
 * A fake Grok Voice realtime server on MockWebServer's WebSocket upgrade: it records every client
 * event in order and sends scripted server events (the shapes from docs.x.ai, 2026-10-14). Each
 * [accept] serves one connection; [take] waits (bounded) for the next client event of a type, and
 * [fence] proves nothing else was sent before a ping/pong round trip (no sleeps, no polling).
 */
class FakeRealtimeServer : AutoCloseable {
    val server = MockWebServer()
    private val received = LinkedBlockingQueue<JsonObject>()
    private val sockets = LinkedBlockingQueue<WebSocket>()
    private val all = java.util.concurrent.CopyOnWriteArrayList<WebSocket>()
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile var socket: WebSocket? = null
        private set

    /** Every client event, in order (kept after [take]). */
    val log = java.util.Collections.synchronizedList(mutableListOf<JsonObject>())

    init {
        server.start()
    }

    val url: String get() = server.url("/v1/realtime").toString().replaceFirst("http://", "ws://").substringBefore('?')

    /** Serve the next connection: `session.created`, then `conversation.created` with [conversationId]. */
    fun accept(conversationId: String = "conv_1") {
        server.enqueue(
            MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    socket = webSocket
                    webSocket.send("""{"type":"session.created","session":{"id":"sess_1"}}""")
                    webSocket.send("""{"type":"conversation.created","conversation":{"id":"$conversationId"}}""")
                    sockets.put(webSocket)
                    all += webSocket
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val o = json.parseToJsonElement(text).jsonObject
                    log += o
                    received.put(o)
                }
            }).build(),
        )
    }

    /** A connection refused with [code] (e.g. 401 for a bad key). */
    fun refuse(code: Int) {
        server.enqueue(MockResponse.Builder().code(code).body("{}").build())
    }

    fun awaitConnection(): WebSocket = sockets.poll(WAIT_S, TimeUnit.SECONDS) ?: throw AssertionError("no connection")

    fun request(): RecordedRequest = server.takeRequest(WAIT_S, TimeUnit.SECONDS)!!

    fun send(event: String) {
        socket!!.send(event)
    }

    /** The next client event of [type]; other events before it are skipped (they stay in [log]). */
    fun take(type: String): JsonObject {
        while (true) {
            val o = received.poll(WAIT_S, TimeUnit.SECONDS) ?: throw AssertionError("no '$type' from the client; got ${log.map { it.type }}")
            if (o.type == type) return o
        }
    }

    /** Wait until the client has handled everything sent before this ping (it answers in order), then return the events since [mark]. */
    fun fence(mark: Int): List<JsonObject> {
        val ts = System.nanoTime()
        send("""{"type":"ping","ping_timestamp":$ts}""")
        while (true) {
            val o = received.poll(WAIT_S, TimeUnit.SECONDS) ?: throw AssertionError("no pong")
            if (o.type == "pong" && o["ping_timestamp"]?.jsonPrimitive?.content == ts.toString()) break
        }
        return synchronized(log) { log.drop(mark).filter { it.type != "pong" } }
    }

    fun mark(): Int = log.size

    /** Drain the queue so [take] only sees new events. */
    fun clearPending() = received.clear()

    // ── scripted server events ──────────────────────────────────────────────────────────────

    fun responseCreated(id: String) = send("""{"type":"response.created","response":{"id":"$id","status":"in_progress"}}""")

    fun audioDelta(responseId: String, itemId: String, ms: Int) {
        val pcm = ByteArray(ms * 48)
        send("""{"type":"response.output_audio.delta","response_id":"$responseId","item_id":"$itemId","output_index":0,"content_index":0,"delta":"${Base64.getEncoder().encodeToString(pcm)}"}""")
    }

    fun functionCall(responseId: String, callId: String, name: String, arguments: String) {
        val args = Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(arguments))
        send("""{"type":"response.function_call_arguments.done","response_id":"$responseId","item_id":"item_$callId","output_index":0,"call_id":"$callId","name":"$name","arguments":$args}""")
    }

    fun responseDone(id: String, status: String = "completed") = send("""{"type":"response.done","response":{"id":"$id","status":"$status","output":[]}}""")

    fun speechStarted(itemId: String = "user_1") = send("""{"type":"input_audio_buffer.speech_started","item_id":"$itemId","audio_start_ms":0}""")

    fun speechStopped(itemId: String = "user_1") = send("""{"type":"input_audio_buffer.speech_stopped","item_id":"$itemId","audio_end_ms":900}""")

    override fun close() {
        all.forEach { runCatching { it.cancel() } }
        server.close()
    }

    companion object {
        const val WAIT_S = 10L
        val JsonObject.type: String get() = this["type"]!!.jsonPrimitive.content

        fun assertType(o: JsonObject, type: String) = assertEquals(type, o.type)
    }
}

/** The phone's speaker as a test controls it: [write] makes it busy; [drain] plays it out. */
class FakePlayback : RealtimePlayback {
    private val _idle = MutableStateFlow(true)
    override val idle: StateFlow<Boolean> = _idle
    val written = java.util.Collections.synchronizedList(mutableListOf<Int>())

    @Volatile var stops = 0
        private set

    @Volatile var played = 0L

    override fun write(pcm: ByteArray) {
        written += pcm.size
        _idle.value = false
    }

    override fun stop() {
        stops++
        _idle.value = true
    }

    override fun playedMs(): Long = played

    fun drain() {
        _idle.value = true
    }
}
