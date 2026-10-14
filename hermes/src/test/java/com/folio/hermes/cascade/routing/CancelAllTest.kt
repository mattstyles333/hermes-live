package com.folio.hermes.cascade.routing

import com.folio.hermes.cascade.CascadeSettings
import com.folio.hermes.cascade.Effect
import com.folio.hermes.cascade.Event
import com.folio.hermes.cascade.TaskState
import com.folio.hermes.cascade.answer
import com.folio.hermes.cascade.routing.CeoHarness.Companion.brief
import com.folio.hermes.cascade.routing.CeoHarness.Companion.briefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Stop everything" (hub decision 2026-10-14) through the pure reducer: every task stops, the count is said. */
class CancelAllTest {
    private fun newTask() = arrayOf("action" to answer("new_task"), "line" to answer("start.one"), "split" to answer("one"), "clarity" to answer("clear"))

    private fun start(h: CeoHarness, label: String, at: Long) {
        h.turn("run the $label job", at, *newTask())
        h.write(briefs(brief("Run the $label job.", label, "running $label")))
    }

    @Test fun stopEverythingCancelsRunningAndQueuedTasksAndSaysTheCount() {
        val h = CeoHarness(CascadeSettings(maxTasks = 2))
        start(h, "Leeds", 0)
        start(h, "Bristol", 5_000)
        start(h, "Cardiff", 10_000)
        assertEquals("two run, the third waits on the phone", listOf("t1", "t2"), h.submits().map { it.taskId })
        assertTrue(TaskQueue.isQueued(h.s.tasks["t3"]!!))
        h.drain()

        val fx = h.turn("Stop everything.", 60_000, "action" to answer("cancel_task", 0.99), "task" to answer("none"), "line" to answer("cancel.all"))
        assertEquals(listOf(Effect.InterruptTask("t1"), Effect.InterruptTask("t2")), fx.filterIsInstance<Effect.InterruptTask>())
        assertTrue("the queued task never starts", h.submits().none { it.taskId == "t3" })
        assertTrue(h.s.tasks.tasks.none { it.state == TaskState.Running })

        // The interrupts come back out of order: the count waits for both.
        h.on(Event.TaskInterrupted("t2", true))
        h.drain()
        assertTrue("not yet: t1 is still out", "That's all three." !in h.said())
        h.on(Event.TaskInterrupted("t1", true))
        h.drain()
        assertEquals(CeoRouter.CANCEL_ALL, h.slotsSaid()[h.slotsSaid().size - 2])
        assertEquals("That's all three.", h.said().last())
        assertTrue("never asked which", AppLinesWhich !in h.slotsSaid())
    }

    @Test fun theCountLine() {
        assertEquals("That's both of them.", CancelAll.countLine(2))
        assertEquals("That's all three.", CancelAll.countLine(3))
        assertEquals("That's all 14.", CancelAll.countLine(14))
    }

    private companion object {
        const val AppLinesWhich = "cancel.which"
    }
}
