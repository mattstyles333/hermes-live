package com.folio.hermes.live

import com.folio.hermes.AccessTokenProvider
import com.folio.hermes.HermesConfig
import com.folio.hermes.HermesJson
import com.folio.hermes.asText
import com.folio.hermes.await
import com.folio.hermes.jsonBody
import com.folio.hermes.readText
import java.util.concurrent.TimeUnit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** `GET /api/audio/voice-live/status`. [available] is false when the server has no OpenAI key. */
@Serializable
data class VoiceLiveStatus(
    val ok: Boolean = false,
    /** `chained` or `gpt-live` (the server's selected desktop mode; Hermes Live always uses GPT-Live). */
    val mode: String = "chained",
    val available: Boolean = false,
    val reason: String? = null,
    val model: String = "",
    val voice: String = "",
)

/** A text turn to seed a new live session with. */
data class LiveTurn(val speaker: Speaker, val text: String)

/** One history item for `POST /api/audio/voice-live/session`. */
@Serializable
data class LiveHistoryMessage(
    val type: String = "message",
    val role: String,
    val content: List<LiveHistoryContent>,
)

/** `{"type":"input_text"|"output_text","text":…}`. */
@Serializable
data class LiveHistoryContent(val type: String, val text: String)

/** The session the server created: hand [sdpAnswer] to `setRemoteDescription` as an answer. */
data class VoiceLiveAnswer(val sessionId: String?, val sdpAnswer: String)

/** Why a voice session could not be created. */
class VoiceLiveException(val kind: Kind, val httpCode: Int, detail: String) : java.io.IOException(detail) {
    enum class Kind {
        /** 400: the offer was empty. */
        EmptyOffer,

        /** 503: the server has no OpenAI key. */
        NotConfigured,

        /** 502: OpenAI refused the session. */
        VendorRejected,

        /** 401 after a refresh: sign in again. */
        Unauthorized,

        /** Anything else, including an unreadable answer. */
        Failed,
    }
}

/**
 * The two GPT-Live calls Hermes Live makes on the Hermes server. The OpenAI key stays on the
 * server; the client only sends its SDP offer (byte-exact, CRLFs included) and gets the answer.
 * A 401 refreshes the token once and retries.
 */
class VoiceLiveApi(
    private val config: HermesConfig,
    private val tokens: AccessTokenProvider,
    client: OkHttpClient = OkHttpClient(),
) {
    private val client = client
    private val sessionClient: OkHttpClient by lazy {
        client.newBuilder()
            .callTimeout(config.voiceSessionTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(config.voiceSessionTimeoutMs, TimeUnit.MILLISECONDS)
            .build()
    }

    /** Readiness, or null when the server is too old to have the endpoint or answers oddly. */
    suspend fun status(): VoiceLiveStatus? {
        val response = authorized(client) { token ->
            Request.Builder().url(config.endpoint.resolve("/api/audio/voice-live/status"))
                .header("Authorization", "Bearer $token").get().build()
        }
        val code = response.code
        val text = response.readText(64_000)
        if (code !in 200..299) return null
        return runCatching { HermesJson.decodeFromString<VoiceLiveStatus>(text) }.getOrNull()?.takeIf { it.ok }
    }

    /** Exchange [sdpOffer] for the session answer, seeding the model with [history]. */
    suspend fun createSession(sdpOffer: String, history: List<LiveHistoryMessage> = emptyList()): VoiceLiveAnswer {
        val body = HermesJson.encodeToString(SessionRequest(sdpOffer, history))
        val response = authorized(sessionClient) { token ->
            Request.Builder().url(config.endpoint.resolve("/api/audio/voice-live/session"))
                .header("Authorization", "Bearer $token").post(body.jsonBody()).build()
        }
        val code = response.code
        val text = response.readText(256_000)
        if (code !in 200..299) {
            val detail = runCatching { HermesJson.decodeFromString<ErrorBody>(text) }.getOrNull()?.detail?.asText()?.ifEmpty { null }
                ?: text.take(300)
            val kind = when (code) {
                400 -> VoiceLiveException.Kind.EmptyOffer
                401 -> VoiceLiveException.Kind.Unauthorized
                502 -> VoiceLiveException.Kind.VendorRejected
                503 -> VoiceLiveException.Kind.NotConfigured
                else -> VoiceLiveException.Kind.Failed
            }
            throw VoiceLiveException(kind, code, "Voice session failed ($code): $detail")
        }
        val wire = runCatching { HermesJson.decodeFromString<SessionResponse>(text) }.getOrNull()
        val sdp = wire?.transport?.sdp
        if (wire?.ok != true || sdp.isNullOrEmpty()) {
            throw VoiceLiveException(VoiceLiveException.Kind.Failed, code, "GPT-Live session creation failed")
        }
        return VoiceLiveAnswer(wire.session?.id, sdp)
    }

    private suspend fun authorized(http: OkHttpClient, build: (String) -> Request): Response {
        val token = tokens.accessToken()
        val first = http.newCall(build(token)).await()
        if (first.code != 401) return first
        first.close()
        val renewed = tokens.refreshAfterUnauthorized(token)
        return http.newCall(build(renewed)).await()
    }

    @Serializable internal data class SessionRequest(val sdp: String, val history: List<LiveHistoryMessage>)
    @Serializable internal data class SessionResponse(val ok: Boolean = false, val session: Sess? = null, val transport: Transport? = null)
    @Serializable internal data class Sess(val id: String? = null)
    @Serializable internal data class Transport(val type: String? = null, val sdp: String? = null)
    @Serializable internal data class ErrorBody(val detail: JsonElement? = null)

    companion object {
        const val MAX_MESSAGES = 24
        const val MAX_CHARS = 6_000
        const val MAX_MESSAGE_CHARS = 1_200
        private val WHITESPACE = Regex("\\s+")

        /**
         * Port of the desktop `toLiveHistory`: newest first until [maxMessages] or the [maxChars]
         * budget runs out (stops at the first turn that does not fit), each turn cut to 1200 chars.
         */
        fun history(turns: List<LiveTurn>, maxMessages: Int = MAX_MESSAGES, maxChars: Int = MAX_CHARS): List<LiveHistoryMessage> {
            val out = ArrayDeque<LiveHistoryMessage>()
            var budget = maxChars
            for (turn in turns.asReversed()) {
                val text = turn.text.replace(WHITESPACE, " ").trim().take(MAX_MESSAGE_CHARS)
                if (text.isEmpty()) continue
                if (out.size >= maxMessages || budget - text.length < 0) break
                budget -= text.length
                val assistant = turn.speaker == Speaker.Assistant
                out.addFirst(
                    LiveHistoryMessage(
                        role = if (assistant) "assistant" else "user",
                        content = listOf(LiveHistoryContent(if (assistant) "output_text" else "input_text", text)),
                    ),
                )
            }
            return out.toList()
        }
    }
}
