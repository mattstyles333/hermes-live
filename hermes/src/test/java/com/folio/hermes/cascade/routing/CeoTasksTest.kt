package com.folio.hermes.cascade.routing

import com.folio.hermes.TurnStatus
import com.folio.hermes.TurnUpdate
import com.folio.hermes.cascade.AppLines
import com.folio.hermes.cascade.CascadeEnv
import com.folio.hermes.cascade.CascadeEvent
import com.folio.hermes.cascade.Effect
import com.folio.hermes.cascade.Event
import com.folio.hermes.cascade.PhraseBank
import com.folio.hermes.cascade.SendState
import com.folio.hermes.cascade.TaskState
import com.folio.hermes.cascade.TaskTap
import com.folio.hermes.cascade.TaskUiStatus
import com.folio.hermes.cascade.UiProjection
import com.folio.hermes.cascade.UpdateMode
import com.folio.hermes.cascade.answer
import com.folio.hermes.cascade.budget.JevStateBudget
import com.folio.hermes.cascade.routing.CeoHarness.Companion.brief
import com.folio.hermes.cascade.routing.CeoHarness.Companion.briefs
import com.folio.hermes.cascade.writer.BriefTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The routing slice through the pure `reduce`: tasks, ordering and confirmations (R-TASK-2..5/7, spec v2 §0). */
class CeoTasksTest {
    private val newTask = arrayOf("action" to answer("new_task"), "line" to answer("start.lookup"), "split" to answer("one"), "clarity" to answer("clear"))

    @Test fun theDefaultEnvironmentStaysC1() {
        val bank = PhraseBank.load()
        val default = CascadeEnv(bank)
        assertTrue("C1 handlers by default", default.handlers.handler(com.folio.hermes.cascade.Route.NewTask) === NewTaskHandler)
        assertTrue("no Jev state cap by default", default.budget === JevStateBudget.PassThrough)
        val on = CeoRouting.env(bank)
        assertFalse(on.handlers.handler(com.folio.hermes.cascade.Route.NewTask) === NewTaskHandler)
        assertEquals(default.handlers.wires, on.handlers.wires)
    }

    @Test fun startLinePlaysAtOnceSummaryWaitsForAccepted() {
        val h = CeoHarness()
        val commit = h.turn("check stock of RB2140", 0, *newTask)
        assertEquals("start.* plays at once", "start.lookup", commit.filterIsInstance<Effect.FetchClip>().single().piece.slot)
        val write = commit.filterIsInstance<Effect.Write>().single()
        assertEquals(com.folio.hermes.cascade.writer.Briefs.SYSTEM, write.system)
        assertTrue("check stock of RB2140" in write.prompt)
        h.write(briefs(brief("Check stock of RB2140.", "stock check", "checking RB2140 stock")))
        val submit = h.submits().single()
        assertTrue(submit.text.startsWith("Goal: Check stock of RB2140."))
        assertTrue(BriefTemplate.REPORT_BACK in submit.text)
        assertTrue("the full verbatim text reaches Hermes", "Transcript: check stock of RB2140" in submit.text)
        assertTrue("As heard: check stock of RB2140" in submit.text)
        assertTrue("Clarity: clear (0.90)" in submit.text)
        assertTrue(submit.text.endsWith(BriefTemplate.ASK_FIRST))
        val card = UiProjection.project(h.s).tasks.single()
        assertEquals("stock check", card.label)
        assertEquals("Checking RB2140 stock.", card.summary)
        assertEquals(SendState.Sending, card.send)

        h.drain()
        assertFalse("no summary before Accepted", h.said().any { "RB2140 stock" in it })
        h.accept(submit)
        h.drain()
        assertEquals("Checking RB2140 stock.", h.said().last())
        // Spoken once: a later update's Accepted does not repeat it.
        h.on(Event.TaskUpdated("t1", submit.runId, TurnUpdate.Started))
        h.drain()
        assertEquals(1, h.said().count { it == "Checking RB2140 stock." })
    }

