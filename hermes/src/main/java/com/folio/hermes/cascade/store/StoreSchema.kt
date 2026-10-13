package com.folio.hermes.cascade.store

import com.folio.hermes.HermesJson
import com.folio.hermes.TurnStatus
import com.folio.hermes.cascade.RunKind
import com.folio.hermes.cascade.SendState
import com.folio.hermes.cascade.TaskEvent
import com.folio.hermes.cascade.TaskRecord
import com.folio.hermes.cascade.TaskRun
import com.folio.hermes.cascade.TaskState
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/*
 * The cascade's durable schema (R-DUR, R-CONV-1, R-OFF-3). Every value is JSON with a version
 * field `v`. Keys are stable and contain no `/`, so a file-backed `Store` can use them as names.
 *
 * Compatibility: decoding ignores unknown fields (a newer app's fields never break an older
 * load), every field has a default (an older value loads in a newer app), enums are stored by
 * wire name and an unknown name falls back, and a value that does not parse is skipped, never thrown.
 */

/** The `Store` keys. */
object StoreKeys {
    /** Outbox, table counters, turn counter, the current conversation, memo sessions, off-call results. */
    const val CORE = "cascade.core"

    /** One task per key: `cascade.task.<taskId>`. Written before [CORE] in a step. */
    const val TASK_PREFIX = "cascade.task."

    /** One ended conversation per key (history): `cascade.conv.<conversationId>`. */
    const val CONVERSATION_PREFIX = "cascade.conv."

    /** Wall time the last call started / ended (decimal). */
    const val CALL_START = "cascade.call.start"
    const val CALL_END = "cascade.call.end"

    fun task(id: String): String = TASK_PREFIX + id

    fun conversation(id: String): String = CONVERSATION_PREFIX + id
}

/** The schema version written into every value. Bump it when a field changes meaning (not when one is added). */
const val STORE_VERSION: Int = 1

@Serializable
data class HeldDto(val turnId: String, val text: String = "", val speechEndAt: Long = 0L, val wallMs: Long = 0L, val carried: Boolean = false)

/**
 * The durable outbox. [held] is every turn not yet carried out: held offline, or committed and
 * still being acted on (R-DUR-1). [awaiting] are turns whose task was submitted but not yet
 * `Accepted` (the outbox clears a turn only after `Accepted`; R-DUR-2). [sent] never come back.
 */
@Serializable
data class OutboxDto(
    val held: List<HeldDto> = emptyList(),
    val awaiting: List<String> = emptyList(),
    val sent: List<String> = emptyList(),
    val stages: Map<String, List<String>> = emptyMap(),
)

@Serializable
data class ExchangeDto(val user: String = "", val bill: String = "")

/** A conversation (R-CONV-1): the current one in [CoreDto], ended ones under [StoreKeys.conversation]. */
@Serializable
data class ConversationDto(
    val v: Int = STORE_VERSION,
    val id: String = "",
    val startedWallMs: Long = 0L,
    val endedWallMs: Long? = null,
    val summary: String = "",
    val recent: List<ExchangeDto> = emptyList(),
    val taskIds: List<String> = emptyList(),
    val unheard: List<String> = emptyList(),
    val heldTurnIds: List<String> = emptyList(),
    val sessionIds: List<String> = emptyList(),
)

@Serializable
data class MemoDto(val id: String, val startedWallMs: Long = 0L, val endedWallMs: Long = 0L, val turnIds: List<String> = emptyList(), val speechMs: Long = 0L)

@Serializable
data class OffCallDto(val taskId: String, val ok: Boolean = true)

@Serializable
data class CoreDto(
    val v: Int = STORE_VERSION,
    val outbox: OutboxDto = OutboxDto(),
    /** Turn ids are `u<n>`: the counter survives restarts so a new turn never reuses an id. */
    val turns: Int = 0,
    val counter: Int = 0,
    val runCounter: Long = 0L,
    val conversation: ConversationDto? = null,
    val memo: List<MemoDto> = emptyList(),
    val offCall: List<OffCallDto> = emptyList(),
    /** `State.marks`: what slices remember across events (summaries spoken, lines awaiting `Accepted`). */
    val marks: Map<String, List<String>> = emptyMap(),
)

/** One task-log entry. [t] is the kind; the other fields depend on it. */
@Serializable
data class EventDto(val t: String, val at: Long = 0L, val s: String? = null, val label: String? = null, val done: Boolean? = null)

@Serializable
data class RunDto(
    val id: Long,
    val kind: String = RunKind.Start.how,
    val pending: Boolean = false,
    val turnId: String? = null,
    /** Kept until Hermes accepts the run, so a restart can send it again if it never arrived. */
    val text: String? = null,
    val accepted: Boolean = false,
    /** The session was being created: the prompt had not left. */
    val opening: Boolean = false,
)

/** One task (R-DUR-3): enough to answer status, re-bind its session and recover or retry it. */
@Serializable
data class TaskDto(
    val v: Int = STORE_VERSION,
    val id: String,
    val label: String = "",
    val startedAt: Long = 0L,
    val state: String = TaskState.Running.wire,
    val cancelled: Boolean = false,
    val active: Int = 0,
    val lastFailed: Boolean = false,
    val events: List<EventDto> = emptyList(),
    val result: String? = null,
    val unread: Boolean = false,
    val waiting: Boolean = false,
    val accepted: Boolean = false,
    val queuedAtServer: Boolean = false,
    val finishedAt: Long? = null,
    val steps: Int = 0,
    val pendingUpdates: Int = 0,
    val runs: List<RunDto> = emptyList(),
    val brief: String? = null,
    val summary: String? = null,
    val turnId: String? = null,
    val storedSessionId: String? = null,
    val send: String = SEND_SENDING,
    val submitAttempts: Int = 0,
    val offline: Boolean = false,
    val queued: Boolean = false,
)

