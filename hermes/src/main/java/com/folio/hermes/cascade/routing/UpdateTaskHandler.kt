package com.folio.hermes.cascade.routing

import com.folio.hermes.cascade.AppLines
import com.folio.hermes.cascade.CascadeEvent
import com.folio.hermes.cascade.Route
import com.folio.hermes.cascade.RunKind
import com.folio.hermes.cascade.TaskTable
import com.folio.hermes.cascade.Tx
import com.folio.hermes.cascade.UpdateMode
import com.folio.hermes.cascade.UpdateResult
import com.folio.hermes.cascade.writer.WriterPrompts

/**
 * `update_task` (C1): `queue` sends `prompt.submit {queued: true}`; `redirect` reads the server's
 * busy mode first ([TaskTable.kindFor]). Then a short confirmation (writer, or the stock line).
 */
object UpdateTaskHandler : RouteHandler {
    override fun start(tx: Tx): Next {
        val route = tx.action.decision.route as Route.UpdateTask
        if (tx.updateSent(tx.action.turnId, route.taskId)) return Next.Done
        when (val r = tx.tasks(tx.s.tasks.reopen(route.taskId, tx.s.settings.taskCap))) {
            UpdateResult.NotFound -> {
                tx.play(AppLines.WHICH_UPDATE)
                return Next.Done
            }
            is UpdateResult.AtCap -> {
                tx.play(AppLines.LIMIT_FULL)
                return Next.Done
            }
            else -> Unit
        }
        return if (route.mode == UpdateMode.Queue) send(tx, route, RunKind.Queue) else tx.readBusyMode()
    }

    override fun resume(tx: Tx, result: ActionResult): Next {
        if (result !is ActionResult.Busy) return Next.Done
        val route = tx.action.decision.route as Route.UpdateTask
        return send(tx, route, TaskTable.kindFor(route.mode, result.mode))
    }

    private fun send(tx: Tx, route: Route.UpdateTask, kind: RunKind): Next {
        val a = tx.action
        // Exactly once per turn (R-DUR-2): a resend of this turn finds its update already sent.
        if (tx.launchUpdate(route.taskId, a.text, kind, a.turnId) != null) {
            tx.emit(CascadeEvent.UpdateSent(route.taskId, route.mode))
            tx.report("cascade.task", mapOf("event" to "update", "running" to tx.s.tasks.runningCount, "how" to kind.how))
        }
        val label = tx.s.tasks[route.taskId]?.label.orEmpty()
        if (a.decision.writer) return tx.write(WriterPrompts.spoken(a.line, 1), WriterPrompts.updated(label, route.mode), null)
        if (a.slot == null) tx.play(if (route.mode == UpdateMode.Queue) "update.queued" else "update.redirect")
        return Next.Done
    }
}
