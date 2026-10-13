package com.folio.hermeslive.ui.cascade

import com.folio.hermes.cascade.CascadeEvent
import com.folio.hermes.cascade.TaskUiStatus
import com.folio.hermes.cascade.TurnPhase
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeCascadeUiTest {
    private val fake = FakeCascadeUi()

    @Test fun replaysThePrototypeMoments() {
        assertEquals("Hey. What's up?", fake.stateAt(1_000).bill.text)
        val pausing = fake.stateAt(5_100)
        assertEquals(TurnPhase.Pausing, pausing.turn.phase)
        assertEquals(0.5f, pausing.turn.pauseProgress, 1e-3f)
        assertTrue(pausing.jev.inFlight)
        val running = fake.stateAt(13_500)
        assertEquals(listOf("a", "b"), running.tasks.map { it.id })
        assertEquals(3, running.tasks.first().steps)
        val done = fake.stateAt(21_000)
        assertTrue(done.tasks.all { it.status == TaskUiStatus.Done })
        assertEquals(fake.stateAt(13_500), fake.stateAt(13_500))
    }

    @Test fun eventsComeInOrderAndOnce() {
        val all = fake.eventsBetween(-1, FakeCascadeUi.DURATION_MS)
        assertEquals(all.map { it.first }.sorted(), all.map { it.first })
        assertEquals(2, all.count { it.second is CascadeEvent.TaskFinished })
        assertEquals(fake.eventsBetween(-1, 10_000).size + fake.eventsBetween(10_000, FakeCascadeUi.DURATION_MS).size, all.size)
    }

    @Test fun playEmitsTheScriptOnVirtualTime() = runTest {
        val seen = mutableListOf<CascadeEvent>()
        val job = launch { fake.events.collect { seen += it } }
        testScheduler.runCurrent()
        fake.play(frameMs = 100)
        job.cancel()
        assertEquals(fake.eventsBetween(-1, FakeCascadeUi.DURATION_MS).map { it.second }, seen)
        assertEquals(FakeCascadeUi.DURATION_MS, fake.nowMs)
    }
}
