package com.folio.hermeslive.memo

import com.folio.hermeslive.call.CallFailure
import com.folio.hermeslive.call.CallPhase
import com.folio.hermeslive.call.CallRequest
import com.folio.hermeslive.call.CallUiState
import com.folio.hermeslive.call.MemoCause

/**
 * What the call screen does after a GPT-Live call failed (pure): with Settings › Voice › Offline
 * memos on, a start that failed because the phone is offline records a memo at once (no extra
 * tap); one that failed for Hermes or its voice, or a call that dropped and could not reconnect,
 * offers it. Off: nothing, today's failure screen.
 */
object MemoFallback {
    enum class Action { None, Offer, Start }

    fun decide(state: CallUiState, memosOn: Boolean): Action {
        val failure = state.failure
        return when {
            !memosOn || state.inCall || state.memo || failure == null || !failure.offersMemo -> Action.None
            failure == CallFailure.Offline && state.phase == CallPhase.Failed -> Action.Start
            else -> Action.Offer
        }
    }

    /** The memo call for [state]'s failure. */
    fun request(state: CallUiState, entry: String): CallRequest =
        CallRequest(memo = true, entry = entry, memoCause = if (state.summary != null) MemoCause.CallDropped else MemoCause.of(state.failure))

    fun offerText(state: CallUiState): String = when {
        state.summary != null -> "Continue as memo"
        state.failure == CallFailure.Offline -> "You're offline · Record a memo"
        state.failure == CallFailure.VoiceUnavailable -> "Voice is unavailable · Record a memo"
        else -> "Hermes is unreachable · Record a memo"
    }
}

/**
 * The call screen's auto-start ([MemoFallback.Action.Start]), fired once per failed call: on the
 * change into the failed state, not again when the screen sees the same state again, not when the
 * setting is switched on over it, and never for a memo's own states (a memo is not a GPT-Live call).
 */
class MemoAutoStart {
    private var wasFailedOffline = false

    /** The memo to start now, or null. Only on a new failure: switching memos on over a failed screen starts nothing. */
    fun onState(state: CallUiState, memosOn: Boolean): CallRequest? {
        val failedOffline = MemoFallback.decide(state, memosOn = true) == MemoFallback.Action.Start
        val fire = failedOffline && !wasFailedOffline && memosOn
        wasFailedOffline = failedOffline
        return if (fire) MemoFallback.request(state, entry = "fallback") else null
    }
}
