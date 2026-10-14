package com.folio.hermeslive.ui.cascade

import com.folio.hermes.cascade.TaskUiStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The end transition's state machine (pure): outcome per reason, phases and timings, the summary's numbers. */
class EndCallTest {
    private val working = FakeCascadeUi.Moments.endedWithTasks() // three running, started at script time 5.6 s and 9 s
    private val allDone = FakeCascadeUi.Moments.endedAllDone() // two done (16 s and 20.8 s), one unheard

    private fun run(reason: EndReason, tasks: Boolean, reduced: Boolean = false, tapped: Boolean = false): EndRun =
        EndRun.start(CallEnd(reason, 252_000), tapped, (if (tasks) working else allDone).tasks, 0L, 30_000L, reduced)

    @Test fun everyReasonGetsTheSameWindDownAndOnlyDropsDiffer() {
        for (reason in EndReason.entries) {
            for (tasks in listOf(true, false)) {
                val r = run(reason, tasks)
                val expected = when {
                    reason.dropped -> EndOutcome.Dropped
                    tasks -> EndOutcome.StillWorking
                    else -> EndOutcome.Summary
                }
                assertEquals("$reason tasks=$tasks", expected, r.outcome)
                assertEquals("$reason winds down at once", EndPhase.WindingDown, r.phaseAt(0))
                assertEquals(EndPhase.WindingDown, r.phaseAt(EndTimeline.WIND_MS - 1))
                assertEquals(0f, EndTimeline.wind(0, false))
                assertEquals(1f, EndTimeline.wind(EndTimeline.WIND_MS, false))
                assertEquals(if (reason.dropped) "Call dropped" else "Call ended", r.announcement)
            }
        }
    }

    @Test fun reasonsBillRaisesAreDropsOnlyForErrors() {
        assertEquals(listOf(EndReason.Error, EndReason.Dropped), EndReason.entries.filter { it.dropped })
    }

    @Test fun summaryHoldsThenLeavesForHome() {
        val r = run(EndReason.HungUp, tasks = false)
        assertEquals(EndPhase.Summary, r.phaseAt(EndTimeline.WIND_MS))
        val leave = EndTimeline.CARD_AT_MS + EndTimeline.CARD_MS + EndTimeline.HOLD_MS
        assertEquals(2_500L, leave)
        assertFalse(r.leavesAt(leave - 1))
        assertTrue(r.leavesAt(leave))
        assertEquals(EndPhase.Finished, r.phaseAt(leave))
    }

    @Test fun tasksRunningAndDropsStayUntilClosed() {
        for (r in listOf(run(EndReason.Goodbye, tasks = true), run(EndReason.Error, tasks = true), run(EndReason.Error, tasks = false))) {
            assertEquals(EndPhase.Settled, r.phaseAt(EndTimeline.WIND_MS))
            assertFalse("${r.outcome} never leaves by itself", r.leavesAt(3_600_000))
        }
    }

    @Test fun reduceMotionKeepsTheOrderWithShortFades() {
        val r = run(EndReason.Headset, tasks = false, reduced = true)
        assertEquals(EndPhase.Summary, r.phaseAt(EndTimeline.REDUCED_WIND_MS))
        assertEquals(EndTimeline.REDUCED_CARD_AT_MS + EndTimeline.REDUCED_CARD_MS + EndTimeline.HOLD_MS, EndTimeline.leaveAtMs(true))
        assertTrue(r.leavesAt(EndTimeline.leaveAtMs(true)))
        // Linear fades, no easing.
        assertEquals(0.5f, EndTimeline.wind(EndTimeline.REDUCED_WIND_MS / 2, true), 0.01f)
    }

    @Test fun anUnnamedHangUpAfterATapIsTheEndButton() {
        assertEquals(EndReason.EndButton, run(EndReason.HungUp, tasks = true, tapped = true).end.reason)
        assertEquals(EndReason.HungUp, run(EndReason.HungUp, tasks = true, tapped = false).end.reason)
        assertEquals("an error stays an error", EndReason.Error, run(EndReason.Error, tasks = true, tapped = true).end.reason)
    }

    @Test fun summaryValues() {
        val s = EndSummary.of(allDone.tasks, callStartMs = 0L, durationMs = 252_000)
        assertEquals(EndSummary(252_000, running = 0, done = 2, unheard = 1), s)
        assertEquals("Call ended · 4:12", s.line())
        assertEquals("2 tasks done · 1 result unheard", s.detail())

        val w = EndSummary.of(working.tasks, callStartMs = 0L, durationMs = 252_000)
        assertEquals(3, w.running)
        assertEquals("Call ended · 4:12 · 3 tasks still running", w.line())
        assertEquals("Call dropped · 3 tasks still running", w.line(dropped = true))
        assertEquals("1 task still running", EndSummary(null, 1, 0, 0).line().substringAfter("Call ended · "))
        assertEquals("No tasks this call", EndSummary(60_000, 0, 0, 0).detail())
        assertEquals("1 task done", EndSummary(60_000, 0, 1, 0).detail())
    }

    @Test fun onlyTasksFinishedDuringThisCallCountAsDone() {
        // The call started at 18 s on the tasks' clock: "Stock check" finished at 16 s, before it.
        assertEquals(1, EndSummary.of(allDone.tasks, callStartMs = 18_000L, durationMs = null).done)
        assertEquals("unheard counts every result not heard yet", 1, EndSummary.of(allDone.tasks, 18_000L, null).unheard)
    }

    @Test fun durationIsTheEnginesElseTheScreenClock() {
        val fromEngine = EndRun.start(CallEnd(EndReason.HungUp, 61_000), false, emptyList(), 1_000L, 500_000L, false)
        assertEquals(61_000L, fromEngine.summary.durationMs)
        val fromClock = EndRun.start(CallEnd(EndReason.HungUp, null), false, emptyList(), 1_000L, 73_500L, false)
        assertEquals(72_500L, fromClock.summary.durationMs)
        val neverLive = EndRun.start(CallEnd(EndReason.Error, null, "no mic"), false, emptyList(), null, 73_500L, false)
        assertEquals(null, neverLive.summary.durationMs)
        assertEquals("Call dropped", neverLive.summary.line(dropped = true))
    }

    @Test fun queuedAndWaitingTasksStillRun() {
        val s = working.copy(tasks = working.tasks.mapIndexed { i, t -> if (i == 0) t.copy(status = TaskUiStatus.Queued) else t.copy(status = TaskUiStatus.Waiting) })
        assertEquals(3, EndSummary.runningOf(s.tasks))
    }

    @Test fun rowSpacingFitsTheScreen() {
        assertEquals(0f, EndTimeline.rowSpacingDp(1, 393f))
        assertEquals(64f, EndTimeline.rowSpacingDp(3, 393f))
        assertTrue(EndTimeline.rowSpacingDp(6, 393f) * 5 <= 393f - 64f)
    }
}
