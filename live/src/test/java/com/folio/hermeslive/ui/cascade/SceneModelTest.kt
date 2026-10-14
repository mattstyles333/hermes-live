package com.folio.hermeslive.ui.cascade

import com.folio.hermes.cascade.CascadeEvent
import com.folio.hermes.cascade.SendState
import com.folio.hermes.cascade.TaskUi
import com.folio.hermes.cascade.TaskUiStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneModelTest {
    private fun task(id: String, status: TaskUiStatus = TaskUiStatus.Running, start: Long = 0, steps: Int = 0, send: SendState = SendState.Accepted) =
        TaskUi(id, "Task $id", status, start, null, steps, "tool", emptyList(), start, "", null, 0, send = send)

    private fun model(reduce: Boolean = false) = SceneModel().apply {
        reduceMotion = reduce
        layout(500f, 800f, 3f)
    }

    private fun SceneModel.run(seconds: Float, tasks: List<TaskUi>) {
        var t = 0f
        while (t < seconds) {
            sync(tasks, offline = false, pausing = false)
            step(1 / 60f, jevInFlight = false, targetLevel = 0f, ink = false)
            t += 1 / 60f
        }
    }

    private fun SceneModel.live(): List<String> = sats.filter { it.phase == SatPhase.Live }.mapNotNull { it.id }

    private fun SceneModel.sparks(): Int = (0 until sparkCount()).count { sparkLife(it) > 0f }

    @Test fun atMostSixSatellitesAndQueuedGiveWay() {
        val m = model()
        val queued = (0 until 2).map { task("q$it", TaskUiStatus.Queued, start = it.toLong()) }
        m.run(0.5f, queued)
        assertEquals(listOf("q0", "q1"), m.live())
        val running = (0 until 8).map { task("r$it", start = 10L + it) }
        m.run(1f, queued + running)
        assertEquals(SceneModel.MAX_SATS, m.visibleSatellites())
        assertEquals((0 until 6).map { "r$it" }.toSet(), m.live().toSet())
    }

    @Test fun stateIsTheTruthWithoutEvents() {
        val m = model()
        m.run(0.5f, listOf(task("a"), task("b")))
        // a finished and b vanished, no events at all: a flies in, b fades, nothing is left.
        m.run(2f, listOf(task("a", TaskUiStatus.Done)))
        assertEquals(0, m.visibleSatellites())
        // A task first seen finished never gets a satellite.
        m.run(0.2f, listOf(task("a", TaskUiStatus.Done), task("c", TaskUiStatus.Done)))
        assertEquals(0, m.visibleSatellites())
    }

    @Test fun finishedSatelliteFliesIntoTheOrb() {
        val m = model()
        m.run(0.5f, listOf(task("a")))
        m.sync(listOf(task("a", TaskUiStatus.Done)), offline = false, pausing = false)
        assertEquals(SatPhase.Flying, m.sats.first { it.id == "a" }.phase)
        m.run(0.4f, listOf(task("a", TaskUiStatus.Done)))
        val sat = m.sats.first { it.id == "a" }
        assertTrue("moving towards the centre", kotlin.math.hypot(sat.x - m.cx, sat.y - m.cy) < kotlin.math.hypot(sat.fromX - m.cx, sat.fromY - m.cy))
    }

    @Test fun failedAndCancelledLeave() {
        val m = model()
        m.run(0.5f, listOf(task("a"), task("b")))
        m.sync(listOf(task("a", TaskUiStatus.Failed), task("b", TaskUiStatus.Cancelled)), offline = false, pausing = false)
        assertEquals(SatPhase.Failing, m.sats.first { it.id == "a" }.phase)
        assertEquals(SatPhase.Shrinking, m.sats.first { it.id == "b" }.phase)
        m.run(2f, listOf(task("a", TaskUiStatus.Failed), task("b", TaskUiStatus.Cancelled)))
        assertEquals(0, m.visibleSatellites())
    }

    @Test fun oneBurstPerToolEvenWithTheEvent() {
        val m = model()
        m.run(0.5f, listOf(task("a")))
        m.onEvent(CascadeEvent.ToolStarted("a", "search"))
        m.sync(listOf(task("a", steps = 1)), offline = false, pausing = false)
        assertEquals(SceneModel.SPARKS_PER_TOOL, m.sparks())
    }

    @Test fun reduceMotionHasNoOrbitTrailsOrSparks() {
        val m = model(reduce = true)
        m.run(0.2f, listOf(task("a")))
        val angle = m.sats.first { it.id == "a" }.angle
        m.run(1f, listOf(task("a", steps = 2)))
        val sat = m.sats.first { it.id == "a" }
        assertEquals(angle, sat.angle, 0f)
        assertEquals(0, sat.trailLen)
        assertEquals(0, m.sparks())
        assertEquals(FrameNeed.None, m.frameNeed(speaking = true, pausing = true, jevInFlight = true))
    }

    @Test fun waitingNotSentAndQueuedStayStill() {
        val m = model()
        m.run(0.5f, listOf(task("w", TaskUiStatus.Waiting), task("n", send = SendState.NotSent), task("q", TaskUiStatus.Queued)))
        val before = m.sats.filter { it.id != null }.associate { it.id to it.angle }
        m.run(1f, listOf(task("w", TaskUiStatus.Waiting), task("n", send = SendState.NotSent), task("q", TaskUiStatus.Queued)))
        for (sat in m.sats.filter { it.id != null }) assertEquals(sat.id, before[sat.id]!!, sat.angle, 0f)
        assertEquals(196f, m.sats.first { it.id == "q" }.orbit, 0f)
    }

    @Test fun framesOnlyWhileSomethingMoves() {
        val m = model()
        m.run(1f, emptyList())
        assertEquals(FrameNeed.Calm, m.frameNeed(speaking = false, pausing = false, jevInFlight = false))
        assertEquals(FrameNeed.Full, m.frameNeed(speaking = true, pausing = false, jevInFlight = false))
        m.run(0.5f, listOf(task("a")))
        assertEquals(FrameNeed.Full, m.frameNeed(speaking = false, pausing = false, jevInFlight = false))
    }

    @Test fun trailsGrowWithSteps() {
        assertTrue(SceneModel.trailMax(5) > SceneModel.trailMax(1))
        assertEquals(SceneModel.TRAIL_MAX, SceneModel.trailMax(100))
        val m = model()
        m.run(2f, listOf(task("a", steps = 1)))
        assertEquals(SceneModel.trailMax(1), m.sats.first { it.id == "a" }.trailLen)
    }

    @Test fun earlyTickFollowsThePauseSetting() {
        assertEquals(0.5f, SceneModel.earlyTickFraction(1000), 0f)
        assertEquals(0.5f, SceneModel.earlyTickFraction(400), 0f)
        assertEquals(500f / 3000f, SceneModel.earlyTickFraction(3000), 1e-6f)
    }

    @Test fun earlyTickLightsOnTheEarlyCallAndResetsWithTheNextPause() {
        val m = model()
        m.sync(emptyList(), offline = false, pausing = true)
        assertFalse(m.earlyTickLit)
        m.onEvent(CascadeEvent.JevFired(early = true))
        assertTrue(m.earlyTickLit)
        m.sync(emptyList(), offline = false, pausing = false)
        m.sync(emptyList(), offline = false, pausing = true)
        assertFalse(m.earlyTickLit)
    }

    @Test fun offlineDims() {
        val m = model()
        m.sync(emptyList(), offline = true, pausing = false)
        repeat(60) { m.step(1 / 60f, false, 0f, false) }
        assertTrue(m.dim > 0.9f)
    }
}
