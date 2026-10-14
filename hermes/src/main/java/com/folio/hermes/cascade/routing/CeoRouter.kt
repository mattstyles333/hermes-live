package com.folio.hermes.cascade.routing

import com.folio.hermes.cascade.AppLines
import com.folio.hermes.cascade.Decision
import com.folio.hermes.cascade.PhraseBank
import com.folio.hermes.cascade.Route
import com.folio.hermes.cascade.State
import com.folio.hermes.cascade.TaskRecord
import com.folio.hermes.cascade.TaskState
import com.folio.hermes.cascade.UpdateMode
import com.folio.hermes.openrouter.JevAnswer

/**
 * Jev's answers → the plan, with the spec v2 rules (the C1 [Router.decide] stays for the default
 * registry):
 * - R-RT-3: `action` below `actionFloor` plays `clarify.confirm` and never starts a task; `task`
 *   below its floor asks which one, unless exactly one task fits the action, which then wins.
 * - R-RT-5: the stock line must come from the action's groups, else the action's default slot.
 * - R-RT-6: `enough = line_only` counts only for reply, update, cancel and end call, at ≥ 0.7.
 * - R-RT-7: an update redirects when it targets the newest task and that is under 20 s old, or
 *   when Jev's `redirect` confidence is ≥ 0.6; otherwise it is queued.
 * - R-TASK-4: a cancel with no sure task, right after a start, cancels the newest task.
 * - R-STT-5: `clarity = garbled` (sure) plays `clarify.again` instead of acting.
 */
object CeoRouter {
    /** R-RT-5: the slot groups each action may play. */
    val ALLOWED: Map<String, Set<String>> = mapOf(
        CascadeBrain.NEW_TASK to setOf("start"),
        CascadeBrain.UPDATE_TASK to setOf("update", "ack"),
        CascadeBrain.STATUS to setOf("status"),
        CascadeBrain.CANCEL_TASK to setOf("cancel"),
        CascadeBrain.READ_RESULT to setOf("result"),
        CascadeBrain.REPLY to setOf("ack", "social"),
        CascadeBrain.END_CALL to setOf("end"),
    )

    /** R-RT-6: the actions whose stock line may be the whole answer. */
    val ENOUGH_ACTIONS = setOf(CascadeBrain.REPLY, CascadeBrain.UPDATE_TASK, CascadeBrain.CANCEL_TASK, CascadeBrain.END_CALL)

    const val CONFIRM = "clarify.confirm"
    const val START_ONE = "start.one"
    const val START_PARALLEL = "start.parallel"

