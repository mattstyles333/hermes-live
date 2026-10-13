package com.folio.hermes.cascade.routing

import com.folio.hermes.cascade.AppLines
import com.folio.hermes.cascade.CancelResult
import com.folio.hermes.cascade.Route
import com.folio.hermes.cascade.Tx
import com.folio.hermes.cascade.writer.WriterPrompts

/** `cancel_task` (R-TASK-4): `session.interrupt`, then a short confirmation once it returns. */
object CancelTaskHandler : RouteHandler {
    override fun start(tx: Tx): Next {
        val a = tx.action
        val route = a.decision.route as Route.CancelTask
        return when (tx.tasks(tx.s.tasks.cancel(route.taskId, tx.s.wallMs))) {
            CancelResult.NotFound -> {
                tx.play(AppLines.WHICH_CANCEL)
                Next.Done
            }
            CancelResult.AlreadyFinished -> {
                if (a.slot != "cancel.too_late") tx.play("cancel.too_late")
                Next.Done
            }
            CancelResult.Cancelled -> {
                tx.updateAction { it.copy(waitingOn = "interrupt:${route.taskId}") }
                Next.Wait("interrupt")
            }
        }
    }

    override fun resume(tx: Tx, result: ActionResult): Next {
        val a = tx.action
        val route = a.decision.route as Route.CancelTask
        val label = tx.s.tasks[route.taskId]?.label.orEmpty()
        if (a.decision.writer) return tx.write(WriterPrompts.spoken(a.line, 1), WriterPrompts.cancelled(label), null)
        if (a.slot == null) tx.play("cancel.one")
        return Next.Done
    }
}
