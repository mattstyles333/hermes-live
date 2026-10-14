package com.folio.hermes.cascade.routing

import com.folio.hermes.cascade.ActionState
import com.folio.hermes.cascade.CancelResult
import com.folio.hermes.cascade.Route
import com.folio.hermes.cascade.Tx

/**
 * "Stop everything" (hub decision 2026-10-14): every running and queued task stops, with no
 * "which one?". Queued tasks are dropped first, so cancelling a running one (which frees a slot
 * and pumps the queue) never launches a task that is about to be dropped. The `cancel.all` line
 * plays first (the reducer plays the decision's slot); once every interrupt is back Bill adds
 * the count ("That's all three."). The interrupts go out together and come back in any order:
 * each one is marked on the turn as it arrives ([interrupted]), and the action waits on one
 * still out until none is.
 */
internal object CancelAll {
    private const val COUNT = "cancelled"
    private const val AWAIT = "await"
    private const val BACK = "interrupted:"

    fun start(tx: Tx): Next {
        val targets = CeoRouter.candidates(CascadeBrain.CANCEL_TASK, tx.s.tasks.tasks).sortedByDescending { TaskQueue.isQueued(it) }
        var count = 0
        val interrupting = mutableListOf<String>()
        for (t in targets) {
            val queued = TaskQueue.isQueued(t)
            if (CeoTasks.cancel(tx, t.id) == CancelResult.Cancelled) {
                count++
                if (!queued) interrupting += t.id
            }
        }
        tx.updateAction { it.copy(data = it.data + (COUNT to count.toString()) + (AWAIT to interrupting.joinToString(","))) }
        tx.report("cascade.task", mapOf("event" to "cancel_all", "count" to count, "running" to tx.s.tasks.runningCount))
        return next(tx)
    }

    /** An interrupt came back: remember it on the stop-all turn waiting for it. */
    fun interrupted(tx: Tx, taskId: String) {
        val a = tx.s.actions.active ?: return
        if ((a.decision.route as? Route.CancelTask)?.taskId != CeoRouter.ALL_TASKS) return
        if (taskId in awaited(a)) tx.mark(a.turnId, BACK + taskId)
    }

    /** Wait on an interrupt still out, or confirm once all are back. */
    fun next(tx: Tx): Next {
        val a = tx.action
        val out = awaited(a).firstOrNull { !tx.marked(a.turnId, BACK + it) } ?: return confirm(tx)
        tx.updateAction { it.copy(waitingOn = "interrupt:$out") }
        return Next.Wait("interrupt")
    }

    private fun awaited(a: ActionState): List<String> = a.data[AWAIT].orEmpty().split(',').filter { it.isNotEmpty() }

    fun confirm(tx: Tx): Next {
        val a = tx.action
        val count = a.data[COUNT]?.toIntOrNull() ?: 0
        if (count == 0) {
            tx.play("cancel.too_late")
            return Next.Done
        }
        if (a.slot == null) tx.play(CeoRouter.CANCEL_ALL)
        if (tx.add(a.utteranceId, countLine(count))) tx.updateAction { it.copy(pieces = it.pieces + 1) }
        return Next.Done
    }

    /** "That's both of them." / "That's all three." */
    fun countLine(count: Int): String = when (count) {
        1 -> "That was the only one."
        2 -> "That's both of them."
        else -> "That's all ${WORDS.getOrNull(count) ?: count.toString()}."
    }

    private val WORDS = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve")
}
