package com.folio.hermes

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Connection state of [HermesGateway]. */
sealed interface GatewayConnection {
    /** Not started, or [HermesGateway.disconnect] was called. */
    data object Idle : GatewayConnection

    /** Fetching a ticket and opening the socket. [attempt] counts from 1 since the last ready. */
    data class Connecting(val attempt: Int) : GatewayConnection

    /** `gateway.ready` arrived and the client advertised itself. [generation] goes up on every ready. */
    data class Ready(val generation: Long, val replayEpoch: String) : GatewayConnection

    /** Waiting [delayMs] before the next attempt, after [reason]. */
    data class Waiting(val attempt: Int, val delayMs: Long, val reason: String) : GatewayConnection

    /** Gave up (no session, or the server keeps refusing the handshake). [connect] starts again. */
    data class Stopped(val reason: String, val signInRequired: Boolean) : GatewayConnection
}

/** A JSON-RPC error from the gateway. */
class HermesRpcException(
    val method: String,
    val code: Int,
    message: String,
    val data: JsonElement? = null,
) : java.io.IOException("$method failed ($code): $message") {
    /** The runtime session id is gone (detached and reaped); resume the stored id. */
    val isSessionNotFound: Boolean get() = code == 4001

    /** A turn is still running (4009); retry shortly. */
    val isSessionBusy: Boolean get() = code == 4009 || message?.contains("session busy", ignoreCase = true) == true

    /** Another surface holds the session lease (4090); do not retry, start a new session. */
    val isSessionNotOwned: Boolean get() = code == 4090
}

/** The socket dropped (or never became ready) while a call was waiting. */
class GatewayUnavailableException(message: String, cause: Throwable? = null) : java.io.IOException(message, cause)

/** `prompt.submit` `surface`: how the gateway should frame the turn for the model. */
enum class PromptSurface(val wire: String) {
    /** A GPT-Live delegation: spoken transcript in, speakable prose out. */
    VoiceLive("voice-live"),
}

/** The server's `display.busy_input_mode`: what a `prompt.submit` to a busy session does. */
enum class BusyMode(val wire: String) {
    /** Redirect the live turn (the server default). */
    Interrupt("interrupt"),

    /** Run after the live turn. */
    Queue("queue"),

    /** Inject into the live turn as a steer. */
    Steer("steer"),
}

/** Options for `session.create`. Unset fields are omitted so the server's own defaults apply. */
data class SessionOptions(
    /** Shown in the dashboard's session list. */
    val title: String? = null,
    /**
     * DB `source` / agent platform. Leave null: the server falls back to its own platform, and an
     * unknown value can change which tools the agent offers.
     */
    val source: String? = null,
    /** Server profile name; null is the launch profile. */
    val profile: String? = null,
    /** Hide from the dashboard sidebar. */
    val hidden: Boolean? = null,
)

/** `session.create` result. [storedSessionId] is the durable key to keep for `session.resume`. */
data class CreatedSession(val sessionId: String, val storedSessionId: String, val model: String?)

/** `session.resume` result. Open server requests were re-delivered as events already. */
data class ResumedSession(
    val sessionId: String,
    val storedSessionId: String,
    val running: Boolean,
    val status: String,
    val messageCount: Int,
    val openRequests: Int,
)

/** What `prompt.submit` did with the text. */
enum class SubmitStatus {
    /** A new turn started (the session was idle). */
    Streaming,

    /** The session was busy; the text waits for the next turn (and the live turn was asked to stop). */
    Queued,

    /** The text redirected the running turn; that turn's completion carries the answer. */
    Redirected,

    /** The text was injected into the running turn as a steer. */
    Steered,

    /** The server read the text as a typed stop phrase for its own voice mode; no turn ran. */
    VoiceStopped,

    /** A status this client does not know. */
    Unknown,
}

/** `prompt.submit` result. */
data class PromptSubmitResult(val status: SubmitStatus, val raw: JsonObject)

/** How a turn ended (`message.complete.status`; absent means [Complete]). */
enum class TurnStatus { Complete, Error, Interrupted }

/** Choices for an approval answer. */
enum class ApprovalChoice(val wire: String) { Once("once"), Session("session"), Always("always"), Deny("deny") }

/**
 * The agent wants to run something that needs the user's yes. Shown as a card; never answered
 * automatically. Answer with [HermesGateway.answerApproval].
 */
data class ApprovalRequest(
    /** JSON-RPC id of the server request (`srq-…`). */
    val id: String,
    val sessionId: String,
    /** The approval queue's own id. */
    val requestId: String,
    /** The command, redacted by the server. */
    val command: String,
    val description: String,
    val choices: List<ApprovalChoice>,
    val toolName: String?,
    val params: JsonObject,
)

/** One clarify question. [choices] empty means free text. */
data class ClarifyQuestion(val qid: String, val question: String, val choices: List<String>, val multiSelect: Boolean)

/** The agent asks the user 1-5 questions. Answer with [HermesGateway.answerClarify]. */
data class ClarifyRequest(val id: String, val sessionId: String, val questions: List<ClarifyQuestion>, val params: JsonObject)

/** Events from the gateway. [sessionId] is the runtime session id, null for global events. */
sealed interface GatewayEvent {
    val sessionId: String?

    /** A turn began streaming. */
    data class MessageStart(override val sessionId: String?) : GatewayEvent

    /** One streamed chunk of the reply. */
    data class MessageDelta(override val sessionId: String?, val text: String) : GatewayEvent

    /** Commentary beside tool calls, sealed as its own segment. */
    data class MessageInterim(override val sessionId: String?, val text: String, val alreadyStreamed: Boolean) : GatewayEvent

    /**
     * The turn ended. [responseTransformed]: [text] replaces what was streamed and need not extend it.
     */
    data class MessageComplete(
        override val sessionId: String?,
        val text: String,
        val status: TurnStatus,
        val error: String?,
        val responseTransformed: Boolean,
    ) : GatewayEvent

    /** A tool call began. */
    data class ToolStart(override val sessionId: String?, val toolId: String, val name: String, val context: String?) : GatewayEvent

    /** A tool call finished. */
    data class ToolComplete(override val sessionId: String?, val toolId: String, val name: String, val summary: String?) : GatewayEvent

    /** A session-level failure outside a turn. */
    data class Error(override val sessionId: String?, val message: String) : GatewayEvent

    /** Transient status line. */
    data class Status(override val sessionId: String?, val kind: String, val text: String) : GatewayEvent

    /** An approval request; see [ApprovalRequest]. */
    data class ApprovalRequested(val request: ApprovalRequest) : GatewayEvent {
        override val sessionId: String get() = request.sessionId
    }

    /** A clarify request; see [ClarifyRequest]. */
    data class ClarifyRequested(val request: ClarifyRequest) : GatewayEvent {
        override val sessionId: String get() = request.sessionId
    }

    /** The server withdrew an open request (timeout, interrupt, answered elsewhere): clear its card. */
    data class RequestCancelled(override val sessionId: String?, val id: String, val method: String, val reason: String) : GatewayEvent

    /** Any other event, untouched. */
    data class Other(override val sessionId: String?, val type: String, val payload: JsonObject?) : GatewayEvent
}
