package com.folio.hermes.cascade.routing

import com.folio.hermes.cascade.AppLines
import com.folio.hermes.cascade.CancelResult
import com.folio.hermes.cascade.CascadeEvent
import com.folio.hermes.cascade.RunKind
import com.folio.hermes.cascade.SpeechKind
import com.folio.hermes.cascade.TaskState
import com.folio.hermes.cascade.Tx
import com.folio.hermes.cascade.UpdateMode
import com.folio.hermes.cascade.writer.Speakable

/** Task operations shared by the CEO route handlers and the card taps (R-TASK-4/5/7). */
object CeoTasks {
    /** Outcome of [update]. */
    sealed interface Updated {
        /** Sent as run [runId]; its line waits for `Accepted`. */
        data class Sent(val runId: Long) : Updated

        /** The task had not started: its brief was amended. */
        data object Amended : Updated

        /** This turn's update already went out (a resend): nothing sent again. */
        data object AlreadySent : Updated

        /** The task had failed: it was retried as new task [taskId] (R-TASK-7). */
        data class Retried(val taskId: String) : Updated
        data object NotFound : Updated
        data object AtCap : Updated
    }

    /**
     * Pass [text] to task [id]: a queued task's brief is amended, a failed task is retried with
     * the same brief, a finished one takes it as a follow-up, a running one gets it queued or
     * redirected ([kind]: `Queue`, `Redirect` or `InterruptFirst`).
     */
    fun update(tx: Tx, id: String, text: String, kind: RunKind, turnId: String? = null): Updated {
        val t = tx.s.tasks[id] ?: return Updated.NotFound
        if (turnId != null && (tx.updateSent(turnId, id) || tx.marked(turnId, "$AMENDED$id"))) return Updated.AlreadySent
        if (TaskQueue.isQueued(t) && turnId != null) {
            if (!TaskQueue.amend(tx, id, text)) return Updated.NotFound
            tx.mark(turnId, "$AMENDED$id")
            return Updated.Amended
        }
        if (TaskQueue.isQueued(t)) return if (TaskQueue.amend(tx, id, text)) Updated.Amended else Updated.NotFound
        if (t.state == TaskState.Failed) return retry(tx, id, text)?.let { Updated.Retried(it) } ?: Updated.NotFound
        if (t.state != TaskState.Running) {
            if (TaskQueue.busy(tx.s.tasks) >= tx.s.settings.taskCap) return Updated.AtCap
            tx.tasks(tx.s.tasks.reopen(id, MAX_CAP))
            if (tx.s.tasks[id]?.state != TaskState.Running) return Updated.AtCap
        }
        val runId = tx.launchUpdate(id, text, kind, turnId) ?: return Updated.NotFound
        tx.emit(CascadeEvent.UpdateSent(id, if (kind == RunKind.Queue) UpdateMode.Queue else UpdateMode.Redirect))
        tx.report("cascade.task", mapOf("event" to "update", "running" to tx.s.tasks.runningCount, "how" to kind.how))
        return Updated.Sent(runId)
    }

    /**
     * R-TASK-7: a failed task is retried as a new task with the same brief ([note]: what the
     * user added). Public for the registry's "tap to retry" (S-B). Returns the new task id.
     */
    fun retry(tx: Tx, id: String, note: String? = null): String? {
        val t = tx.s.tasks[id] ?: return null
        val brief = t.brief ?: return null
        val text = if (note.isNullOrBlank()) brief else "$brief\n\nThe user, retrying this after it failed: $note"
        val turnId = "retry:${t.id}:${tx.s.tasks.counter + 1}"
        val placed = TaskQueue.add(tx, listOf(PlannedTask(text, t.label, "Trying the ${t.label} again.")), turnId, offline = t.offline)
        return (placed.started + placed.queued).firstOrNull()
    }

    /** Stop task [id] (R-TASK-4). A queued task is just dropped (nothing was sent). */
    fun cancel(tx: Tx, id: String): CancelResult {
        if (TaskQueue.drop(tx, id)) return CancelResult.Cancelled
        val r = tx.tasks(tx.s.tasks.cancel(id, tx.s.wallMs))
        if (r == CancelResult.Cancelled) TaskQueue.pump(tx)
        return r
    }

    /** R-TASK-5 "read it all": the whole result, cleaned for speech, nothing cut. False if there is no result yet. */
    fun readAll(tx: Tx, id: String, utteranceId: Long? = null): Boolean {
        val t = tx.s.tasks[id] ?: return false
        val result = t.result ?: return false
        tx.s.resultUtterances[id]?.let(tx::drop)
        tx.s = tx.s.copy(resultUtterances = tx.s.resultUtterances - id, tasks = tx.s.tasks.markRead(id))
        val sentences = Speakable.sentences(result).map { it to null }.ifEmpty { listOfNotNull(tx.line("result.empty")) }
        if (utteranceId == null) return tx.speak(SpeechKind.Reply, sentences, taskId = id)
        sentences.forEach { (text, slot) -> if (tx.add(utteranceId, text, slot)) tx.updateAction { it.copy(pieces = it.pieces + 1) } }
        return true
    }

    /** A card tap's cancel: stop it and say so. */
    fun cancelTapped(tx: Tx, id: String) {
        when (cancel(tx, id)) {
            CancelResult.Cancelled -> tx.say("cancel.one", SpeechKind.Reply)
            CancelResult.AlreadyFinished -> tx.say("cancel.too_late", SpeechKind.Reply)
            CancelResult.NotFound -> Unit
        }
    }

    /** "Tell it…" on a card: an update by text; its line waits for `Accepted`. */
    fun tell(tx: Tx, id: String, text: String, mode: UpdateMode) {
        val kind = if (mode == UpdateMode.Queue) RunKind.Queue else RunKind.InterruptFirst
        val slot = if (mode == UpdateMode.Queue) "update.queued" else "update.redirect"
        when (val r = update(tx, id, text, kind)) {
            is Updated.Sent -> Confirmations.expectAck(tx, id, r.runId, slot)
            Updated.Amended -> tx.say(slot, SpeechKind.Reply)
            is Updated.Retried, Updated.AlreadySent -> Unit // its summary follows Accepted
            Updated.AtCap -> tx.say(AppLines.LIMIT_FULL, SpeechKind.Reply)
            Updated.NotFound -> Unit
        }
    }

    /** Above every user cap: reopening is limited by [TaskQueue.busy] instead (queued tasks hold no slot). */
    private const val MAX_CAP = 6

    private const val AMENDED = "amended:"
}