    @Test fun aSplitIntoSixStartsFourAndQueuesTwo() {
        val h = CeoHarness()
        h.turn("six things", 0, "action" to answer("new_task"), "split" to answer("several"), "line" to answer("none"))
        // Letters, not numbers: a number the user never said would fail the verbatim check.
        h.write(briefs(*"ABCDEF".map { brief("Job $it.", "job $it", "doing job $it") }.toTypedArray()))
        assertEquals(listOf("t1", "t2", "t3", "t4"), h.submits().map { it.taskId })
        val cards = UiProjection.project(h.s).tasks
        assertEquals(6, cards.size)
        assertEquals(listOf(TaskUiStatus.Queued, TaskUiStatus.Queued), cards.drop(4).map { it.status })
        assertTrue("each brief knows its scope", "Scope: job 5 of 6" in h.s.tasks["t5"]!!.brief!!)
        // Summaries wait for all four to be accepted, then go together, with the queued note.
        h.submits().take(3).forEach { h.accept(it) }
        h.drain()
        assertFalse(h.said().any { it.startsWith("Doing job") })
        h.accept(h.submits()[3])
        h.drain()
        assertEquals(listOf("Doing job A.", "Doing job B.", "Doing job C.", "Doing job D."), h.said().filter { it.startsWith("Doing job") })
        assertEquals(Confirmations.QUEUED, h.slotsSaid().last())
        // A slot frees when a task's turn completes: the oldest queued one starts.
        h.on(Event.TaskUpdated("t2", h.submits()[1].runId, TurnUpdate.Completed("done", TurnStatus.Complete, null)))
        assertEquals("t5", h.submits().last().taskId)
        assertEquals(5, h.submits().size)
        h.accept(h.submits().last())
        h.drain()
        assertEquals("Doing job E.", h.said().filter { it.startsWith("Doing job") }.last())
    }

    @Test fun allQueuedSaysLimitQueuedAtOnce() {
        val h = CeoHarness(com.folio.hermes.cascade.CascadeSettings(maxTasks = 1))
        h.turn("first", 0, *newTask)
        h.write(briefs(brief("First.", "first job", "first")))
        h.turn("second", 5_000, *newTask)
        h.write(briefs(brief("Second.", "second job", "second")))
        assertEquals(1, h.submits().size)
        h.drain()
        assertTrue(Confirmations.QUEUED in h.slotsSaid())
        assertTrue(TaskQueue.isQueued(h.s.tasks["t2"]!!))
    }

    @Test fun aFailedSubmitIsAlwaysSpoken() {
        val h = CeoHarness()
        h.turn("email the supplier", 0, *newTask)
        h.write(briefs(brief("Email the supplier.", "supplier email", "emailing the supplier")))
        val submit = h.submits().single()
        h.on(Event.TaskUpdated("t1", submit.runId, TurnUpdate.Failed("IOException")))
        h.on(Event.TaskRunEnded("t1", submit.runId))
        h.drain()
        assertEquals(AppLines.GAVE_UP, h.slotsSaid().last())
        assertEquals(1, h.slotsSaid().count { it == AppLines.GAVE_UP })
        assertFalse("no result line for a submit that never reached Hermes", AppLines.RESULT_FAILED in h.slotsSaid())
        assertFalse(h.said().any { it == "Emailing the supplier." })
        assertEquals(TaskUiStatus.Failed, UiProjection.project(h.s).tasks.single().status)
    }

    @Test fun statusNeverSubmits() {
        val h = CeoHarness()
        h.turn("check stock", 0, *newTask)
        h.write(briefs(brief("Check stock.", "stock check", "checking stock")))
        h.accept(h.submits().single())
        val before = h.submits().size
        val status = h.turn("how's it going", 10_000, "action" to answer("status"), "task" to answer("t1"), "line" to answer("status.working"))
        assertTrue(status.none { it is Effect.SubmitTask || it is Effect.InterruptTask || it is Effect.ReadBusyMode })
        assertEquals(before, h.submits().size)
        assertTrue(status.filterIsInstance<Effect.StreamWriter>().single().prompt.startsWith("Say how the user's tasks are getting on, from this log only"))
    }

