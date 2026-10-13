package com.folio.hermes.cascade.routing

import com.folio.hermes.cascade.AppLines
import com.folio.hermes.cascade.Route
import com.folio.hermes.cascade.Tx
import com.folio.hermes.cascade.writer.WriterPrompts

/** `read_result`: the queued result (if any) is dropped and the writer reads it now. */
object ReadResultHandler : RouteHandler {
    override fun start(tx: Tx): Next {
        val a = tx.action
        val route = a.decision.route as Route.ReadResult
        val snap = tx.s.tasks.snapshot(route.taskId)
        val result = snap?.result
        if (snap == null || result == null) {
            if (a.slot == null) tx.play("status.working")
            return Next.Done
        }
        tx.s.resultUtterances[route.taskId]?.let(tx::drop)
        tx.s = tx.s.copy(resultUtterances = tx.s.resultUtterances - route.taskId, tasks = tx.s.tasks.markRead(route.taskId))
        return tx.write(WriterPrompts.spoken(a.line), WriterPrompts.result(snap.label, result), AppLines.ON_SCREEN)
    }
}
