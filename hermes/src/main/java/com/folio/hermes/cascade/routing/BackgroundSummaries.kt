package com.folio.hermes.cascade.routing

import com.folio.hermes.cascade.Effect
import com.folio.hermes.cascade.Tx
import com.folio.hermes.cascade.writer.Summaries
import com.folio.hermes.openrouter.OpenRouterResult

/**
 * R-CTX-2 off the critical path: the conversation summary is a whole-writer request that no action
 * waits on; its answer lands in `State.conversation.summary` whenever it comes.
 *
 * Off by default ([enabled] false): today the reducer drops a `WriterWrote` that no action waits
 * for, so a request would be wasted. It needs one hub line routing an unmatched `WriterWrote` to
 * [wrote] (see the slice report); then S-F turns it on.
 */
class BackgroundSummaries(val enabled: Boolean = false) {
    /** After a committed turn: ask for a fresh conversation summary when it is due. */
    fun afterTurn(tx: Tx) {
        if (!enabled || !Summaries.conversationDue(tx.s.turn.turns)) return
        if (tx.marks(KEY).isNotEmpty()) return // one at a time
        val seq = tx.nextSeq()
        val tasks = tx.s.tasks.tasks.takeLast(8).map { "${it.label} (${it.state.wire})" }
        tx.effects += Effect.Write(seq, Summaries.SYSTEM, Summaries.conversation(tx.s.conversation.summary, tx.recentExchanges(), tasks, tx.s.settings.conversationSummaryChars))
        tx.mark(KEY, "conversation:$seq")
    }

    /** A whole-writer answer no action waited for. True if it was a summary (stored); false: not ours. */
    fun wrote(tx: Tx, seq: Long, result: OpenRouterResult<String>): Boolean {
        val mark = "conversation:$seq"
        if (!tx.marked(KEY, mark)) return false
        tx.unmark(KEY, mark)
        val text = result.valueOrNull()?.takeIf { it.isNotBlank() } ?: return true
        tx.s = tx.s.copy(conversation = tx.s.conversation.copy(summary = Summaries.clamp(text, tx.s.settings.conversationSummaryChars)))
        return true
    }

    private companion object {
        const val KEY = "bg:summary"
    }
}
