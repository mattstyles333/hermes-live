package com.folio.hermes

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * JSON-RPC 2.0 client for the Hermes gateway at `/api/ws`.
 *
 * Connect: `POST /api/auth/ws-ticket` with the bearer (a 30 s single-use ticket), then open
 * `wss://…/api/ws?ticket=…`. The first frame is the `gateway.ready` event; the client then sends
 * `client.capabilities {server_requests:true}` so the server sends approval and clarify
 * requests here. The client pings (`gateway.ping`) every [HermesConfig.pingIntervalMs]; no inbound
 * frame for [HermesConfig.heartbeatTimeoutMs] drops the socket. Drops reconnect with exponential
 * backoff and a fresh ticket. Sessions are bound to the socket that used them, so after a
 * reconnect call [resumeSession] ([HermesConversation] does this) before relying on their events.
 *
 * Server requests: `approval` and `clarify` become [GatewayEvent]s for the app to show (never
 * answered automatically). Requests only a desktop window can answer are declined at once with
 * 4404, any other kind (sudo, secret, vault prompts) with -32601, so the agent never waits on them.
 *
 * Off state: nothing dials until [connect] or a call. With no session the connection stops at
 * [GatewayConnection.Stopped] with `signInRequired = true` and no ticket request is sent.
 */
