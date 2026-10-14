package com.folio.hermeslive.meeting

import com.folio.hermes.openrouter.FailureKind
import com.folio.hermes.telemetry.TelemetrySink
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId

/**
 * Sends what meeting mode holds: each queued meeting's notes brief, and each in-meeting task.
 *
 * - **Compile at send time**, so a meeting ended offline still goes: the brief is built (chunk
 *   summaries need the writer, so a long one waits for the network) and **stored before it is
 *   submitted**: a retry sends the same text and never summarises twice.
 * - **Idempotent:** the submit key is the meeting's ([MeetingMeta.requestKey]) or the task's, so
 *   a retry after a kill or a lost reply never starts a second task.
 * - **Held, not lost:** anything but `Sent` leaves it queued with the reason; [flush] tries
 *   again (on start, when the network comes back, when the user asks).
 */
class MeetingOutbox(
    private val log: MeetingLog,
    private val tasks: () -> MeetingTasks,
    private val writer: () -> MeetingWriter,
    private val zone: () -> ZoneId,
    private val wall: () -> Long,
    private val telemetry: TelemetrySink,
) {
    private val lock = Mutex()

    /** What a send did, for the screen. */
    sealed interface Outcome {
        data class Sent(val taskId: String) : Outcome

        /** Held on the phone: [reason] `offline`, `bill_off`, `signed_out`, `no_reply`, `not_connected`. */
        data class Held(val reason: String) : Outcome

        data object Missing : Outcome
    }

    /** Send meeting [id] now if it is queued (its stored brief, or a fresh one). */
    suspend fun send(id: String): Outcome = lock.withLock {
        prepare()
        sendLocked(id)
    }

    /**
     * Ask to send meeting [id] (after the meeting, or from history), then try at once. A queued
     * meeting keeps its key and stored brief (its first start may already be in the task table,
     * with the reply lost); only a meeting already **sent** becomes a new request (a new key and a
     * fresh brief, so additions made since count).
     */
    suspend fun queue(id: String): Outcome = lock.withLock {
        prepare()
        val meta = log.meta(id) ?: return@withLock Outcome.Missing
        if (meta.phase.live) return@withLock Outcome.Missing
        if (meta.phase != MeetingPhase.Queued) {
            log.putBrief(id, null)
            log.put(meta.copy(phase = MeetingPhase.Queued, queuedAt = wall(), sends = meta.sends + 1, taskId = null, heldReason = null))
        }
        sendLocked(id)
    }

    /** Before any meeting is read: the task side may wipe the store for another account. */
    private suspend fun prepare() = runCatching { tasks().prepare() }

    /** Try everything held: queued meetings, then tasks Hermes has not taken. */
    suspend fun flush(): Int = lock.withLock {
        prepare()
        var sent = 0
        log.all().filter { it.phase == MeetingPhase.Queued }.forEach { if (sendLocked(it.id) is Outcome.Sent) sent++ }
        log.tasks().filter { it.taskId == null }.forEach { if (sendTaskLocked(it) is Outcome.Sent) sent++ }
        sent
    }

    /** Hold and try one in-meeting task. */
    suspend fun task(task: MeetingTask): Outcome = lock.withLock {
        prepare()
        log.putTask(task)
        sendTaskLocked(task)
    }

    private suspend fun sendLocked(id: String): Outcome {
        val meta = log.meta(id) ?: return Outcome.Missing
        if (meta.phase != MeetingPhase.Queued) return meta.taskId?.let { Outcome.Sent(it) } ?: Outcome.Missing
        val started = wall()
        var chunked = false
        var chunks = 1
        val brief = log.brief(id) ?: run {
            val transcript = log.transcript(id)
            val compiled = try {
                MeetingBrief.compile(meta, transcript, zone(), writer())
            } catch (e: MeetingBrief.WriterUnavailable) {
                return hold(meta, if (e.kind == FailureKind.Offline || e.kind == FailureKind.Timeout) "offline" else "writer_" + e.kind.wire)
            }
            chunked = compiled.chunked
            chunks = compiled.chunks
            log.putBrief(id, compiled.brief)
            compiled.brief
        }
        return when (val r = runCatching { tasks().submit(brief, MeetingBrief.label(meta), meta.requestKey) }.getOrElse { MeetingSubmit.NotAvailable("failed") }) {
            is MeetingSubmit.Sent -> {
                log.put(meta.copy(phase = MeetingPhase.Sent, taskId = r.taskId, sentAt = wall(), heldReason = null))
                event("meeting.sent", mapOf("chars" to brief.length, "chunked" to chunked, "chunks" to chunks, "ms" to wall() - started, "queued" to r.queued, "send" to meta.sends))
                Outcome.Sent(r.taskId)
            }
            MeetingSubmit.Offline -> hold(meta, "offline")
            is MeetingSubmit.NotAvailable -> hold(meta, r.reason)
        }
    }

    private fun hold(meta: MeetingMeta, reason: String): Outcome {
        if (meta.heldReason != reason) log.put(meta.copy(heldReason = reason))
        event("meeting.held", mapOf("reason" to reason))
        return Outcome.Held(reason)
    }

    private suspend fun sendTaskLocked(task: MeetingTask): Outcome {
        task.taskId?.let { return Outcome.Sent(it) }
        val r = runCatching { tasks().submit(task.brief, task.label, task.key) }.getOrElse { MeetingSubmit.NotAvailable("failed") }
        return when (r) {
            is MeetingSubmit.Sent -> {
                log.putTask(task.copy(taskId = r.taskId, heldReason = null))
                event("meeting.task_sent", mapOf("queued" to r.queued))
                Outcome.Sent(r.taskId)
            }
            MeetingSubmit.Offline -> Outcome.Held("offline").also { log.putTask(task.copy(heldReason = "offline")) }
            is MeetingSubmit.NotAvailable -> Outcome.Held(r.reason).also { log.putTask(task.copy(heldReason = r.reason)) }
        }
    }

    private fun event(name: String, attrs: Map<String, Any?>) = runCatching { telemetry.event(name, attrs, false) }
}
