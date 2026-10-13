package com.folio.hermes.cascade.routing

import com.folio.hermes.cascade.AppLines
import com.folio.hermes.cascade.StartResult
import com.folio.hermes.cascade.Tx
import com.folio.hermes.cascade.writer.WriterPrompts
import com.folio.hermes.openrouter.SentenceSplitter

/**
 * `new_task` (C1): over the cap, `limit.full` and nothing else. Otherwise `ack.do` if no stock
 * line played, the writer plans the task (label, prompt, a spoken confirmation), the task starts,
 * and the confirmation is spoken. A resend never starts the same turn's task twice.
 */
object NewTaskHandler : RouteHandler {
    private const val TASK_STAGE = "task"

    private fun atCap(tx: Tx) = tx.s.tasks.isFull(tx.s.settings.taskCap)

    override fun playsSlotFirst(tx: Tx): Boolean = !atCap(tx)

    override fun start(tx: Tx): Next {
        val a = tx.action
        if (atCap(tx)) {
            tx.play(AppLines.LIMIT_FULL)
            return Next.Done
        }
        // A resend never starts the same turn's task twice (R-DUR-2).
        if (tx.s.outbox.stageDone(a.turnId, TASK_STAGE) || tx.env.registry.taskForTurn(tx.s, a.turnId) != null) return Next.Done
        if (a.slot == null) tx.play("ack.do")
        tx.updateAction { it.copy(writerRan = true) }
        return tx.requestWrite(WriterPrompts.PLAN_SYSTEM, WriterPrompts.plan(a.text, tx.recentExchanges()))
    }

    override fun resume(tx: Tx, result: ActionResult): Next {
        if (result !is ActionResult.Wrote) return Next.Done
        val a = tx.action
        val plan = WriterPrompts.parsePlan(result.result.valueOrNull(), a.text)
        val s = tx.s
        when (tx.tasks(s.tasks.start(plan.prompt, plan.label, tx.voiceContext(), s.settings.taskCap, s.wallMs, a.turnId))) {
            is StartResult.AtCap -> tx.play(AppLines.LIMIT_FULL)
            is StartResult.Started -> {
                tx.s = tx.s.copy(outbox = tx.s.outbox.markStage(a.turnId, TASK_STAGE))
                if (a.decision.writer && plan.say.isNotEmpty()) {
                    val splitter = SentenceSplitter(leadWords = 0)
                    (splitter.push(plan.say) + splitter.finish()).forEach { sentence ->
                        if (tx.add(a.utteranceId, sentence)) tx.updateAction { it.copy(pieces = it.pieces + 1) }
                    }
                }
            }
        }
        return Next.Done
    }
}
