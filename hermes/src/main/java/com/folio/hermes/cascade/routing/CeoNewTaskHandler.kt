package com.folio.hermes.cascade.routing

import com.folio.hermes.cascade.HeldTurn
import com.folio.hermes.cascade.Tx
import com.folio.hermes.cascade.writer.BriefSet
import com.folio.hermes.cascade.writer.Briefs
import com.folio.hermes.openrouter.FailureKind
import com.folio.hermes.openrouter.OpenRouterResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * `new_task` in CEO mode (R-TASK-2, spec v2 §0 ordering):
 * 1. the `start.*` line plays at once (the reducer plays the decision's slot first); with
 *    `key_doubt` Bill first asks one question and waits ([KeyDoubt]);
 * 2. the writer returns briefs (one call per 20,000-character chunk above 24,000; R-CTX-3);
 * 3. each brief becomes a task: up to `maxBriefsPerTurn` start now, the rest queue ([TaskQueue]);
 * 4. the spoken summaries wait for Hermes's `Accepted` ([Confirmations]); a failed submit is
 *    always spoken.
 * A resend never starts the same turn's tasks twice (R-DUR-2). The writer offline holds the turn
 * in the outbox (R-OFF-5); any other writer failure still starts the task, with the user's own
 * words as the brief.
 */
class CeoNewTaskHandler(private val memo: MemoRouting) : RouteHandler {
    private val chunkKey = "chunk"
    private val accKey = "briefs"

    override fun playsSlotFirst(tx: Tx): Boolean = !KeyDoubt.needed(tx, tx.action) && !MemoRouting.isMemo(tx, tx.action.turnId)

    override fun start(tx: Tx): Next {
        val a = tx.action
        if (tx.s.outbox.stageDone(a.turnId, TASK_STAGE) || tx.env.registry.taskForTurn(tx.s, a.turnId) != null) return Next.Done
        if (KeyDoubt.needed(tx, a)) return KeyDoubt.ask(tx)
        return writeChunk(tx, 0)
    }

    override fun resume(tx: Tx, result: ActionResult): Next {
        val a = tx.action
        return when (result) {
            is ActionResult.Written -> KeyDoubt.afterQuestion(tx, result.spoke) ?: proceed(tx)
            is ActionResult.TimerFired -> {
                KeyDoubt.waited(tx)
                if (tx.action.data[KeyDoubt.STATE] == KeyDoubt.DROPPED) {
                    tx.play("cancel.drop")
                    Next.Done
                } else {
                    proceed(tx)
                }
            }
            is ActionResult.Wrote -> {
                val r = result.result
                if (r is OpenRouterResult.Failed && r.kind == FailureKind.Offline && !a.resend) {
                    tx.holdOffline(a.turnId, HeldTurn(a.text, a.speechEndAt, tx.s.wallMs, BriefPlanner.transcript(tx, a)))
                    return Next.Done
                }
                val chunk = a.data[chunkKey]?.toIntOrNull() ?: 0
                val acc = merge(a.data[accKey], BriefPlanner.parse(r.valueOrNull()))
                tx.updateAction { it.copy(data = it.data + (accKey to acc)) }
                val chunks = chunksOf(tx)
                if (chunk + 1 < chunks.size) writeChunk(tx, chunk + 1) else place(tx, decode(acc))
            }
            else -> Next.Done
        }
    }

    /** After a key-doubt question: the start line now (it did not play first), then the briefs. */
    private fun proceed(tx: Tx): Next {
        tx.action.decision.slot?.let { if (!MemoRouting.isMemo(tx, tx.action.turnId)) tx.play(it) }
        return writeChunk(tx, 0)
    }

    private fun chunksOf(tx: Tx): List<String> =
        BriefPlanner.chunks(tx.action.text, tx.s.settings.writerChunkAboveChars, tx.s.settings.writerChunkChars)

    private fun writeChunk(tx: Tx, index: Int): Next {
        val a = tx.action
        val chunks = chunksOf(tx)
        val carried = decode(a.data[accKey])?.briefs?.map { it.label }.orEmpty()
        tx.updateAction { it.copy(data = it.data + (chunkKey to index.toString()), writerRan = true) }
        return tx.requestWrite(Briefs.SYSTEM, Briefs.prompt(BriefPlanner.request(tx, a, chunks[index], index, chunks.size, carried)))
    }

    private fun place(tx: Tx, set: BriefSet?): Next {
        val a = tx.action
        val offline = memo.offlineNote(tx, a.turnId)
        val planned = BriefPlanner.plan(tx, a, set, offline)
        val placed = TaskQueue.add(tx, planned, a.turnId, offline = offline != null)
        tx.s = tx.s.copy(outbox = tx.s.outbox.markStage(a.turnId, TASK_STAGE))
        if (MemoRouting.isMemo(tx, a.turnId)) {
            (placed.started + placed.queued).forEach { Confirmations.silence(tx, a.turnId, it) }
            memo.record(tx, a.turnId, "new_task", placed.started.size + placed.queued.size)
        } else if (placed.started.isEmpty() && placed.queued.isNotEmpty()) {
            // Nothing reaches Hermes yet, so there is no Accepted to wait for: say it is queued now.
            tx.play(Confirmations.QUEUED)
            placed.queued.forEach { tx.mark(a.turnId, "queued:$it") }
        }
        return Next.Done
    }

    /** Briefs gathered over chunks, as a small JSON value in the action's scratch data. */
    private fun merge(acc: String?, next: BriefSet?): String {
        val prev = decode(acc)
        val briefs = prev?.briefs.orEmpty() + next?.briefs.orEmpty()
        return buildJsonObject {
            putJsonArray("briefs") {
                briefs.forEach { b -> add(buildJsonObject { put("brief", b.brief); put("label", b.label); b.summary?.let { put("summary", it) } }) }
            }
            (next?.repaired ?: prev?.repaired)?.let { put("repaired", it) }
            listOfNotNull(prev?.notes, next?.notes).filter { it.isNotBlank() }.joinToString(" ").takeIf { it.isNotEmpty() }?.let { put("notes", it) }
            put("complete", (prev?.complete ?: true) && (next?.complete ?: false))
        }.toString()
    }

    private fun decode(acc: String?): BriefSet? {
        val o = acc?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: return null
        val set = Briefs.parse(o.toString()) ?: return null
        val complete = (o["complete"] as? JsonPrimitive)?.contentOrNull != "false"
        return set.copy(complete = complete)
    }

    companion object {
        /** The outbox stage that says this turn's tasks exist (shared with the C1 handler). */
        const val TASK_STAGE = "task"
    }
}
