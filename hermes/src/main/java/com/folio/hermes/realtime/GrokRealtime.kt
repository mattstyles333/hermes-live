package com.folio.hermes.realtime

import com.folio.hermes.telemetry.TelemetrySink
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** The socket's state, for the call screen and the session. */
sealed interface LinkState {
    data object Idle : LinkState
    data object Connecting : LinkState

    /** [resumed]: reconnected with `conversation_id` (the history replays). */
    data class Open(val resumed: Boolean) : LinkState

    data class Reconnecting(val attempt: Int) : LinkState

    /** Dropped with the phone offline: [GrokRealtime.reconnect] once the network is back. */
    data object Offline : LinkState

    /** Gave up: [code] `no_key`, `unauthorized`, `http_<n>`, `unreachable`. */
    data class Failed(val code: String) : LinkState
    data object Closed : LinkState
}

/** What a session sends in `session.update`: the instructions and the tools. */
data class SessionSpec(val instructions: String, val tools: JsonArray, val turnDetection: Boolean = true)

/**
 * One Grok Voice realtime connection (OkHttp WebSocket, `Authorization: Bearer <key>`): the
 * `session.update` once the server says `session.created`, audio in, server events out (one
 * consumer, in order; the consumer answers `ping` with `pong`, in order), and reconnects. A drop while online reconnects up to
 * [GrokConfig.reconnectAttempts] times, with `conversation_id` when the server gave one and
 * resumption is on (the history replays; the session is opted in again). A drop while offline
 * waits in [LinkState.Offline] for [reconnect].
 *
 * Off state: nothing connects without a key ([key] null or blank: [LinkState.Failed] `no_key`, no
 * request). Telemetry: names, codes and timings only; never audio, transcripts, tool arguments,
 * server messages or the key.
 */
