package com.folio.hermes

import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * A scripted Hermes dashboard for gateway tests: `/api/auth/ws-ticket` (Bearer-checked) and the
 * `/api/ws` upgrade. Shapes follow the deployed server (tui_gateway/ws.py, server_requests.py).
 */
class FakeHermesServer : Closeable {
    val server = MockWebServer()
    val acceptedTokens: MutableSet<String> = ConcurrentHashMap.newKeySet<String>().apply { add("at-1") }
    val tickets = AtomicInteger()
    val upgrades = LinkedBlockingQueue<HttpUrl>()
    val sockets = LinkedBlockingQueue<ServerSide>()
    @Volatile var refuseUpgrade = false
    @Volatile var answerPings = true
    @Volatile var sendReady = true
    @Volatile var capabilitiesUnsupported = false

    /** False: the fake never answers the client's close frame (the gateway's own fallback must cut the socket). */
    @Volatile var answerClose = true

    /** Plain HTTP routes beyond the gateway's (path → handler): the voice tests add `/api/audio/…` ([FakeHermesVoice]). */
    val routes = ConcurrentHashMap<String, (RecordedRequest) -> MockResponse>()

    /** method -> handler(socket, id, params). Unhandled methods get no reply. */
    val responders = ConcurrentHashMap<String, (ServerSide, JsonElement, JsonObject) -> Unit>()

    private val _activity = MutableStateFlow(0L)