class HermesGateway(
    private val config: HermesConfig,
    private val tokens: AccessTokenProvider,
    client: OkHttpClient = OkHttpClient(),
    private val scope: CoroutineScope,
) {
    private val client: OkHttpClient = client
    private val _connection = MutableStateFlow<GatewayConnection>(GatewayConnection.Idle)
    private val _events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 1024)
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, PendingCall>()
    private val generation = AtomicLong(0)
    private val running = ConcurrentHashMap<String, Boolean>()
    private val lock = Any()
    private var loop: Job? = null

    @Volatile
    private var live: Conn? = null

    @Volatile
    private var busyModeCache: Pair<Long, BusyMode?>? = null

    /** Connection state. */
    val connection: StateFlow<GatewayConnection> = _connection.asStateFlow()

    /**
     * Events and server requests, as they arrive. Subscribe before the call whose events you want.
     * Collect promptly: the buffer holds 1024 events, and a stalled collector makes later events
     * drop for every subscriber.
     */
    val events: SharedFlow<GatewayEvent> = _events.asSharedFlow()

    /**
     * True while [sessionId] runs a turn, as far as this client saw: set by `message.start`, an
     * accepted `prompt.submit` or a resume that says running; cleared by `message.complete`.
     */
    fun isRunning(sessionId: String): Boolean = running[sessionId] == true

    /** Start (or restart after [GatewayConnection.Stopped]) the connection loop. Idempotent. */
    fun connect() {
        synchronized(lock) {
            if (loop?.isActive == true) return
            loop = scope.launch { runLoop() }
        }
    }

    /** Close the socket and stop reconnecting. Waiting calls fail. */
    fun disconnect() {
        synchronized(lock) {
            loop?.cancel()
            loop = null
        }
        live?.ws?.close(1000, "client closing")
        live = null
        failPending("disconnected")
        _connection.value = GatewayConnection.Idle
    }

    /**
     * Wait until the gateway is ready, connecting if needed. Throws
     * [HermesAuthException.SignInRequired] when there is no session, or
     * [GatewayUnavailableException] when it does not get ready within [timeoutMs].
     */
    suspend fun awaitReady(timeoutMs: Long = config.connectTimeoutMs): GatewayConnection.Ready {
        (_connection.value as? GatewayConnection.Ready)?.let { if (live != null) return it }
        connect()
        val state = withTimeoutOrNull(timeoutMs) {
            _connection.first { it is GatewayConnection.Ready || it is GatewayConnection.Stopped }
        } ?: throw GatewayUnavailableException("Hermes did not answer in time.")
        return when (state) {
            is GatewayConnection.Ready -> state
            is GatewayConnection.Stopped ->
                if (state.signInRequired) throw HermesAuthException.SignInRequired(state.reason)
                else throw GatewayUnavailableException(state.reason)
            else -> throw GatewayUnavailableException("Hermes is not connected.")
        }
    }

    /** Raw JSON-RPC call. Returns `result`; throws [HermesRpcException] for an `error` reply. */
    suspend fun call(method: String, params: JsonObject, timeoutMs: Long = config.rpcTimeoutMs): JsonElement {
        awaitReady()
        val conn = live ?: throw GatewayUnavailableException("Hermes is not connected.")
        return rawCall(conn, method, params, timeoutMs)
    }

    /** `session.create`. Keep [CreatedSession.storedSessionId] to resume later. */
    suspend fun createSession(options: SessionOptions = SessionOptions()): CreatedSession {
        val params = buildJsonObject {
            options.title?.let { put("title", it) }
            options.source?.let { put("source", it) }
            options.profile?.let { put("profile", it) }
            options.hidden?.let { put("hidden", it) }
        }
        val result = call("session.create", params).jsonObject
        return CreatedSession(
            sessionId = result.string("session_id") ?: throw protocol("session.create", "no session_id"),
            storedSessionId = result.string("stored_session_id") ?: result.string("session_id")!!,
            model = result.obj("info")?.string("model"),
        )
    }

    /**
     * `session.resume {session_id: stored, omit_messages: true}`: bind a stored session to this
     * socket (returns the live runtime id, possibly new). Unanswered approval/clarify requests in
     * the reply are re-delivered on [events].
     */
    suspend fun resumeSession(storedSessionId: String, profile: String? = null): ResumedSession {
        val params = buildJsonObject {
            put("session_id", storedSessionId)
            put("omit_messages", true)
            profile?.let { put("profile", it) }
        }
        val result = call("session.resume", params).jsonObject
        val open = (result["open_requests"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        live?.let { conn -> open.forEach { req -> redeliver(conn, req) } }
        val sessionId = result.string("session_id") ?: throw protocol("session.resume", "no session_id")
        return ResumedSession(
            sessionId = sessionId,
            storedSessionId = result.string("session_key") ?: result.string("resumed") ?: storedSessionId,
            running = result.bool("running") == true,
            status = result.string("status") ?: "idle",
            messageCount = result.long("message_count")?.toInt() ?: 0,
            openRequests = open.size,
        )
    }

    /**
     * `prompt.submit`. [surface] [PromptSurface.VoiceLive] marks a GPT-Live delegation and
     * [voiceContext] (the recent spoken exchange, ≤6000 chars) rides the model input only.
     * [queued] sends `queued: true`: on a busy session the text runs after the current turn and
     * never becomes a live correction, whatever the server's busy mode (omitted when false).
     */
    suspend fun submitPrompt(
        sessionId: String,
        text: String,
        surface: PromptSurface? = null,
        voiceContext: String? = null,
        queued: Boolean = false,
    ): PromptSubmitResult {
        val params = buildJsonObject {
            put("session_id", sessionId)
            put("text", text)
            if (surface != null) {
                put("surface", surface.wire)
                if (!voiceContext.isNullOrEmpty()) put("voice_context", voiceContext.take(6_000))
            }
            if (queued) put("queued", true)
        }
        val result = call("prompt.submit", params, config.promptSubmitTimeoutMs).jsonObject
        return PromptSubmitResult(GatewayFrames.submitStatus(result), result)
    }

    /**
     * The server's `display.busy_input_mode` (`interrupt`, `queue` or `steer`), read with
     * `config.get {key: "busy"}`. Read-only: this client never changes server config. Null when
     * the server does not say (older server, error, or a reply shape this client does not know);
     * callers then treat a redirect as interrupt-then-submit. Cached per connection.
     */
    suspend fun busyMode(): BusyMode? {
        val ready = awaitReady()
        busyModeCache?.let { (gen, mode) -> if (gen == ready.generation) return mode }
        val mode = try {
            GatewayFrames.busyMode(call("config.get", buildJsonObject { put("key", "busy") }))
        } catch (e: HermesRpcException) {
            null
        }
        busyModeCache = ready.generation to mode
        return mode
    }

    /** `session.interrupt`. Returns the server's status (`interrupted`, or `not_interrupted`). */
    suspend fun interrupt(sessionId: String): String {
        val result = call("session.interrupt", buildJsonObject { put("session_id", sessionId) }).jsonObject
        return result.string("status") ?: "interrupted"
    }

    /** Answer an approval with the user's [choice]. [all] resolves every queued approval of the session. */
    suspend fun answerApproval(request: ApprovalRequest, choice: ApprovalChoice, all: Boolean = false) {
        respond(request.id, buildJsonObject {
            put("choice", choice.wire)
            put("all", all)
        })
    }

    /** Answer a clarify request. Keys are question ids; null means skipped. */
    suspend fun answerClarify(request: ClarifyRequest, answers: Map<String, String?>) {
        respond(request.id, buildJsonObject {
            put("answers", JsonObject(answers.mapValues { (_, v) -> v?.let(::JsonPrimitive) ?: JsonNull }))
        })
    }

    /** Dismiss a clarify request without answers (cancel-all). */
    suspend fun cancelClarify(request: ClarifyRequest) = respond(request.id, JsonObject(emptyMap()))

    private suspend fun respond(id: String, result: JsonObject) {
        awaitReady()
        val conn = live ?: throw GatewayUnavailableException("Hermes is not connected.")
        if (!conn.ws.send(GatewayFrames.result(JsonPrimitive(id), result))) {
            throw GatewayUnavailableException("Hermes connection closed before the answer was sent.")
        }
    }

    // ── connection loop ───────────────────────────────────────────────────────────────────────

    private class PendingCall(
        val method: String,
        val params: JsonObject,
        val reply: CompletableDeferred<JsonElement>,
    )

    /**
     * Running state from replies, applied on the reader thread so it is ordered with the events
     * that follow the reply on the wire (a later `message.complete` must win).
     */
    private fun trackRunning(call: PendingCall, result: JsonElement?) {
        val obj = result as? JsonObject ?: return
        when (call.method) {
            "session.resume" -> obj.string("session_id")?.let { running[it] = obj.bool("running") == true }
            "prompt.submit" -> call.params.string("session_id")?.let { sid ->
                val status = GatewayFrames.submitStatus(obj)
                if (status == SubmitStatus.Streaming || status == SubmitStatus.Queued) running[sid] = true
            }
        }
    }

    private class Conn {
        lateinit var ws: WebSocket
        val ready = CompletableDeferred<String>()
        val closed = CompletableDeferred<Closed>()
        val lastInbound = AtomicLong(System.nanoTime())
    }

    private data class Closed(val reason: String, val handshakeCode: Int?)

    private sealed interface Outcome {
        data class Dropped(val reason: String) : Outcome
        data class Refused(val reason: String) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    private suspend fun runLoop() {
        var attempt = 0
        var refusals = 0
        while (currentCoroutineContext().isActive) {
            attempt++
            _connection.value = GatewayConnection.Connecting(attempt)
            val outcome = try {
                openOnce()
            } catch (e: CancellationException) {
                throw e
            } catch (e: HermesAuthException) {
                val needsSignIn = e is HermesAuthException.SignInRequired || e is HermesAuthException.InvalidCredentials
                if (needsSignIn || e is HermesAuthException.RateLimited) {
                    _connection.value = GatewayConnection.Stopped(e.message.orEmpty(), signInRequired = needsSignIn)
                    return
                }
                Outcome.Failed(e.message.orEmpty())
            } catch (e: java.io.IOException) {
                Outcome.Failed(e.message ?: e.javaClass.simpleName)
            }
            val reason = when (outcome) {
                is Outcome.Dropped -> {
                    attempt = 1
                    refusals = 0
                    outcome.reason
                }
                is Outcome.Refused -> {
                    refusals++
                    if (refusals >= config.maxHandshakeRefusals) {
                        _connection.value = GatewayConnection.Stopped(
                            "Hermes refused the connection (${outcome.reason}).", signInRequired = false,
                        )
                        return
                    }
                    outcome.reason
                }
                is Outcome.Failed -> outcome.reason
            }
            val base = config.reconnectBaseDelayMs shl (attempt - 1).coerceIn(0, 16)
            val wait = base.coerceAtMost(config.reconnectMaxDelayMs).let { it + Random.nextLong(0, it / 5 + 1) }
            _connection.value = GatewayConnection.Waiting(attempt, wait, reason)
            delay(wait)
        }
    }

    /** One connection: ticket, socket, ready, capabilities, then wait for the drop. */
    private suspend fun openOnce(): Outcome {
        var token = tokens.accessToken()
        var ticket = fetchTicket(token)
        if (ticket == null) {
            token = tokens.refreshAfterUnauthorized(token)
            ticket = fetchTicket(token) ?: throw HermesAuthException.SignInRequired("Hermes refused the session.")
        }
        val request = Request.Builder().url(config.endpoint.gatewayUrl(ticket)).build()
        val conn = Conn()
        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) = handle(conn, webSocket, text)
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                conn.closed.complete(Closed("closed ($code)", null))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val handshake = response?.code?.takeIf { !conn.ready.isCompleted }
                response?.close()
                conn.closed.complete(Closed(t.message ?: t.javaClass.simpleName, handshake))
            }
        })
        conn.ws = ws
        try {
            val first = withTimeoutOrNull(config.connectTimeoutMs) {
                select<Any> {
                    conn.ready.onAwait { it }
                    conn.closed.onAwait { it }
                }
            } ?: return Outcome.Failed("no gateway.ready").also { ws.cancel() }
            if (first is Closed) {
                return if (first.handshakeCode == 401 || first.handshakeCode == 403) {
                    Outcome.Refused("HTTP ${first.handshakeCode}")
                } else {
                    Outcome.Failed(first.reason)
                }
            }
            val epoch = first as String
            try {
                rawCall(conn, "client.capabilities", buildJsonObject { put("server_requests", true) }, config.rpcTimeoutMs)
            } catch (e: HermesRpcException) {
                // An older server without the method still works; it just cannot send approvals here.
                if (e.code != GatewayFrames.METHOD_NOT_FOUND) throw e
            }
            live = conn
            _connection.value = GatewayConnection.Ready(generation.incrementAndGet(), epoch)
            val heartbeat = scope.launch { heartbeat(conn) }
            val closed = try {
                conn.closed.await()
            } finally {
                heartbeat.cancel()
            }
            return Outcome.Dropped(closed.reason)
        } catch (e: HermesRpcException) {
            ws.cancel()
            return Outcome.Failed(e.message.orEmpty())
        } finally {
            if (live === conn) live = null
            failPending("connection lost")
            if (!conn.closed.isCompleted) ws.cancel()
        }
    }

    private suspend fun heartbeat(conn: Conn) {
        val timeoutNs = config.heartbeatTimeoutMs * 1_000_000
        while (currentCoroutineContext().isActive) {
            delay(config.pingIntervalMs)
            if (System.nanoTime() - conn.lastInbound.get() > timeoutNs) {
                conn.ws.cancel()
                conn.closed.complete(Closed("heartbeat timeout", null))
                return
            }
            val id = nextId.getAndIncrement()
            conn.ws.send(GatewayFrames.request(id, "gateway.ping", JsonObject(emptyMap())))
        }
    }

    /** `POST /api/auth/ws-ticket` with the bearer. Null on 401 (caller refreshes once). */
    private suspend fun fetchTicket(token: String): String? {
        val request = Request.Builder()
            .url(config.endpoint.resolve("/api/auth/ws-ticket"))
            .header("Authorization", "Bearer $token")
            .post("{}".jsonBody())
            .build()
        val response = client.newCall(request).await()
        val code = response.code
        val text = response.readText(16_000)
        if (code == 401) return null
        if (code !in 200..299) throw GatewayUnavailableException("ws-ticket failed (HTTP $code)")
        val body = runCatching { HermesJson.parseToJsonElement(text).jsonObject }.getOrNull()
        return body?.string("ticket") ?: throw GatewayUnavailableException("ws-ticket answered without a ticket")
    }

    private suspend fun rawCall(conn: Conn, method: String, params: JsonObject, timeoutMs: Long): JsonElement {
        val id = nextId.getAndIncrement()
        val reply = CompletableDeferred<JsonElement>()
        pending[id] = PendingCall(method, params, reply)
        try {
            if (!conn.ws.send(GatewayFrames.request(id, method, params))) {
                throw GatewayUnavailableException("Hermes connection closed.")
            }
            return withTimeout(timeoutMs) { reply.await() }
        } catch (e: TimeoutCancellationException) {
            throw GatewayUnavailableException("request timed out: $method", e)
        } finally {
            pending.remove(id)
        }
    }

    private fun handle(conn: Conn, ws: WebSocket, text: String) {
        conn.lastInbound.set(System.nanoTime())
        for (line in text.split('\n')) {
            if (line.isBlank()) continue
            val obj = runCatching { HermesJson.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: continue
            when (val frame = GatewayFrames.classify(obj)) {
                is GatewayFrame.Event ->
                    if (frame.type == "gateway.ready") {
                        conn.ready.complete(frame.payload?.string("replay_epoch").orEmpty())
                    } else {
                        val event = GatewayFrames.event(frame.type, frame.sessionId, frame.payload)
                        event.sessionId?.let { sid ->
                            if (event is GatewayEvent.MessageStart) running[sid] = true
                            if (event is GatewayEvent.MessageComplete) running[sid] = false
                        }
                        _events.tryEmit(event)
                    }
                is GatewayFrame.ServerRequest -> serverRequest(ws, frame.id, frame.method, frame.params)
                is GatewayFrame.Response -> pending[frame.id]?.let { call ->
                    if (frame.error != null) {
                        call.reply.completeExceptionally(
                            HermesRpcException(
                                call.method,
                                frame.error.long("code")?.toInt() ?: -1,
                                frame.error.string("message").orEmpty(),
                                frame.error["data"],
                            ),
                        )
                    } else {
                        trackRunning(call, frame.result)
                        call.reply.complete(frame.result ?: JsonNull)
                    }
                }
                GatewayFrame.Unknown -> Unit
            }
        }
    }

    private fun serverRequest(ws: WebSocket, id: JsonElement, method: String, params: JsonObject) {
        when (method) {
            "gateway.ping" -> ws.send(GatewayFrames.result(id, buildJsonObject { put("ok", true) }))
            "approval" -> _events.tryEmit(GatewayEvent.ApprovalRequested(GatewayFrames.approval(id, params)))
            "clarify" -> _events.tryEmit(GatewayEvent.ClarifyRequested(GatewayFrames.clarify(id, params)))
            in GatewayFrames.WINDOW_OWNED ->
                ws.send(GatewayFrames.error(id, GatewayFrames.NOT_SHOWN_CODE, "No Hermes Desktop window here."))
            else -> ws.send(GatewayFrames.error(id, GatewayFrames.METHOD_NOT_FOUND, "Not handled by this client."))
        }
    }

    private fun redeliver(conn: Conn, snapshot: JsonObject) {
        val id = snapshot["id"]?.takeUnless { it is JsonNull } ?: return
        val method = snapshot.string("method") ?: return
        serverRequest(conn.ws, id, method, snapshot.obj("params") ?: JsonObject(emptyMap()))
    }

    private fun failPending(reason: String) {
        val calls = pending.values.toList()
        pending.clear()
        calls.forEach { it.reply.completeExceptionally(GatewayUnavailableException("Hermes $reason (${it.method}).")) }
    }

    private fun protocol(method: String, what: String) = HermesRpcException(method, -32603, "unexpected reply: $what")
}
