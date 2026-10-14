package com.folio.hermeslive.call

import com.folio.hermes.cascade.BillUi
import com.folio.hermes.cascade.CascadeUiState
import com.folio.hermes.cascade.TurnPhase
import com.folio.hermes.cascade.TurnUi
import com.folio.hermes.live.Speaker

/**
 * Bill's call screen for a GPT-Live call with parallel agents (pure): the task tray, cards and
 * connection from the actor's projection; the turn, the orb and the captions from the GPT-Live
 * call ([CallUiState]); Jev's strip empty (GPT-Live has no Jev turn).
 */
object GptLiveScreen {
    fun state(tasks: CascadeUiState, call: CallUiState): CascadeUiState {
        val speaking = call.activity == CallActivity.HermesSpeaking
        val phase = when (call.activity) {
            CallActivity.UserSpeaking -> TurnPhase.UserSpeaking
            CallActivity.HermesSpeaking -> TurnPhase.BillSpeaking
            else -> TurnPhase.Idle
        }
        val last = call.captions.lastOrNull()
        val partial = last?.takeIf { it.speaker == Speaker.User && phase == TurnPhase.UserSpeaking }?.text.orEmpty()
        val reply = last?.takeIf { it.speaker == Speaker.Assistant && speaking }?.text?.let(::lastSentence)
        return tasks.copy(
            turn = TurnUi(phase = phase, partial = partial.takeLast(CAPTION_CHARS)),
            jev = tasks.jev.copy(inFlight = false, answers = emptyMap(), latencyMs = null),
            bill = BillUi(speaking = speaking, level = if (speaking) call.level else 0f, text = reply),
            connection = tasks.connection.copy(online = call.phase == CallPhase.Live || call.phase == CallPhase.Connecting || call.phase == CallPhase.Ending),
            // Bill's Resume card is his conversation's.
            resume = null,
        )
    }

    /** The sentence the voice is saying now (the tail of its caption). */
    private fun lastSentence(text: String): String {
        val t = text.trim()
        val cut = t.dropLast(1).lastIndexOfAny(charArrayOf('.', '?', '!'))
        return (if (cut >= 0) t.substring(cut + 1) else t).trim().takeLast(160)
    }

    private const val CAPTION_CHARS = 280
}
