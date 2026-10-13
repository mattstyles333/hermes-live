package com.folio.hermes.cascade.routing

import com.folio.hermes.TurnUpdate
import com.folio.hermes.cascade.ActionState
import com.folio.hermes.cascade.AppLines
import com.folio.hermes.cascade.CascadeEvent
import com.folio.hermes.cascade.Decision
import com.folio.hermes.cascade.DecisionRecord
import com.folio.hermes.cascade.Event
import com.folio.hermes.cascade.PhraseBank
import com.folio.hermes.cascade.Route
import com.folio.hermes.cascade.RunKind
import com.folio.hermes.cascade.SpeechKind
import com.folio.hermes.cascade.State
import com.folio.hermes.cascade.TaskResult
import com.folio.hermes.cascade.TaskState
import com.folio.hermes.cascade.TaskTap
import com.folio.hermes.cascade.Transcript
import com.folio.hermes.cascade.TranscriptPass
import com.folio.hermes.cascade.TurnEvent
import com.folio.hermes.cascade.Tx
import com.folio.hermes.cascade.WriterJob
import com.folio.hermes.cascade.WriterOwner
import com.folio.hermes.cascade.writer.CeoPrompts
import com.folio.hermes.cascade.writer.Repair
import com.folio.hermes.cascade.writer.WriterPrompts
import com.folio.hermes.openrouter.FailureKind
import com.folio.hermes.openrouter.JevAnswer

/**
 * The routing slice's turn hooks (spec v2): R-RT-3/5/6/7 decisions ([CeoRouter]), R-RT-8 (no Jev
 * call while offline; an unavailable Jev gets a clarifying reply, never a task), the per-turn
 * transcript and R-STT-5 clarity, confirmations after `Accepted`, queued tasks starting as slots
 * free, and the task-card taps.
 */
class CeoHooks(private val memo: MemoRouting) : TurnHooks {
    /** R-RT-8: offline, the turn commits on `pause_ms + 500` with no Jev call (then memo mode holds it). */
    override fun useJev(state: State): Boolean = state.connection.online

    override fun decide(state: State, answers: Map<String, JevAnswer>, bank: PhraseBank): Decision = CeoRouter.decide(state, answers, bank)

    /**
     * Jev gave nothing usable. No response (offline): the outbox (C1). A timeout or an unusable
     * answer: `clarify.again` (spec v2 §0). Jev unavailable while online (no key, refused, HTTP
     * error): the writer asks a clarifying question (R-RT-8). Never a task.
     */
    override fun noAnswer(tx: Tx, event: TurnEvent.NoAnswer, base: DecisionRecord) {
        when (event.failure.kind) {
            FailureKind.Offline, FailureKind.Timeout, FailureKind.BadBody -> super.noAnswer(tx, event, base)
            FailureKind.NoKey, FailureKind.Refused, FailureKind.Http -> {
                val text = (event.pending + event.heard).filter { it.isNotBlank() }.joinToString(" ")
                val record = base.copy(outcome = "no_answer", failure = event.failure.kind.wire, route = Route.Clarify.wire)
                tx.enqueueAction(ActionState(event.turnId, text, Decision(Route.Clarify, null, writer = true), event.speechEndAt, record))
            }
        }
    }

    /**
     * A turn was committed: keep its transcript (the fast-pass words for its text), flag a doubtful
     * transcript (R-STT-5) and, if an action waits on a key-doubt question, take this turn as the answer.
     */
    override fun committed(tx: Tx, action: ActionState, event: TurnEvent.Done): ActionState {
        val transcript = transcriptFor(tx, action)
        val clarity = event.answers[CascadeBrain.CLARITY]
        if (clarity != null && clarity.choice != CascadeBrain.CLEAR && clarity.choice != null && clarity.confidence >= tx.s.settings.confidenceFloor) {
            tx.emit(CascadeEvent.ClarityDoubt(action.turnId, clarity.choice!!))
        }
        val withTranscript = action.copy(transcript = transcript)
        return KeyDoubt.absorb(tx, withTranscript) ?: withTranscript
    }

    /** The accurate pass for a turn (R-STT-2, S-C): its transcript replaces the fast one. */
    override fun accurate(tx: Tx, event: Event.AccurateFinal) {
        val t = event.transcript ?: return
        keep(tx, event.turnId, t)
        val update = { a: ActionState -> if (a.turnId == event.turnId) a.copy(transcript = t) else a }
        tx.s = tx.s.copy(actions = tx.s.actions.copy(active = tx.s.actions.active?.let(update), waiting = tx.s.actions.waiting.map(update)))
    }

    override fun taskUpdated(tx: Tx, event: Event.TaskUpdated) {
        Confirmations.taskUpdated(tx, event)
        if (event.update is TurnUpdate.Completed || event.update is TurnUpdate.Failed) TaskQueue.pump(tx)
    }

