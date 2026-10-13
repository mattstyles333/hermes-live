package com.folio.hermes.cascade

import com.folio.hermes.openrouter.JevAnswer

/** How an update reaches a running task. */
enum class UpdateMode(val wire: String) {
    /** `prompt.submit {queued: true}`: after the current step (the default). */
    Queue("queue"),

    /** Change what it is doing now (server busy mode, or interrupt then submit). */
    Redirect("redirect"),
}

/** What Jev's answer to a finished turn means. */
sealed interface Route {
    val wire: String

    data object Reply : Route { override val wire = "reply" }

    /** Jev was not sure what to do (or which task): ask, never act. */
    data object Clarify : Route { override val wire = "clarify" }

    data object NewTask : Route { override val wire = "new_task" }

    data class UpdateTask(val taskId: String, val mode: UpdateMode) : Route { override val wire = "update_task" }

    /** [taskId] null: all tasks. Answered from the phone's event log only. */
    data class Status(val taskId: String?) : Route { override val wire = "status" }

    data class CancelTask(val taskId: String) : Route { override val wire = "cancel_task" }

    data class ReadResult(val taskId: String) : Route { override val wire = "read_result" }

    data object EndCall : Route { override val wire = "end_call" }

    /** Jev gave nothing usable: say "say that again?" and do nothing else. */
    data object AskRepeat : Route { override val wire = "ask_repeat" }
}

/** A finished turn's plan: the [route], the stock [slot] to play first, and whether the [writer] speaks after it. */
data class Decision(val route: Route, val slot: String?, val writer: Boolean)

/** Jev's `turn` verdict after the confidence floor. */
enum class TurnVerdict { Done, KeepListening, Ignore }

/**
 * Jev answers → what happens. Code, not Jev, decides whether Jev was sure enough:
 * - `turn` below the floor (0.55) keeps listening;
 * - `action` below the floor is a clarifying question, never a task;
 * - a task action without a confident task asks which one (stock line, no writer);
 * - `update_mode` missing or unsure is `queue`;
 * - `enough = line_only` skips the writer only with a stock line and confidence ≥ 0.7.
 */
object Router {
    fun verdict(answers: Map<String, JevAnswer>, settings: CascadeSettings): TurnVerdict {
        val turn = answers[CascadeBrain.TURN]?.takeIf { it.confidence >= settings.confidenceFloor }
        return when (turn?.choice) {
            CascadeBrain.DONE -> TurnVerdict.Done
            CascadeBrain.IGNORE -> TurnVerdict.Ignore
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
