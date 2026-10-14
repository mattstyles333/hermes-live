package com.folio.hermes.cascade.routing

import com.folio.hermes.cascade.AppLines
import com.folio.hermes.cascade.Tx

/** Nothing usable: "say that again?", nothing else. */
object AskRepeatHandler : RouteHandler {
    override fun start(tx: Tx): Next {
        tx.play(AppLines.SAY_AGAIN)
        return Next.Done
    }
}