    override fun tap(tx: Tx, event: Event.TaskTapped) {
        when (val tap = event.tap) {
            TaskTap.Cancel -> CeoTasks.cancelTapped(tx, event.taskId)
            TaskTap.ReadAll -> CeoTasks.readAll(tx, event.taskId)
            is TaskTap.Tell -> CeoTasks.tell(tx, event.taskId, tap.text, tap.mode)
            TaskTap.Retry, TaskTap.Play -> Unit // the registry's and the conversation's (S-B)
        }
    }

    /**
     * The user picked [Event.WordChosen.choice] for an uncertain word on screen: the transcript is
     * corrected, and each task the turn started hears the correction (queued), or has its brief
     * amended if it has not started.
     */
    override fun wordChosen(tx: Tx, event: Event.WordChosen) {
        val t = tx.s.transcript.byTurn[event.turnId] ?: return
        val words = Repair.aligned(t)
        val old = words.getOrNull(event.index) ?: return
        if (old.text == event.choice) return
        val fixed = words.mapIndexed { i, w -> if (i == event.index) w.copy(text = event.choice, uncertain = false, alternatives = listOf(w.text) + w.alternatives - event.choice) else w }
        keep(tx, event.turnId, t.copy(text = fixed.joinToString(" ") { it.text }, words = fixed))
        val note = "Correction from the user: \"${old.text}\" should be \"${event.choice}\"."
        tx.s.tasks.tasks.filter { it.turnId == event.turnId && (it.state == TaskState.Running || TaskQueue.isQueued(it)) }.forEach { task ->
            when (val r = CeoTasks.update(tx, task.id, note, RunKind.Queue)) {
                is CeoTasks.Updated.Sent -> Confirmations.expectAck(tx, task.id, r.runId, "update.queued")
                else -> Unit
            }
        }
    }

    /** The fast-pass words for [action]'s text (the last words heard), as its transcript. */
    private fun transcriptFor(tx: Tx, action: ActionState): Transcript {
        val tokens = action.text.split(Regex("\\s+")).count { it.isNotEmpty() }
        val heardWords = tx.s.transcript.words
        val words = if (heardWords.size >= tokens) heardWords.takeLast(tokens) else heardWords.filter { it.uncertain }
        val uncertainBelow = tx.s.settings.uncertainBelow
        val marked = words.map { w -> if (!w.uncertain && w.confidence != null && w.confidence < uncertainBelow) w.copy(uncertain = true) else w }
        val t = tx.s.transcript.byTurn[action.turnId] ?: Transcript(action.text, marked, TranscriptPass.Fast)
        tx.s = tx.s.copy(transcript = tx.s.transcript.copy(words = emptyList()))
        keep(tx, action.turnId, t)
        return t
    }

    private fun keep(tx: Tx, turnId: String, t: Transcript) {
        val byTurn = (tx.s.transcript.byTurn - turnId + (turnId to t)).entries.toList().takeLast(KEEP_TRANSCRIPTS).associate { it.key to it.value }
        tx.s = tx.s.copy(transcript = tx.s.transcript.copy(byTurn = byTurn))
    }

    private companion object {
        const val KEEP_TRANSCRIPTS = 32
    }
}

/**
 * Task results in CEO mode (R-TASK-5): queued for when the user is silent and Bill idle, then a
 * briefing of at most `resultSentences` sentences (headline plus any decision); the full text is
 * on the card and "read it all" reads it. A submit that never reached Hermes is not a result:
 * [Confirmations] says `error.gave_up` instead.
 */
object CeoResults : ResultHandler {
    override fun arrived(tx: Tx, result: TaskResult) {
        val t = tx.s.tasks[result.taskId]
        if (!result.ok && t != null && !t.accepted) return
        val u = tx.open(SpeechKind.Result, taskId = result.taskId)
        tx.s = tx.s.copy(resultUtterances = tx.s.resultUtterances + (result.taskId to u))
        tx.emit(CascadeEvent.ResultQueued(result.taskId))
        val others = tx.s.tasks.tasks.count { it.state == TaskState.Running && it.id != result.taskId } > 0
        val slot = when {
            !result.ok -> AppLines.RESULT_FAILED
            result.text.isBlank() -> "result.empty"
            others -> "result.one_of"
            else -> AppLines.RESULT_BACK
        }
        val line = tx.nextLine(slot)
        if (!result.ok || result.text.isBlank()) {
            line?.let { tx.add(u, it, slot) }
            tx.close(u)
            return
        }
        tx.stream(
            WriterOwner.Result(result.taskId, slot, line),
            u,
            WriterPrompts.spoken(line, tx.s.settings.resultSentences),
            CeoPrompts.result(result.label, result.text, tx.s.settings.resultSentences),
            lead = line?.let { slot to it },
        )
    }

    override fun written(tx: Tx, job: WriterJob, spoke: Boolean) = SpokenResults.written(tx, job, spoke)
}
