package com.folio.hermes.cascade.store

import com.folio.hermes.cascade.Effect
import com.folio.hermes.cascade.HeldTurn
import com.folio.hermes.cascade.MemoSession
import com.folio.hermes.cascade.OffCallState
import com.folio.hermes.cascade.OutboxEntry
import com.folio.hermes.cascade.OutboxState
import com.folio.hermes.cascade.SendState
import com.folio.hermes.cascade.State
import com.folio.hermes.cascade.TaskEvent
import com.folio.hermes.cascade.TaskRecord
import com.folio.hermes.cascade.TaskResult
import com.folio.hermes.cascade.TaskState
import com.folio.hermes.cascade.TaskTable
import com.folio.hermes.cascade.ports.Store

/**
 * The real durability seam (R-DUR-1..5, R-CONV-1, R-OFF-3) over the [Store] port.
 *
 * [save] (pure, every step) writes only what changed: each changed task under its own key first,
 * then [StoreKeys.CORE] (outbox, counters, conversation, memo sessions, off-call results), then
 * the call start/end times. The actor runs these before any network effect of the step (R-DUR-1).
 * Nothing is written until the first restore, so an actor that never started never overwrites the store.
 *
 * [restore] has two modes:
 * - **First load** in this actor (no conversation yet): rebuild from the [Store] and recover from a
 *   crash. A task left `Sending` with no stored session is marked `NotSent` (it may or may not have
 *   reached Hermes, so it is never sent again on its own: "Not sent: tap to retry"; R-DUR-4).
 *   A task left running with no stored session is `Failed` (retry, R-TASK-7). A task bound to a
 *   stored session keeps its runs, and [com.folio.hermes.cascade.registry.TaskRecovery] re-binds and
 *   recovers it. A held turn whose task exists belongs to the registry, so it is not sent again.
 * - **Later starts** (same actor, next call): memory is the truth; [fresh] is returned as is.
 *
 * Restore also prunes: finished, read tasks beyond [KEEP_FINISHED] and ended conversations beyond
 * `conversationsKept - 1` are deleted from the store (the only writes outside `save`).
 */
object DurablePersistence : StatePersistence {
    /** Finished, read tasks kept across restarts (running, unheard, waiting and not-sent tasks are always kept). */
    const val KEEP_FINISHED = 30

    override fun save(before: State, after: State): List<Effect> {
        if (after.conversation.id == null) return emptyList()
        val firstWrite = before.conversation.id == null
        val out = mutableListOf<Effect>()
        if (firstWrite || before.tasks !== after.tasks) {
            val old = before.tasks.tasks.associateBy { it.id }
            for (t in after.tasks.tasks) {
                val o = old[t.id]
                if (o === t && !firstWrite) continue
                val dto = StoreCodec.task(t)
                if (!firstWrite && o != null && StoreCodec.task(o) == dto) continue
                out += Effect.Persist(StoreKeys.task(t.id), StoreCodec.encode(TaskDto.serializer(), dto))
            }
            val kept = after.tasks.tasks.map { it.id }.toSet()
            old.keys.filter { it !in kept }.forEach { out += Effect.Persist(StoreKeys.task(it), null) }
        }
        if (firstWrite || !Snapshots.sameCore(before, after)) {
            val core = Snapshots.core(after)
            if (firstWrite || Snapshots.core(before) != core) out += Effect.Persist(StoreKeys.CORE, StoreCodec.encode(CoreDto.serializer(), core))
        }
        if (!before.started && after.started) out += Effect.Persist(StoreKeys.CALL_START, after.wallMs.toString())
        if (before.started && !after.started) out += Effect.Persist(StoreKeys.CALL_END, after.wallMs.toString())
        return out
    }