    fun decide(state: State, answers: Map<String, JevAnswer>, bank: PhraseBank): Decision {
        val settings = state.settings
        val floor = settings.confidenceFloor
        val action = answers[CascadeBrain.ACTION]?.takeIf { it.confidence >= settings.actionFloor }?.choice
        if (action == null || action !in ALLOWED) return Decision(Route.Clarify, CONFIRM, writer = false)
        val clarity = answers[CascadeBrain.CLARITY]?.takeIf { it.confidence >= floor }?.choice
        if (clarity == CascadeBrain.GARBLED && action != CascadeBrain.END_CALL) return Decision(Route.AskRepeat, null, writer = false)

        val tasks = state.tasks.tasks
        val picked = answers[CascadeBrain.TASK]?.takeIf { it.confidence >= settings.taskFloor }?.choice?.takeIf { id -> tasks.any { it.id == id } }
        val candidates = candidates(action, tasks)
        val task = picked ?: candidates.singleOrNull()?.id ?: if (action == CascadeBrain.CANCEL_TASK) justStarted(state)?.id else null

        val jevSlot = answers[CascadeBrain.LINE]?.takeIf { it.confidence >= floor }?.choice?.takeIf { it != CascadeBrain.NONE }
        val enough = answers[CascadeBrain.ENOUGH]
        fun slot(default: String?, fits: (String) -> Boolean = { true }): String? {
            val id = jevSlot ?: return default
            val ok = bank.jevSlots(settings.backchannel).any { it.id == id } && bank[id]?.group in ALLOWED.getValue(action) && fits(id)
            return if (ok) id else default
        }
        fun lineOnly(slot: String?) = slot != null && action in ENOUGH_ACTIONS && enough?.choice == CascadeBrain.LINE_ONLY && enough.confidence >= settings.lineOnlyFloor
        fun which(slotId: String) = Decision(Route.Clarify, slotId, writer = false)

        return when (action) {
            CascadeBrain.REPLY -> slot(null).let { Decision(Route.Reply, it, writer = !lineOnly(it)) }
            CascadeBrain.NEW_TASK -> {
                val several = answers[CascadeBrain.SPLIT]?.let { it.choice == CascadeBrain.SEVERAL && it.confidence >= floor } == true
                val others = tasks.any { it.state == TaskState.Running }
                Decision(Route.NewTask, slot(if (several || others) START_PARALLEL else START_ONE), writer = true)
            }
            CascadeBrain.UPDATE_TASK -> if (task == null) which(AppLines.WHICH_UPDATE) else {
                val mode = updateMode(state, task, answers)
                val default = if (mode == UpdateMode.Queue) "update.queued" else "update.redirect"
                // The update line must say what happened; `update.which` never fits once the task is known.
                val s = slot(default) { id -> bank[id]?.group == "ack" || id == default }
                Decision(Route.UpdateTask(task, mode), s, writer = !lineOnly(s))
            }
            CascadeBrain.STATUS -> Decision(Route.Status(picked ?: candidates.singleOrNull()?.id), slot(null), writer = true)
            CascadeBrain.CANCEL_TASK -> if (task == null) which(AppLines.WHICH_CANCEL) else {
                val s = slot("cancel.one") { it != AppLines.WHICH_CANCEL }
                Decision(Route.CancelTask(task), s, writer = !lineOnly(s))
            }
            CascadeBrain.READ_RESULT -> if (task == null) which(AppLines.WHICH_ONE) else Decision(Route.ReadResult(task), slot(AppLines.RESULT_BACK), writer = true)
            else -> {
                val bye = if (tasks.any { it.state == TaskState.Running }) AppLines.BYE_TASKS else AppLines.BYE
                Decision(Route.EndCall, slot(bye), writer = false)
            }
        }
    }

    /** The tasks an action can be about (R-RT-3's single-candidate rule). */
    fun candidates(action: String, tasks: List<TaskRecord>): List<TaskRecord> = when (action) {
        CascadeBrain.CANCEL_TASK -> tasks.filter { it.state == TaskState.Running }
        CascadeBrain.READ_RESULT -> tasks.filter { it.state == TaskState.Done && it.result != null }
        CascadeBrain.UPDATE_TASK, CascadeBrain.STATUS -> tasks
        else -> emptyList()
    }

    /** The newest task (by start), if it started within the redirect window. */
    fun justStarted(state: State): TaskRecord? =
        state.tasks.tasks.filter { it.state == TaskState.Running }.maxByOrNull { it.startedAt }
            ?.takeIf { state.wallMs - it.startedAt < state.settings.redirectWindowMs }

    /** R-RT-7. */
    fun updateMode(state: State, taskId: String, answers: Map<String, JevAnswer>): UpdateMode {
        val newest = state.tasks.tasks.maxByOrNull { it.startedAt }
        if (newest?.id == taskId && newest.state == TaskState.Running && state.wallMs - newest.startedAt < state.settings.redirectWindowMs) return UpdateMode.Redirect
        val redirect = answers[CascadeBrain.UPDATE_MODE]?.let { it.choice == CascadeBrain.REDIRECT && it.confidence >= state.settings.redirectFloor } == true
        return if (redirect) UpdateMode.Redirect else UpdateMode.Queue
    }
}