private const val SEND_SENDING = "sending"

/** Encode and decode the durable values. Decoding never throws: a bad value is null. */
object StoreCodec {
    fun <T> encode(serializer: KSerializer<T>, value: T): String = HermesJson.encodeToString(serializer, value)

    fun <T> decode(serializer: KSerializer<T>, text: String?): T? =
        text?.let { runCatching { HermesJson.decodeFromString(serializer, it) }.getOrNull() }

    fun encodeTask(task: TaskRecord): String = encode(TaskDto.serializer(), task(task))

    fun decodeTask(text: String?): TaskRecord? = decode(TaskDto.serializer(), text)?.let(::record)

    // ── task ↔ dto ──────────────────────────────────────────────────────────────────────────

    /** The stored form of [t]. Streaming progress, the tool timeline and the live tool are not stored. */
    fun task(t: TaskRecord): TaskDto = TaskDto(
        id = t.id,
        label = t.label,
        startedAt = t.startedAt,
        state = t.state.wire,
        cancelled = t.cancelled,
        active = t.active,
        lastFailed = t.lastFailed,
        events = t.events.map(::event),
        result = t.result,
        unread = t.unread,
        waiting = t.waiting,
        accepted = t.accepted,
        queuedAtServer = t.queuedAtServer,
        finishedAt = t.finishedAt,
        steps = t.steps,
        pendingUpdates = t.pendingUpdates,
        runs = t.runs.entries.sortedBy { it.key }.map { (id, run) -> RunDto(id, run.kind.how, run.pending, run.turnId, run.text, run.accepted, run.opening) },
        brief = t.brief,
        summary = t.summary,
        turnId = t.turnId,
        storedSessionId = t.storedSessionId,
        send = sendWire(t.send),
        submitAttempts = t.submitAttempts,
        offline = t.offline,
        queued = t.queued,
    )

    fun record(d: TaskDto): TaskRecord {
        val events = d.events.mapNotNull(::event)
        return TaskRecord(
            id = d.id,
            label = d.label,
            startedAt = d.startedAt,
            state = TaskState.entries.firstOrNull { it.wire == d.state } ?: TaskState.Failed,
            cancelled = d.cancelled,
            active = d.active.coerceAtLeast(0),
            lastFailed = d.lastFailed,
            events = events,
            result = d.result,
            unread = d.unread,
            waiting = d.waiting,
            accepted = d.accepted,
            queuedAtServer = d.queuedAtServer,
            finishedAt = d.finishedAt,
            steps = d.steps,
            lastEventAt = events.lastOrNull()?.atMs ?: d.startedAt,
            pendingUpdates = d.pendingUpdates,
            runs = d.runs.associate { r ->
                r.id to TaskRun(RunKind.entries.firstOrNull { it.how == r.kind } ?: RunKind.Start, r.pending, r.turnId, r.text, r.accepted, r.opening)
            },
            brief = d.brief,
            summary = d.summary,
            turnId = d.turnId,
            storedSessionId = d.storedSessionId,
            send = when (d.send) {
                "accepted" -> SendState.Accepted
                "not_sent" -> SendState.NotSent
                else -> SendState.Sending
            },
            submitAttempts = d.submitAttempts,
            offline = d.offline,
            queued = d.queued,
        )
    }

    private fun sendWire(s: SendState): String = when (s) {
        SendState.Sending -> SEND_SENDING
        SendState.Accepted -> "accepted"
        SendState.NotSent -> "not_sent"
    }

    private fun event(e: TaskEvent): EventDto = when (e) {
        is TaskEvent.Submitted -> EventDto("submitted", e.atMs, s = e.how)
        is TaskEvent.Started -> EventDto("started", e.atMs)
        is TaskEvent.Tool -> EventDto("tool", e.atMs, s = e.summary, label = e.label, done = e.done)
        is TaskEvent.Interim -> EventDto("interim", e.atMs, s = e.text)
        is TaskEvent.Waiting -> EventDto("waiting", e.atMs, s = e.what)
        is TaskEvent.Finished -> EventDto("finished", e.atMs, s = e.status.name.lowercase())
        is TaskEvent.Failed -> EventDto("failed", e.atMs)
        is TaskEvent.Cancelled -> EventDto("cancelled", e.atMs)
    }

    /** An unknown kind (from a newer app) is dropped, not thrown. */
    private fun event(d: EventDto): TaskEvent? = when (d.t) {
        "submitted" -> TaskEvent.Submitted(d.at, d.s ?: RunKind.Start.how)
        "started" -> TaskEvent.Started(d.at)
        "tool" -> TaskEvent.Tool(d.at, d.label.orEmpty(), d.done == true, d.s)
        "interim" -> TaskEvent.Interim(d.at, d.s.orEmpty())
        "waiting" -> TaskEvent.Waiting(d.at, d.s.orEmpty())
        "finished" -> TaskEvent.Finished(d.at, TurnStatus.entries.firstOrNull { it.name.lowercase() == d.s } ?: TurnStatus.Complete)
        "failed" -> TaskEvent.Failed(d.at)
        "cancelled" -> TaskEvent.Cancelled(d.at)
        else -> null
    }
}