    @Test fun scrapThatCancelsTheNewestTask() {
        val h = CeoHarness()
        h.turn("check stock", 0, *newTask)
        h.write(briefs(brief("Check stock.", "stock check", "checking stock")))
        h.turn("draft the newsletter", 20_000, *newTask)
        h.write(briefs(brief("Draft the newsletter.", "newsletter", "drafting the newsletter")))
        val cancel = h.turn("scrap that", 25_000, "action" to answer("cancel_task"), "task" to answer("none", 0.6))
        assertEquals(Effect.InterruptTask("t2"), cancel.filterIsInstance<Effect.InterruptTask>().single())
        assertEquals(TaskState.Cancelled, h.s.tasks["t2"]!!.state)
        assertEquals(TaskState.Running, h.s.tasks["t1"]!!.state)
    }

    @Test fun cancellingAQueuedTaskSendsNothing() {
        val h = CeoHarness(com.folio.hermes.cascade.CascadeSettings(maxTasks = 1))
        h.turn("first", 0, *newTask)
        h.write(briefs(brief("First.", "first job", "first")))
        h.turn("second", 5_000, *newTask)
        h.write(briefs(brief("Second.", "second job", "second")))
        val cancel = h.turn("drop the second one", 30_000, "action" to answer("cancel_task"), "task" to answer("t2"))
        assertTrue(cancel.none { it is Effect.InterruptTask })
        assertEquals(TaskState.Cancelled, h.s.tasks["t2"]!!.state)
        assertTrue(CascadeEvent.TaskCancelled("t2") in h.emitted())
    }

    @Test fun aCorrectionWithin20sRedirectsAndItsLineWaitsForAccepted() {
        val h = CeoHarness()
        h.turn("email Sapphire", 0, *newTask)
        h.write(briefs(brief("Email Sapphire.", "supplier email", "emailing Sapphire")))
        h.accept(h.submits().single())
        h.drain()
        val fix = h.turn("no, I said Safilo", 10_000, "action" to answer("update_task"), "task" to answer("t1"), "update_mode" to answer("queue", 0.9))
        val busy = fix.filterIsInstance<Effect.ReadBusyMode>().single()
        val sent = h.on(Event.BusyModeRead(busy.seq, com.folio.hermes.BusyMode.Interrupt)).filterIsInstance<Effect.SubmitTask>().single()
        assertTrue(sent.stopOnRedirect)
        assertFalse(sent.queued)
        assertEquals("no, I said Safilo", sent.text)
        h.drain()
        assertFalse("update line waits for Accepted", "update.redirect" in h.slotsSaid())
        h.accept(sent, com.folio.hermes.SubmitStatus.Redirected)
        h.drain()
        assertEquals("update.redirect", h.slotsSaid().last())
    }

    @Test fun after20sAnUnsureRedirectIsQueued() {
        val h = CeoHarness()
        h.turn("email Sapphire", 0, *newTask)
        h.write(briefs(brief("Email Sapphire.", "supplier email", "emailing Sapphire")))
        val fix = h.turn("also copy in Tom", 30_000, "action" to answer("update_task"), "task" to answer("t1"), "update_mode" to answer("redirect", 0.3))
        val sent = fix.filterIsInstance<Effect.SubmitTask>().single()
        assertTrue(sent.queued)
        assertTrue(CascadeEvent.UpdateSent("t1", UpdateMode.Queue) in h.emitted())
    }

    @Test fun aFailedTaskIsRetriedWithTheSameBrief() {
        val h = CeoHarness()
        h.turn("check stock", 0, *newTask)
        h.write(briefs(brief("Check stock.", "stock check", "checking stock")))
        val first = h.submits().single()
        h.accept(first)
        h.on(Event.TaskUpdated("t1", first.runId, TurnUpdate.Completed("", TurnStatus.Error, "boom")))
        h.on(Event.TaskRunEnded("t1", first.runId))
        assertEquals(TaskState.Failed, h.s.tasks["t1"]!!.state)
        h.turn("try that again", 30_000, "action" to answer("update_task"), "task" to answer("t1"))
        val retry = h.submits().last()
        assertEquals("t2", retry.taskId)
        assertTrue(retry.text.startsWith(first.text))
        assertEquals("stock check", h.s.tasks["t2"]!!.label)
        // The public entry point for the registry's tap does the same.
        val tx = com.folio.hermes.cascade.Tx(h.s, h.env)
        assertNotNull(CeoTasks.retry(tx, "t1"))
        assertEquals(first.text, tx.effects.filterIsInstance<Effect.SubmitTask>().single().text)
    }

