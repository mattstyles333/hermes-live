package com.folio.hermeslive.grok

import com.folio.hermes.cascade.BillUi
import com.folio.hermes.cascade.CascadeUiState
import com.folio.hermes.cascade.TaskUi
import com.folio.hermes.cascade.TurnPhase
import com.folio.hermes.cascade.TurnUi
import com.folio.hermes.realtime.GrokTools
import com.folio.hermes.realtime.GrokUi
import com.folio.hermes.realtime.LinkState
import com.folio.hermes.realtime.ToolChip
import com.folio.hermeslive.ui.cascade.JevChip
import com.folio.hermeslive.ui.cascade.SceneModel

/**
 * Bill's call screen for a Grok call (pure): the task tray, connection and memo state from the
 * actor's projection; the turn, the orb and the caption from Grok ([GrokUi]); Jev's answers gone
 * (the strip shows Grok's tool calls instead, [chips]).
 */
object GrokScreen {
    fun state(tasks: CascadeUiState, grok: GrokUi, outputLevel: Float): CascadeUiState {
        val phase = when {
            grok.userSpeaking -> TurnPhase.UserSpeaking
            grok.speaking -> TurnPhase.BillSpeaking
            else -> TurnPhase.Idle
        }
        val online = grok.link is LinkState.Open || grok.link is LinkState.Connecting || grok.link == LinkState.Idle || grok.link == LinkState.Parked
        return tasks.copy(
            turn = TurnUi(phase = phase, partial = if (grok.userSpeaking) grok.caption else ""),
            jev = tasks.jev.copy(inFlight = false, answers = emptyMap(), latencyMs = null),
            bill = BillUi(speaking = grok.speaking, level = if (grok.speaking) outputLevel else 0f, text = grok.reply.takeIf { grok.speaking && it.isNotBlank() }?.let(::lastSentence)),
            connection = tasks.connection.copy(online = online),
            // Bill's Resume card is his conversation's; Grok has its own.
            resume = null,
        )
    }

    /** Grok's tool calls as strip chips: the tool, then its task (by label) or an ellipsis while it runs. */
    fun chips(chips: List<ToolChip>, tasks: List<TaskUi>): List<JevChip> = chips.takeLast(MAX).map { c ->
        val what = c.taskId?.let { id -> tasks.firstOrNull { it.id == id }?.label?.let(SceneModel::shortLabel) ?: id }
        val status = when (c.ok) {
            null -> "…"
            false -> "failed"
            true -> what ?: "ok"
        }
        JevChip(label(c.name), status, null)
    }

    private fun label(tool: String): String = when (tool) {
        GrokTools.START_TASK -> "start"
        GrokTools.UPDATE_TASK -> "update"
        GrokTools.TASK_STATUS -> "status"
        GrokTools.CANCEL_TASK -> "cancel"
        GrokTools.READ_RESULT -> "read"
        GrokTools.END_CALL -> "end call"
        else -> tool.replace('_', ' ')
    }

    /** The sentence Grok is saying now (the tail of its transcript). */
    private fun lastSentence(text: String): String {
        val t = text.trim()
        val cut = t.dropLast(1).lastIndexOfAny(charArrayOf('.', '?', '!'))
        return (if (cut >= 0) t.substring(cut + 1) else t).trim().takeLast(160)
    }

    private const val MAX = 4
}