class GrokRealtime(
    client: OkHttpClient,
    private val key: () -> String?,
    private val config: () -> GrokConfig,
    private val spec: () -> SessionSpec,
    private val scope: CoroutineScope,
    private val online: () -> Boolean = { true },
    private val telemetry: TelemetrySink = TelemetrySink.Default,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val http: OkHttpClient = client.newBuilder()
        .connectTimeout(config().connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(config().pingIntervalMs, TimeUnit.MILLISECONDS)
        .build()
    private val channel = Channel<ServerEvent>(Channel.UNLIMITED)
    private val _link = MutableStateFlow<LinkState>(LinkState.Idle)
    private val lock = Any()
    private var socket: WebSocket? = null
    private var generation = 0
    private var attempts = 0
    private var closing = false
    private var retry: Job? = null

    @Volatile var conversationId: String? = null
        private set

    /** The session config went out on this socket: audio may follow (it is dropped before). */
    @Volatile private var configured = false

    /** Server events, in order (one consumer). */
    val events: Flow<ServerEvent> = channel.receiveAsFlow()

    val link: StateFlow<LinkState> = _link.asStateFlow()

    /** Open the socket. False (and nothing sent) without a key. */
    fun connect(): Boolean {
        synchronized(lock) {
            closing = false
            attempts = 0
        }
        return open(resume = false)
    }

    /** The network is back (or the user asked): reconnect, resuming when possible. */
    fun reconnect() {
        synchronized(lock) {
            if (closing) return
            attempts = 0
        }
        open(resume = true)
    }

    /** Send one client event; false if the socket is not open. */
    fun send(event: JsonObject): Boolean {
        val ws = synchronized(lock) { socket.takeIf { _link.value is LinkState.Open } } ?: return false
        return ws.send(RealtimeProtocol.encode(event))
    }

    /** Microphone audio, PCM s16le mono at [GrokConfig.sampleRate]. */
    fun appendAudio(pcm: ByteArray): Boolean = configured && pcm.isNotEmpty() && send(RealtimeProtocol.appendAudio(pcm))

    /** Close for good (hang-up). */
    fun close() {
        val ws = synchronized(lock) {
            closing = true
            generation++
            retry?.cancel()
            socket.also { socket = null }
        }
        ws?.close(NORMAL_CLOSURE, null)
        _link.value = LinkState.Closed
    }

    private fun open(resume: Boolean): Boolean {
        val k = key()?.trim()
        if (k.isNullOrEmpty()) {
            _link.value = LinkState.Failed(NO_KEY)
            report("grok.connect", mapOf("ok" to false, "code" to NO_KEY), warn = true)
            return false
        }
        val c = config()
        val resumeId = conversationId.takeIf { resume && c.resumption }
        val url = c.url.replaceFirst("wss://", "https://").replaceFirst("ws://", "http://").toHttpUrl().newBuilder()
            .addQueryParameter("model", c.model)
            .apply { resumeId?.let { addQueryParameter("conversation_id", it) } }
            .build()
        val request = Request.Builder().url(url).header("Authorization", "Bearer $k").build()
        val gen = synchronized(lock) {
            socket?.cancel()
            configured = false
            ++generation
        }
        if (_link.value !is LinkState.Reconnecting) _link.value = LinkState.Connecting
        val started = clock()
        val ws = http.newWebSocket(request, Listener(gen, resumeId != null, started))
        synchronized(lock) { if (gen == generation) socket = ws else ws.cancel() }
        return true
    }

    private inner class Listener(private val gen: Int, private val resumed: Boolean, private val startedAt: Long) : WebSocketListener() {
        private var configured = false

        private fun current(): Boolean = synchronized(lock) { gen == generation && !closing }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!current()) return
            synchronized(lock) { attempts = 0 }
            _link.value = LinkState.Open(resumed)
            report("grok.connect", mapOf("ok" to true, "ms" to clock() - startedAt, "resumed" to resumed))
            // The server greets with `session.created`; configure then (or now, if it already has).
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!current()) return
            val event = RealtimeProtocol.parse(text) ?: return
            when (event) {
                ServerEvent.SessionCreated -> configure(webSocket)
                is ServerEvent.ConversationCreated -> conversationId = event.id
                is ServerEvent.Error -> report("grok.error", mapOf("type" to event.type, "code" to event.code), warn = true)
                else -> if (!configured) configure(webSocket)
            }
            channel.trySend(event)
        }

        private fun configure(webSocket: WebSocket) {
            if (configured) return
            configured = true
            val s = spec()
            webSocket.send(RealtimeProtocol.encode(RealtimeProtocol.sessionUpdate(config(), s.instructions, s.tools, s.turnDetection)))
            this@GrokRealtime.configured = true
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(NORMAL_CLOSURE, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!current()) return
            report("grok.closed", mapOf("code" to code))
            dropped("closed_$code")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!current()) return
            val status = response?.code
            report("grok.failure", mapOf("http_status" to status, "type" to t.javaClass.simpleName), warn = true)
            when (status) {
                401, 403 -> fail(UNAUTHORIZED)
                null -> dropped("io")
                in 400..499 -> fail("http_$status")
                else -> dropped("http_$status")
            }
        }
    }

    private fun fail(code: String) {
        synchronized(lock) { socket = null }
        _link.value = LinkState.Failed(code)
    }

    /** An unexpected drop: offline waits for [reconnect]; online retries, then gives up. */
    private fun dropped(why: String) {
        val attempt = synchronized(lock) {
            socket = null
            if (closing) return
            ++attempts
        }
        if (!online()) {
            _link.value = LinkState.Offline
            report("grok.offline", mapOf("why" to why))
            return
        }
        val c = config()
        if (attempt > c.reconnectAttempts) {
            _link.value = LinkState.Failed(UNREACHABLE)
            return
        }
        _link.value = LinkState.Reconnecting(attempt)
        report("grok.reconnect", mapOf("attempt" to attempt, "why" to why, "resume" to (c.resumption && conversationId != null)))
        synchronized(lock) {
            retry?.cancel()
            retry = scope.launch {
                delay(c.reconnectDelayMs * attempt)
                if (!synchronized(lock) { closing }) open(resume = true)
            }
        }
    }

    private fun report(name: String, attrs: Map<String, Any?>, warn: Boolean = false) {
        runCatching { telemetry.event(name, attrs, warn) }
    }

    companion object {
        const val NORMAL_CLOSURE = 1000
        const val NO_KEY = "no_key"
        const val UNAUTHORIZED = "unauthorized"
        const val UNREACHABLE = "unreachable"
    }
}