    /** Bumped after every client frame is recorded and handled (responders included): wait on it, never poll. */
    val activity: StateFlow<Long> = _activity.asStateFlow()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/api/auth/ws-ticket" -> {
                    val bearer = request.headers["Authorization"]?.removePrefix("Bearer ")
                    if (bearer != null && bearer in acceptedTokens) {
                        json(200, """{"ticket":"t-${tickets.incrementAndGet()}","ttl_seconds":30}""")
                    } else {
                        json(401, """{"error":"session_expired","detail":"Unauthorized","reason":"invalid_or_expired_session","login_url":"/login"}""")
                    }
                }
                "/api/ws" -> {
                    upgrades.add(request.url)
                    if (refuseUpgrade) MockResponse.Builder().code(403).build()
                    else MockResponse.Builder().webSocketUpgrade(listener()).build()
                }
                else -> routes[request.url.encodedPath]?.invoke(request) ?: json(404, """{"detail":"Not Found"}""")
            }
        }
        server.start()
    }

    fun config(): HermesConfig = HermesConfig(
        endpoint = testEndpoint(server.url("/").toString()),
        pingIntervalMs = 150,
        heartbeatTimeoutMs = 600,
        rpcTimeoutMs = 3_000,
        promptSubmitTimeoutMs = 3_000,
        connectTimeoutMs = 3_000,
        reconnectBaseDelayMs = 50,
        reconnectMaxDelayMs = 200,
        turnInactivityTimeoutMs = 5_000,
        interruptSettleMs = 1_000,
        busyRetryWindowMs = 1_000,
        busyRetryIntervalMs = 20,
    )

    fun nextSocket(timeoutMs: Long = 5_000): ServerSide =
        sockets.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: error("no socket opened")

    private fun listener() = object : WebSocketListener() {
        val side = ServerSide()
        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
            side.ws = webSocket
            sockets.add(side)
            if (sendReady) {
                webSocket.send(
                    """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"skin":{},"change_events":true,"heartbeat":true,"replay_epoch":"epoch-1"}}}""",
                )
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val obj = HermesJson.parseToJsonElement(text).jsonObject
            side.record(obj)
            val method = obj.string("method")
            val id = obj["id"]
            when {
                method == "gateway.ping" -> if (answerPings && id != null) side.reply(id, """{"ok":true}""")
                // A test's fence: the reply proves every frame the client sent before it has arrived (one socket, in order).
                method == FENCE && id != null -> side.reply(id, "{}")
                method == "client.capabilities" && id != null ->
                    if (capabilitiesUnsupported) side.error(id, -32601, "unknown method: client.capabilities")
                    else side.reply(id, """{"server_requests":["approval","clarify","sudo"],"declines_not_shown":true}""")
                method != null && id != null -> responders[method]?.invoke(side, id, obj.obj("params") ?: JsonObject(emptyMap()))
            }
            _activity.update { it + 1 }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            side.closing.complete(code)
            if (answerClose) webSocket.close(1000, null)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
            side.failed.complete(t)
        }
    }

    override fun close() = server.close()

    companion object {
        /** A method the fake always answers with `{}` (see [HermesGateway.call]); tests use it as a client→server fence. */
        const val FENCE = "test.fence"
    }

    class ServerSide {
        lateinit var ws: WebSocket
        val frames = CopyOnWriteArrayList<JsonObject>()

        /**
         * How the client left this socket. Shared by every gateway test; only the close tests read it.
         * [closing]: the close code the client sent (a clean close). [failed]: the socket dropped
         * without a close frame (the client cut it, or the read failed).
         */
        val closing = CompletableDeferred<Int>()
        val failed = CompletableDeferred<Throwable>()
        private val consumed = HashSet<Int>()
        private val arrived = MutableStateFlow(0)

        internal fun record(frame: JsonObject) {
            frames.add(frame)
            arrived.update { it + 1 }
        }

        fun send(text: String) = check(ws.send(text))
        fun reply(id: JsonElement, result: String) = send("""{"jsonrpc":"2.0","result":$result,"id":$id}""")
        fun error(id: JsonElement, code: Int, message: String, data: String = "null") =
            send("""{"jsonrpc":"2.0","error":{"code":$code,"message":"$message","data":$data},"id":$id}""")

        fun event(type: String, sessionId: String?, payload: String? = null) {
            val sid = sessionId?.let { "\"$it\"" } ?: "null"
            val p = payload?.let { ""","payload":$it""" } ?: ""
            send("""{"jsonrpc":"2.0","method":"event","params":{"type":"$type","session_id":$sid$p}}""")
        }

        @Synchronized
        private fun claim(predicate: (JsonObject) -> Boolean): JsonObject? =
            frames.withIndex().firstOrNull { (i, f) -> i !in consumed && predicate(f) }?.let { (i, f) ->
                consumed.add(i)
                f
            }

        /** The next unconsumed frame matching [predicate]; re-checked each time a frame arrives (on OkHttp's thread). */
        suspend fun next(timeoutMs: Long = 5_000, predicate: (JsonObject) -> Boolean): JsonObject {
            var hit: JsonObject? = null
            withTimeoutOrNull(timeoutMs) { arrived.first { claim(predicate)?.also { hit = it } != null } }
            return hit ?: throw AssertionError("no matching frame; got ${frames.map { it.string("method") ?: it["id"] }}")
        }

        suspend fun nextCall(method: String, timeoutMs: Long = 5_000) = next(timeoutMs) { it.string("method") == method }
        suspend fun nextResponse(id: String, timeoutMs: Long = 5_000) =
            next(timeoutMs) { it.string("method") == null && it.string("id") == id }
    }
}

/**
 * Stateful sessions on a [FakeHermesServer], shaped like the deployed server
 * (`tui_gateway/methods_session.py`, `methods_prompt.py`; read 2026-10-13):
 * - `session.create` stores nothing: the session is live but has no row until its first prompt;
 * - `prompt.submit` stores the row and the user message before the turn starts (`streaming`), or
 *   holds the text as `queued` while a turn runs;
 * - `session.resume` answers `{session_id, session_key, message_count, messages[{role,text}], running,
 *   status, inflight?, queued?}`; `omit_messages: true` leaves `messages` empty; an id with no row
 *   and no live session is 4007 "session not found".
 * [complete] ends the running turn: the assistant row is stored, then `message.complete` goes out.
 */
