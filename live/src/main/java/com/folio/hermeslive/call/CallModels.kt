package com.folio.hermeslive.call

import com.folio.hermes.ApprovalChoice
import com.folio.hermes.ApprovalRequest
import com.folio.hermes.ClarifyRequest
import com.folio.hermes.live.Speaker

/** Where a call is in its life. */
enum class CallPhase {
    /** No call. */
    Idle,

    /** Setting up WebRTC and the voice session. */
    Connecting,

    /** The voice is live. */
    Live,

    /** The line dropped; dialling again once, with the transcript as history. */
    Reconnecting,

    /** `session.close` sent (or a goodbye in progress); waiting for `session.closed`. */
    Ending,

    /** The call is over; [CallUiState.summary] says how it went. */
    Ended,

    /** The call could not start; [CallUiState.error] says why. */
    Failed,
}

/** What the orb shows while a call is live. */
enum class CallActivity { Listening, UserSpeaking, Thinking, HermesSpeaking }

/** How a call was asked for. */
data class CallRequest(
    /** First request, submitted as a text delegation once the voice is live. */
    val prompt: String? = null,
    /** Start with the mic muted. */
    val muted: Boolean = false,
    /** Forget the previous Hermes session and start a new conversation. */
    val newConversation: Boolean = false,
    /** Where the call came from, for diagnostics: app, launcher, folio, shortcut, assist, headset, tile, share, memo. */
    val entry: String = "app",
    /** Memo mode asked for (the Memo shortcut, or the tile offline; R-OFF-2). Bill only. */
    val memo: Boolean = false,
    /** Use GPT-Live for this call even with Bill chosen (the "Preparing Bill…" fallback). */
    val forceGptLive: Boolean = false,
    /** Use Bill for this call even with Grok chosen (Grok offline: "Continue in Bill's memo mode"). */
    val forceBill: Boolean = false,
    /** Why an offline memo was started: its lines and telemetry (offline-memo slice). */
    val memoCause: MemoCause = MemoCause.Asked,
)

/** Why an offline memo is recorded (its spoken lines, its screen notice, `memo.start`'s `cause`). */
enum class MemoCause(val wire: String) {
    /** The Memo shortcut, the tile offline, or "Record a memo" with nothing failed. */
    Asked("asked"),
    Offline("offline"),
    HermesUnreachable("hermes_unreachable"),
    VoiceUnavailable("voice_unavailable"),

    /** A GPT-Live call dropped and could not reconnect: "Continue as memo". */
    CallDropped("call_dropped"),
    ;

    companion object {
        fun of(wire: String?): MemoCause = entries.firstOrNull { it.wire == wire } ?: Asked

        fun of(failure: CallFailure?): MemoCause = when (failure) {
            CallFailure.Offline -> Offline
            CallFailure.HermesUnreachable -> HermesUnreachable
            CallFailure.VoiceUnavailable -> VoiceUnavailable
            CallFailure.Other, null -> Asked
        }
    }
}

/**
 * Why a GPT-Live call could not start (or could not reconnect), alongside its message (offline-memo
 * slice). The first three offer an offline memo when Settings › Voice › Offline memos is on.
 */
enum class CallFailure(val wire: String) {
    /** The phone has no validated network. */
    Offline("offline"),

    /** Online, but Hermes did not answer (network error, 5xx, timeout). */
    HermesUnreachable("hermes_unreachable"),

    /** Hermes answered but voice-live can't start (503: no OpenAI key; 502: OpenAI refused; no `session.started`). */
    VoiceUnavailable("voice_unavailable"),

    /** Anything else (sign-in, the microphone, a bad answer): no memo offer. */
    Other("other"),
    ;

    val offersMemo: Boolean get() = this != Other
}

/** One caption line: a speaker's turn, merged from transcript deltas. */
data class Caption(val speaker: Speaker, val text: String)

/** Shown when a call ends. Cost is an estimate at [CallSummary.USD_PER_MINUTE]. */
data class CallSummary(
    val durationMs: Long,
    /** Billed voice seconds from the vendor, when it said. */
    val voiceSeconds: Double?,
    /** Plain-English reason. */
    val reason: String,
) {
    /** Estimated cost: billed seconds when known, else the call's length. */
    val estimatedCostUsd: Double get() = ((voiceSeconds ?: (durationMs / 1000.0)) / 60.0) * USD_PER_MINUTE

    companion object {
        const val USD_PER_MINUTE = 0.05
    }
}

/** An approval on screen: never answered without a tap (or, when the user turned it on, a spoken yes/no). */
data class ApprovalCard(val request: ApprovalRequest) {
    val id: String get() = request.id
    val choices: List<ApprovalChoice> get() = request.choices
}

/** A clarify question on screen. */
data class ClarifyCard(val request: ClarifyRequest) {
    val id: String get() = request.id
}

/** Everything the call screen, notification and tile show. */
data class CallUiState(
    val phase: CallPhase = CallPhase.Idle,
    val activity: CallActivity = CallActivity.Listening,
    val muted: Boolean = false,
    /** Speakable name of the tool Hermes is running, if any. */
    val tool: String? = null,
    /** Music may play now (a gap), and something is playing. */
    val musicInGap: Boolean = false,
    val route: RouteOption? = null,
    val routes: List<RouteOption> = emptyList(),
    /** Whole-call captions, oldest first. */
    val captions: List<Caption> = emptyList(),
    val approvals: List<ApprovalCard> = emptyList(),
    val clarify: ClarifyCard? = null,
    /** A short status line ("Reconnecting…", "Paused for your phone call"). */
    val notice: String? = null,
    /** A warning about the call ending soon (idle or cap). */
    val warning: String? = null,
    /** Another call has the audio; the mic is muted until it ends. */
    val onOtherCall: Boolean = false,
    /** Orb level 0..1 (the speaking side's loudness). */
    val level: Float = 0f,
    /** When the voice went live, epoch ms. */
    val startedAtMs: Long? = null,
    val summary: CallSummary? = null,
    val error: String? = null,
    /** Grok can't reach xAI and the phone is offline: offer Bill's offline memo mode (his models are on the phone). */
    val offerBillMemo: Boolean = false,
    /** Why the call failed (GPT-Live connect or reconnect), with [error]; null when it did not. */
    val failure: CallFailure? = null,
    /** This is an offline memo (`MemoCall`): the mic records to the phone, nothing is live. */
    val memo: Boolean = false,
) {
    /** True from Connecting to Ending: the service stays in the foreground. */
    val inCall: Boolean get() = phase == CallPhase.Connecting || phase == CallPhase.Live || phase == CallPhase.Reconnecting || phase == CallPhase.Ending
}

/**
 * The Hermes session to resume: calls within [RESUME_WINDOW_MS] of the last activity carry on the
 * same Hermes conversation; later ones start fresh.
 */
data class SessionMemory(val storedSessionId: String?, val lastActiveAtMs: Long) {
    fun resumable(nowMs: Long): String? =
        storedSessionId?.takeIf { nowMs - lastActiveAtMs in 0..RESUME_WINDOW_MS }

    companion object {
        const val RESUME_WINDOW_MS = 30 * 60_000L
        val Empty = SessionMemory(null, 0L)
    }
}
