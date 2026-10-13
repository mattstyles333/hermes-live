package com.folio.hermes.cascade.routing

import com.folio.hermes.cascade.AppLines
import com.folio.hermes.cascade.Tx
import com.folio.hermes.cascade.writer.WriterPrompts

/** Jev was not sure (R-RT-3): ask one short question, never act. */
object ClarifyHandler : RouteHandler {
    override fun start(tx: Tx): Next {
        val a = tx.action
        if (a.decision.writer) {
            return tx.write(WriterPrompts.spoken(a.line, 1), WriterPrompts.clarify(a.text, tx.recentExchanges(), tx.briefs()), AppLines.SAY_AGAIN)
        }
        if (a.slot == null) tx.play(AppLines.SAY_AGAIN)
        return Next.Done
    }
}
