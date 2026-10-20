package com.folio.hermeslive.memo

import com.folio.hermeslive.call.CallFailure
import com.folio.hermeslive.call.CallPhase
import com.folio.hermeslive.call.CallRequest
import com.folio.hermeslive.call.CallUiState

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
        CallRequest(memo = true, entry = entry, memoCause = if (state.summary != null) "call_dropped" else state.failure?.wire ?: "asked")

    fun offerText(state: CallUiState): String = when {
        state.summary != null -> "Continue as memo"
        state.failure == CallFailure.Offline -> "You're offline · Record a memo"
        state.failure == CallFailure.VoiceUnavailable -> "Voice is unavailable · Record a memo"
        else -> "Hermes is unreachable · Record a memo"
    }
}
