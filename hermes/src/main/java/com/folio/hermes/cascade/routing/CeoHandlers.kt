package com.folio.hermes.cascade.routing

import com.folio.hermes.cascade.AppLines
import com.folio.hermes.cascade.CancelResult
import com.folio.hermes.cascade.Route
import com.folio.hermes.cascade.RunKind
import com.folio.hermes.cascade.TaskState
import com.folio.hermes.cascade.TaskTable
import com.folio.hermes.cascade.Tx
import com.folio.hermes.cascade.UpdateMode
import com.folio.hermes.cascade.writer.BriefTemplate
import com.folio.hermes.cascade.writer.CeoPrompts
import com.folio.hermes.cascade.writer.Repair
import com.folio.hermes.cascade.writer.TranscriptBlock
import com.folio.hermes.cascade.writer.WriterPrompts

/**
 * `update_task` in CEO mode (R-RT-7, R-TASK-7, spec v2 §0): the `update.*` line waits for the
 * run's `Accepted` ([Confirmations.expectAck]); a key doubt asks first; a queued task's brief is
 * amended; a failed task is retried with the same brief.
 */
class CeoUpdateTaskHandler(private val memo: MemoRouting) : RouteHandler {
    override fun playsSlotFirst(tx: Tx): Boolean = false

    override fun start(tx: Tx): Next {
        if (KeyDoubt.needed(tx, tx.action)) return KeyDoubt.ask(tx)
        return go(tx)
    }

    override fun resume(tx: Tx, result: ActionResult): Next = when (result) {
        is ActionResult.Written -> KeyDoubt.afterQuestion(tx, result.spoke) ?: go(tx)
        is ActionResult.TimerFired -> {
            KeyDoubt.waited(tx)
            if (tx.action.data[KeyDoubt.STATE] == KeyDoubt.DROPPED) {
                tx.play("cancel.drop")
                Next.Done
            } else {
                go(tx)
            }
        }
        is ActionResult.Busy -> send(tx, TaskTable.kindFor(UpdateMode.Redirect, result.mode))
        else -> Next.Done
    }

    private fun go(tx: Tx): Next {
        val route = tx.action.decision.route as Route.UpdateTask
        val t = tx.s.tasks[route.taskId]
        if (t == null) {
            tx.play(AppLines.WHICH_UPDATE)
            return Next.Done
        }
        val running = t.state == TaskState.Running && !TaskQueue.isQueued(t)
        return if (running && route.mode == UpdateMode.Redirect) tx.readBusyMode() else send(tx, RunKind.Queue)
    }

    private fun send(tx: Tx, kind: RunKind): Next {
        val a = tx.action
        val route = a.decision.route as Route.UpdateTask
        val slot = a.decision.slot ?: if (route.mode == UpdateMode.Queue) "update.queued" else "update.redirect"
        val memoTurn = MemoRouting.isMemo(tx, a.turnId)
        when (val r = CeoTasks.update(tx, route.taskId, text(tx), kind, a.turnId)) {
            is CeoTasks.Updated.Sent -> if (!memoTurn) Confirmations.expectAck(tx, route.taskId, r.runId, slot)
            CeoTasks.Updated.Amended -> if (!memoTurn) tx.play(slot)
            is CeoTasks.Updated.Retried, CeoTasks.Updated.AlreadySent -> Unit // the retry's summary follows its Accepted; a resend says nothing new
            CeoTasks.Updated.AtCap -> tx.play(AppLines.LIMIT_FULL)
            CeoTasks.Updated.NotFound -> tx.play(AppLines.WHICH_UPDATE)
        }
        if (memoTurn) memo.record(tx, a.turnId, "update_task", 1)
        return Next.Done
    }

