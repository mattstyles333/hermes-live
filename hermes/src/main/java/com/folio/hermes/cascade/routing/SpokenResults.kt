package com.folio.hermes.cascade.routing

import com.folio.hermes.cascade.AppLines
import com.folio.hermes.cascade.CascadeEvent
import com.folio.hermes.cascade.SpeechKind
import com.folio.hermes.cascade.TaskResult
import com.folio.hermes.cascade.Tx
import com.folio.hermes.cascade.WriterJob
import com.folio.hermes.cascade.WriterOwner
import com.folio.hermes.cascade.writer.WriterPrompts

/**
 * Task results (C1): queued as a `Result` utterance (spoken only when the user is silent and Bill
 * is idle). A failure or empty result is the stock line alone. Otherwise the writer reads it, and
 * its stock lead-in goes in with the first written sentence, so a result never holds the floor
 * while its writer is still working. If the writer says nothing: the lead-in plus `result.screen`.
 */
object SpokenResults : ResultHandler {
    override fun arrived(tx: Tx, result: TaskResult) {
        val u = tx.open(SpeechKind.Result, taskId = result.taskId)
        tx.s = tx.s.copy(resultUtterances = tx.s.resultUtterances + (result.taskId to u))
        tx.emit(CascadeEvent.ResultQueued(result.taskId))
        val slot = if (result.ok) AppLines.RESULT_BACK else AppLines.RESULT_FAILED
        val line = tx.nextLine(slot)
        if (!result.ok || result.text.isBlank()) {
            line?.let { tx.add(u, it, slot) }
            tx.close(u)
            return
        }
        tx.stream(
            WriterOwner.Result(result.taskId, slot, line),
            u,
            WriterPrompts.spoken(line),
            WriterPrompts.result(result.label, result.text),
            lead = line?.let { slot to it },
        )
    }

    override fun written(tx: Tx, job: WriterJob, spoke: Boolean) {
        val owner = job.owner as WriterOwner.Result
        if (!spoke) {
            owner.line?.let { tx.add(job.utteranceId, it, owner.slot) }
            tx.nextLine(AppLines.ON_SCREEN)?.let { tx.add(job.utteranceId, it, AppLines.ON_SCREEN) }
        }
        tx.close(job.utteranceId)
    }
}
