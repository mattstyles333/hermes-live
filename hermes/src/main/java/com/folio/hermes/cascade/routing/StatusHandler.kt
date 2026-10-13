package com.folio.hermes.cascade.routing

import com.folio.hermes.cascade.Route
import com.folio.hermes.cascade.TaskSnapshot
import com.folio.hermes.cascade.TaskState
import com.folio.hermes.cascade.Tx
import com.folio.hermes.cascade.writer.WriterPrompts

/** `status` (R-TASK-3): from the phone's event log only; never a prompt to a running task. */
object StatusHandler : RouteHandler {
    override fun start(tx: Tx): Next {
        val a = tx.action
        val route = a.decision.route as Route.Status
        val snaps = tx.s.tasks.snapshots()
        if (a.decision.writer) return tx.write(WriterPrompts.spoken(a.line), WriterPrompts.status(snaps, route.taskId), statusSlot(snaps))
        if (a.slot == null) tx.play(statusSlot(snaps))
        return Next.Done
    }

    fun statusSlot(snaps: List<TaskSnapshot>): String = when {
        snaps.none { it.state == TaskState.Running } && snaps.isNotEmpty() && snaps.all { it.state == TaskState.Done } -> "status.all_done"
        snaps.none { it.state == TaskState.Running } -> "status.idle"
        snaps.any { it.waitingOnUser } -> "status.blocked"
        snaps.count { it.state == TaskState.Running } > 1 -> "status.several"
        else -> "status.working"
    }
}
