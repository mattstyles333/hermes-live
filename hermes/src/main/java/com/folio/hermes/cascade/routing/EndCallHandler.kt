package com.folio.hermes.cascade.routing

import com.folio.hermes.cascade.AppLines
import com.folio.hermes.cascade.Tx

/** `end_call`: goodbye (mentioning running tasks); the reducer then asks the app to end the call. */
object EndCallHandler : RouteHandler {
    override fun start(tx: Tx): Next {
        val running = tx.s.tasks.runningCount > 0
        if (tx.action.slot == null) tx.play(if (running) AppLines.BYE_TASKS else AppLines.BYE)
        return Next.Done
    }
}