    /** The update text: the user's words, plus the transcript block when a word was doubtful or it was dictated offline. */
    private fun text(tx: Tx): String {
        val a = tx.action
        val t = BriefPlanner.transcript(tx, a)
        val clarity = a.record.answers[CascadeBrain.CLARITY]?.choice
        val offline = memo.offlineNote(tx, a.turnId)
        val doubtful = t.uncertain.isNotEmpty() || clarity == CascadeBrain.MINOR_DOUBT || clarity == CascadeBrain.KEY_DOUBT
        val answer = a.data[KeyDoubt.ANSWER]
        if (!doubtful && offline == null && answer == null) return a.text
        val notes = listOfNotNull(answer?.let { "asked about a doubtful word; the user answered \"$it\"" }).joinToString(" ")
        return a.text + "\n\n" + BriefTemplate.block(TranscriptBlock(a.text, Repair.marked(t), BriefPlanner.clarity(a), notes, offline))
    }
}

/** `cancel_task` (R-TASK-4): `session.interrupt` (a queued task is just dropped), then a short confirmation. */
class CeoCancelTaskHandler(private val memo: MemoRouting) : RouteHandler {
    override fun start(tx: Tx): Next {
        val a = tx.action
        val route = a.decision.route as Route.CancelTask
        val queued = tx.s.tasks[route.taskId]?.let(TaskQueue::isQueued) == true
        if (MemoRouting.isMemo(tx, a.turnId)) memo.record(tx, a.turnId, "cancel_task", 1)
        return when (CeoTasks.cancel(tx, route.taskId)) {
            CancelResult.NotFound -> {
                tx.play(AppLines.WHICH_CANCEL)
                Next.Done
            }
            CancelResult.AlreadyFinished -> {
                if (a.slot != "cancel.too_late") tx.play("cancel.too_late")
                Next.Done
            }
            CancelResult.Cancelled -> if (queued) confirm(tx) else {
                tx.updateAction { it.copy(waitingOn = "interrupt:${route.taskId}") }
                Next.Wait("interrupt")
            }
        }
    }

    override fun resume(tx: Tx, result: ActionResult): Next = confirm(tx)

    private fun confirm(tx: Tx): Next {
        val a = tx.action
        val route = a.decision.route as Route.CancelTask
        if (a.decision.writer) return tx.write(WriterPrompts.spoken(a.line, 1), WriterPrompts.cancelled(tx.s.tasks[route.taskId]?.label.orEmpty()), null)
        if (a.slot == null) tx.play("cancel.one")
        return Next.Done
    }
}

/** `status` (R-TASK-3): from the phone's event log only, never a prompt to a running task; the writer always speaks (R-RT-6). */
object CeoStatusHandler : RouteHandler {
    override fun start(tx: Tx): Next {
        val a = tx.action
        val route = a.decision.route as Route.Status
        val tasks = tx.s.tasks.tasks
        val fallback = StatusHandler.statusSlot(tx.s.tasks.snapshots())
        if (tasks.isEmpty()) {
            if (a.slot == null) tx.play(fallback)
            return Next.Done
        }
        val queued = TaskQueue.queued(tx.s.tasks).map { it.id }.toSet()
        return tx.write(WriterPrompts.spoken(a.line), CeoPrompts.status(tasks, route.taskId, tx.s.wallMs, queued), fallback)
    }
}

/** `read_result` (R-TASK-5): asked for, the result is read in full, cleaned for speech. */
object CeoReadResultHandler : RouteHandler {
    override fun start(tx: Tx): Next {
        val a = tx.action
        val route = a.decision.route as Route.ReadResult
        if (!CeoTasks.readAll(tx, route.taskId, a.utteranceId)) {
            if (a.slot == null) tx.play("status.working")
        }
        return Next.Done
    }
}

/** A turn folded into a waiting key-doubt question as its answer: nothing more to do. */
object CeoReplyHandler : RouteHandler {
    override fun start(tx: Tx): Next = if (tx.action.data.containsKey(KeyDoubt.ABSORBED)) Next.Done else ReplyHandler.start(tx)
}
