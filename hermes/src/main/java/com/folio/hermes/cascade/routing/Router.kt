package com.folio.hermes.cascade.routing

import com.folio.hermes.cascade.AppLines
import com.folio.hermes.cascade.CascadeSettings
import com.folio.hermes.cascade.Decision
import com.folio.hermes.cascade.PhraseBank
import com.folio.hermes.cascade.Route
import com.folio.hermes.cascade.TaskBrief
import com.folio.hermes.cascade.TurnVerdict
import com.folio.hermes.cascade.UpdateMode
import com.folio.hermes.openrouter.JevAnswer

/**
 * Jev answers → what happens (C1; the default registry). Spec v2's rules are [CeoRouter]. Code,
 * not Jev, decides whether Jev was sure enough:
 * - `turn` below the floor (0.55) keeps listening;
 * - `action` below the floor is a clarifying question, never a task;
 * - a task action without a confident task asks which one (stock line, no writer);
 * - `update_mode` missing or unsure is `queue`;
 * - `enough = line_only` skips the writer only with a stock line and confidence ≥ 0.7.
 */
object Router {
    /** R-RT-3: `done` needs `turnDoneFloor` (0.60, §14 item 16); `ignore` is accepted from 0.4; anything else keeps listening. */
    fun verdict(answers: Map<String, JevAnswer>, settings: CascadeSettings): TurnVerdict {
        val turn = answers[CascadeBrain.TURN] ?: return TurnVerdict.KeepListening
        return when {
            turn.choice == CascadeBrain.DONE && turn.confidence >= settings.turnDoneFloor -> TurnVerdict.Done
            turn.choice == CascadeBrain.IGNORE && turn.confidence >= settings.ignoreFloor -> TurnVerdict.Ignore
            else -> TurnVerdict.KeepListening
        }
    }

    /** The stock slot Jev picked, if it is sure and the slot is one it may pick. */
    fun slot(answers: Map<String, JevAnswer>, bank: PhraseBank, settings: CascadeSettings): String? {
        val line = answers[CascadeBrain.LINE]?.takeIf { it.confidence >= settings.confidenceFloor }?.choice ?: return null
        if (line == CascadeBrain.NONE) return null
        return line.takeIf { id -> bank.jevSlots(settings.backchannel).any { it.id == id } }
    }

    /** While holding (keep_listening): a `listen` slot to murmur, only with backchannel on. */
    fun backchannel(answers: Map<String, JevAnswer>, bank: PhraseBank, settings: CascadeSettings): String? =
        if (!settings.backchannel) null else slot(answers, bank, settings)?.takeIf { bank[it]?.group == PhraseBank.LISTEN }

    fun decide(answers: Map<String, JevAnswer>, tasks: List<TaskBrief>, bank: PhraseBank, settings: CascadeSettings): Decision {
        val floor = settings.confidenceFloor
        val slot = slot(answers, bank, settings)
        val enough = answers[CascadeBrain.ENOUGH]
        val lineOnly = slot != null && enough?.choice == CascadeBrain.LINE_ONLY && enough.confidence >= settings.lineOnlyFloor
        val action = answers[CascadeBrain.ACTION]?.takeIf { it.confidence >= floor }?.choice
            ?: return Decision(Route.Clarify, slot, writer = !(lineOnly && bank[slot!!]?.group == "clarify"))
        val task = answers[CascadeBrain.TASK]?.takeIf { it.confidence >= floor }?.choice?.takeIf { id -> tasks.any { it.id == id } }
        fun which(slotId: String) = Decision(Route.Clarify, slotId, writer = false)
        return when (action) {
            CascadeBrain.REPLY -> Decision(Route.Reply, slot, writer = !lineOnly)
            CascadeBrain.NEW_TASK -> Decision(Route.NewTask, slot, writer = !lineOnly)
            CascadeBrain.UPDATE_TASK -> if (task == null) which(AppLines.WHICH_UPDATE) else {
                val redirect = answers[CascadeBrain.UPDATE_MODE]?.let { it.confidence >= floor && it.choice == CascadeBrain.REDIRECT } == true
                Decision(Route.UpdateTask(task, if (redirect) UpdateMode.Redirect else UpdateMode.Queue), slot, writer = !lineOnly)
            }
            CascadeBrain.STATUS -> Decision(Route.Status(task), slot, writer = !lineOnly)
            CascadeBrain.CANCEL_TASK -> if (task == null) which(AppLines.WHICH_CANCEL) else Decision(Route.CancelTask(task), slot, writer = !lineOnly)
            CascadeBrain.READ_RESULT -> if (task == null) which(AppLines.WHICH_ONE) else Decision(Route.ReadResult(task), slot, writer = true)
            CascadeBrain.END_CALL -> Decision(Route.EndCall, slot, writer = false)
            else -> Decision(Route.Clarify, slot, writer = true)
        }
    }
}