    @Test fun resultBriefingIsTwoSentencesAndReadItAllReadsEverything() {
        val h = CeoHarness()
        h.turn("check stock", 0, *newTask)
        h.write(briefs(brief("Check stock.", "stock check", "checking stock")))
        val submit = h.submits().single()
        h.accept(submit)
        val long = "# Stock\n- **RB2140**: 14 in stock.\n- RB3025: 2 left, reorder?\nSee [the sheet](https://example.invalid/x)."
        val done = h.on(Event.TaskUpdated("t1", submit.runId, TurnUpdate.Completed(long, TurnStatus.Complete, null)))
        val writer = done.filterIsInstance<Effect.StreamWriter>().single()
        assertTrue("at most 2 sentences" in writer.prompt)
        assertTrue("At most 2 sentences." in writer.system)
        h.stream("RB2140 has 14.", "RB3025 needs a reorder decision.")
        h.on(Event.TaskRunEnded("t1", submit.runId))
        h.drain()
        h.on(Event.TaskTapped("t1", TaskTap.ReadAll))
        h.drain()
        val tail = h.said().takeLast(4)
        assertEquals(listOf("Stock.", "RB2140: 14 in stock.", "RB3025: 2 left, reorder?", "See the sheet."), tail)
        // Asked by voice, read_result reads it in full too.
        h.turn("read me the whole thing", 60_000, "action" to answer("read_result"), "task" to answer("t1"))
        h.drain()
        assertEquals(tail, h.said().takeLast(4))
    }

    @Test fun jevUnavailableOnlineGetsAClarifyingReplyNeverATask() {
        val h = CeoHarness()
        h.at(0, Event.SpeechStarted)
        h.at(0, Event.Final("order forty units"))
        h.at(0, Event.SpeechEnded)
        val early = h.at(500, Event.TimerFired(com.folio.hermes.cascade.Timer(com.folio.hermes.cascade.TurnRules.EARLY, h.s.turn.pauseSeq))).filterIsInstance<Effect.CallJev>().single()
        val refused = com.folio.hermes.openrouter.OpenRouterResult.Failed(com.folio.hermes.openrouter.FailureKind.Refused, 401)
        h.at(600, Event.JevAnswered(early.seq, refused, 600))
        val second = h.at(1_000, Event.TimerFired(com.folio.hermes.cascade.Timer(com.folio.hermes.cascade.TurnRules.COMMIT, h.s.turn.pauseSeq))).filterIsInstance<Effect.CallJev>().single()
        val out = h.at(1_100, Event.JevAnswered(second.seq, refused, 1_100))
        assertTrue(out.filterIsInstance<Effect.StreamWriter>().single().prompt.contains("Ask one short question"))
        assertTrue(h.submits().isEmpty())
        assertTrue(out.none { it is Effect.Write })
    }

    @Test fun offlineCommitsOnTheTimerWithoutJev() {
        val h = CeoHarness()
        h.on(Event.Network(false))
        h.at(0, Event.SpeechStarted)
        h.at(0, Event.Final("note to self"))
        val ended = h.at(0, Event.SpeechEnded)
        assertTrue(Effect.StartTimer(com.folio.hermes.cascade.Timer(com.folio.hermes.cascade.TurnRules.COMMIT, h.s.turn.pauseSeq), 1_500) in ended)
        val commit = h.at(1_500, Event.TimerFired(com.folio.hermes.cascade.Timer(com.folio.hermes.cascade.TurnRules.COMMIT, h.s.turn.pauseSeq)))
        assertTrue(h.effects.none { it is Effect.CallJev })
        assertEquals(listOf("u1"), h.s.outbox.ids())
        assertTrue(commit.none { it is Effect.SubmitTask })
    }
}
