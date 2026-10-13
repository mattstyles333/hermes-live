package com.folio.hermes

import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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

    /** method -> handler(socket, id, params). Unhandled methods get no reply. */
    val responders = ConcurrentHashMap<String, (ServerSide, JsonElement, JsonObject) -> Unit>()

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
                else -> json(404, """{"detail":"Not Found"}""")
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
            side.frames.add(obj)
            val method = obj.string("method")
            val id = obj["id"]
            when {
                method == "gateway.ping" -> if (answerPings && id != null) side.reply(id, """{"ok":true}""")
                method == "client.capabilities" && id != null ->
                    if (capabilitiesUnsupported) side.error(id, -32601, "unknown method: client.capabilities")
                    else side.reply(id, """{"server_requests":["approval","clarify","sudo"],"declines_not_shown":true}""")
                method != null && id != null -> responders[method]?.invoke(side, id, obj.obj("params") ?: JsonObject(emptyMap()))
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }
    }

    override fun close() = server.close()

    class ServerSide {
        lateinit var ws: WebSocket
        val frames = CopyOnWriteArrayList<JsonObject>()
        private val consumed = ConcurrentHashMap.newKeySet<Int>()

        fun send(text: String) = check(ws.send(text))
        fun reply(id: JsonElement, result: String) = send("""{"jsonrpc":"2.0","result":$result,"id":$id}""")
        fun error(id: JsonElement, code: Int, message: String, data: String = "null") =
            send("""{"jsonrpc":"2.0","error":{"code":$code,"message":"$message","data":$data},"id":$id}""")

        fun event(type: String, sessionId: String?, payload: String? = null) {
            val sid = sessionId?.let { "\"$it\"" } ?: "null"
            val p = payload?.let { ""","payload":$it""" } ?: ""
            send("""{"jsonrpc":"2.0","method":"event","params":{"type":"$type","session_id":$sid$p}}""")
        }

        /** The next unconsumed frame matching [predicate] (polls; frames arrive on OkHttp's thread). */
        suspend fun next(timeoutMs: Long = 5_000, predicate: (JsonObject) -> Boolean): JsonObject {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                frames.withIndex().firstOrNull { (i, f) -> i !in consumed && predicate(f) }?.let { (i, f) ->
                    consumed.add(i)
                    return f
                }
                kotlinx.coroutines.delay(5)
            }
            error("no matching frame; got ${frames.map { it.string("method") ?: it["id"] }}")
        }

        suspend fun nextCall(method: String, timeoutMs: Long = 5_000) = next(timeoutMs) { it.string("method") == method }
        suspend fun nextResponse(id: String, timeoutMs: Long = 5_000) =
            next(timeoutMs) { it.string("method") == null && it.string("id") == id }
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