class FakeSessions(private val fake: FakeHermesServer) {
    class Session(val runtime: String, val stored: String) {
        val messages = CopyOnWriteArrayList<Pair<String, String>>()
        @Volatile var running = false
        @Volatile var persisted = false
        @Volatile var queued: String? = null
        @Volatile var live = true
    }

    val byStored = ConcurrentHashMap<String, Session>()
    private val counter = AtomicInteger()
    @Volatile var socket: FakeHermesServer.ServerSide? = null

    /** Resumes seen, with their `omit_messages` flag. */
    val resumes = CopyOnWriteArrayList<Pair<String, Boolean>>()

    init {
        fake.responders["session.create"] = { s, id, _ ->
            socket = s
            val n = counter.incrementAndGet()
            val session = Session("rt$n", "stored-$n")
            byStored[session.stored] = session
            s.reply(id, """{"session_id":"${session.runtime}","stored_session_id":"${session.stored}","info":{}}""")
        }
        fake.responders["prompt.submit"] = { s, id, p ->
            socket = s
            val session = byStored.values.firstOrNull { it.runtime == p.string("session_id") }
            val text = p.string("text").orEmpty()
            when {
                session == null -> s.error(id, 4001, "session not found")
                session.running -> {
                    session.queued = text
                    s.reply(id, """{"status":"queued"}""")
                }
                else -> {
                    session.persisted = true
                    session.messages += "user" to text
                    session.running = true
                    s.reply(id, """{"status":"streaming"}""")
                    s.event("message.start", session.runtime)
                }
            }
        }
        fake.responders["session.resume"] = { s, id, p ->
            socket = s
            val stored = p.string("session_id").orEmpty()
            val omit = p.bool("omit_messages") == true
            resumes += stored to omit
            val session = byStored[stored]?.takeIf { it.persisted || it.live }
            if (session == null) {
                s.error(id, 4007, "session not found")
            } else {
                val messages = if (omit) "[]" else session.messages.joinToString(",", "[", "]") { (role, text) -> """{"role":"$role","text":"$text"}""" }
                val inflight = if (session.running) session.messages.lastOrNull { it.first == "user" }?.let { ""","inflight":{"user":"${it.second}","assistant":"","streaming":true}""" }.orEmpty() else ""
                val queued = session.queued?.let { ""","queued":{"user":"$it"}""" }.orEmpty()
                s.reply(
                    id,
                    """{"session_id":"${session.runtime}","resumed":"$stored","session_key":"$stored","message_count":${session.messages.size},""" +
                        """"messages":$messages,"running":${session.running},"status":"${if (session.running) "streaming" else "idle"}"$inflight$queued}""",
                )
            }
        }
    }

    fun session(stored: String): Session = byStored.getValue(stored)

    /** The running turn of [stored] ends with [text] (stored, then announced). */
    fun complete(stored: String, text: String) {
        val session = session(stored)
        session.messages += "assistant" to text
        session.running = false
        socket!!.event("message.complete", session.runtime, """{"text":"$text","status":"complete"}""")
    }
}

/** Token source for gateway tests. */
class FakeTokens(var token: String = "at-1", var next: String = "at-2", var signedIn: Boolean = true) : AccessTokenProvider {
    val calls = AtomicInteger()
    val refreshes = CopyOnWriteArrayList<String>()
    override suspend fun accessToken(): String {
        calls.incrementAndGet()
        if (!signedIn) throw HermesAuthException.SignInRequired()
        return token
    }

    override suspend fun refreshAfterUnauthorized(rejected: String): String {
        refreshes += rejected
        token = next
        return token
    }
}

/**
 * A round trip through [FakeHermesServer.FENCE]: when it returns, every frame this client sent
 * before it has reached the fake, and every frame the fake sent before its reply has been read.
 */
suspend fun HermesGateway.fence() {
    call(FakeHermesServer.FENCE, JsonObject(emptyMap()))
}