    override fun restore(store: Store, fresh: State): State {
        if (fresh.conversation.id != null) return fresh
        val core = StoreCodec.decode(CoreDto.serializer(), store.get(StoreKeys.CORE)) ?: CoreDto()
        val wall = fresh.wallMs
        val stored = store.keys(StoreKeys.TASK_PREFIX).mapNotNull { StoreCodec.decodeTask(store.get(it)) }
        val tasks = prune(store, stored.map { recover(it, wall) }.sortedWith(compareBy({ idNumber(it.id) }, { it.startedAt })))
        val owned = tasks.mapNotNull { it.turnId }.toSet()

        val outbox = core.outbox
        val held = outbox.held.filter { it.turnId !in owned }.distinctBy { it.turnId }
        val sent = (outbox.sent + outbox.awaiting + outbox.held.map { it.turnId }.filter { it in owned }).distinct().takeLast(OutboxState.KEEP)
        val box = OutboxState(
            held = held.map { OutboxEntry(it.turnId, HeldTurn(it.text, it.speechEndAt, it.wallMs)) },
            sent = sent,
            stages = outbox.stages.mapValues { (_, v) -> v.toSet() },
        )
        val heldIds = box.held.map { it.turnId }.toSet()
        val memo = core.memo.map { MemoSession(it.id, it.startedWallMs, it.endedWallMs, it.turnIds.filter { t -> t in heldIds }, it.speechMs) }
            .filter { it.turnIds.isNotEmpty() }
        val byId = tasks.associateBy { it.id }
        val offCall = core.offCall.mapNotNull { o -> byId[o.taskId]?.let { TaskResult(it.id, it.label, it.result.orEmpty(), o.ok) } }

        val turnIds = box.held.map { it.turnId } + box.sent + box.stages.keys + owned
        val turns = maxOf(core.turns, turnIds.maxOfOrNull(::idNumber) ?: 0, fresh.turn.turns)
        val table = TaskTable(
            tasks = tasks,
            counter = maxOf(core.counter, tasks.maxOfOrNull { idNumber(it.id) } ?: 0, fresh.tasks.counter),
            runCounter = maxOf(core.runCounter, stored.flatMap { it.runs.keys }.maxOrNull() ?: 0L, fresh.tasks.runCounter),
        )
        trimConversations(store, fresh.settings.conversationsKept, core.conversation?.id)
        val conversation = core.conversation?.takeIf { it.id.isNotEmpty() }
        val base = fresh.copy(
            turn = fresh.turn.copy(turns = turns),
            tasks = table,
            outbox = box,
            ceo = fresh.ceo.copy(memoSessions = memo),
            offCall = OffCallState(results = offCall),
        )
        return Recent.seed(base, conversation?.id ?: Snapshots.conversationId(wall), conversation?.summary.orEmpty(), conversation?.recent.orEmpty())
    }

    /** Crash recovery for one stored task (first load only). */
    private fun recover(t: TaskRecord, wall: Long): TaskRecord {
        if (t.state != TaskState.Running || (t.storedSessionId != null && t.runs.isNotEmpty())) return t
        val stopped = t.copy(state = TaskState.Failed, active = 0, runs = emptyMap(), lastFailed = true, finishedAt = wall, currentTool = null, queuedAtServer = false)
            .log(TaskEvent.Failed(wall))
        // Sent or not, nobody can tell: never resent on its own (no double submit); the card offers a retry.
        return if (t.send == SendState.Sending) stopped.copy(send = SendState.NotSent) else stopped
    }

    private fun prune(store: Store, tasks: List<TaskRecord>): List<TaskRecord> {
        val keep = tasks.filter { it.state == TaskState.Running || it.unread || it.waiting || it.send == SendState.NotSent }.map { it.id }.toSet()
        val rest = tasks.filter { it.id !in keep }.sortedByDescending { it.finishedAt ?: it.startedAt }
        val drop = rest.drop(KEEP_FINISHED).map { it.id }.toSet()
        drop.forEach { store.put(StoreKeys.task(it), null) }
        return tasks.filter { it.id !in drop }
    }

    private fun trimConversations(store: Store, kept: Int, current: String?) {
        val ended = store.keys(StoreKeys.CONVERSATION_PREFIX).filter { it != current?.let(StoreKeys::conversation) }
            .sortedByDescending { Snapshots.startedWallMs(it.removePrefix(StoreKeys.CONVERSATION_PREFIX)) }
        ended.drop((kept - 1).coerceAtLeast(0)).forEach { store.put(it, null) }
    }

    /** `t12` → 12, `u7` → 7; anything else 0. */
    fun idNumber(id: String): Int = id.drop(1).toIntOrNull() ?: 0
}
