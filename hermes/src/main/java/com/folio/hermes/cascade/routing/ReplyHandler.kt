package com.folio.hermes.cascade.routing

import com.folio.hermes.cascade.AppLines
import com.folio.hermes.cascade.Tx
import com.folio.hermes.cascade.writer.WriterPrompts

/** `reply`: the writer answers after the stock line (`error.gave_up` if it fails with nothing said). */
object ReplyHandler : RouteHandler {
    override fun start(tx: Tx): Next {
        val a = tx.action
        if (!a.decision.writer) return Next.Done
        return tx.write(WriterPrompts.spoken(a.line), WriterPrompts.reply(a.text, tx.recentExchanges(), tx.briefs()), AppLines.GAVE_UP)
    }
}
