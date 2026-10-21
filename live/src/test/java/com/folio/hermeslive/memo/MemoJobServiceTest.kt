package com.folio.hermeslive.memo

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowJobService

/** The job, driven: it waits for the account, flushes, holds the gateway for a sent memo's task, and asks for a retry only while a memo waits. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MemoJobServiceTest {
    private class Fakes(var held: Int = 0, var sent: Int = 0, running: Int = 0, var open: Int = 0) {
        val ready = CompletableDeferred<Unit>()
        var flushes = 0
        var holds = 0
        var released = 0
        val running = MutableStateFlow(running)
        var noHost = false
        fun work() = MemoJobWork(
            ready = { ready.await() },
            flush = { if (noHost) null else MemoSender.Flush(sent, held, open).also { flushes++ } },
            holdGateway = { holds++; AutoCloseable { released++ } },
            tasksRunning = running,
            memosHeld = { held > 0 },
        )
    }

    private fun TestScope.service(f: Fakes): Pair<MemoJobService, ShadowJobService> {
        val s = Robolectric.buildService(MemoJobService::class.java).create().get()
        s.work = { f.work() }
        s.scope = CoroutineScope(backgroundScope.coroutineContext)
        return s to Shadow.extract(s)
    }

    @Test fun itWaitsForTheAccountThenAsksForARetryWhileAMemoWaits() = runTest {
        val f = Fakes(held = 1)
        val (svc, shadow) = service(f)
        assertTrue("the work goes on after onStartJob returns", svc.onStartJob(null))
        runCurrent()
        assertEquals("not before the account is read back", 0, f.flushes)
        assertFalse(shadow.isJobFinished)
        f.ready.complete(Unit)
        runCurrent()
        assertEquals(1, f.flushes)
        assertTrue(shadow.isJobFinished)
        assertTrue("a memo still waits: the job's own backoff tries again", shadow.isRescheduleNeeded)
    }

    @Test fun aSentMemoHoldsTheGatewayUntilItsTaskIsDoneThenNoRetry() = runTest {
        val f = Fakes(sent = 1, running = 1)
        val (svc, shadow) = service(f)
        f.ready.complete(Unit)
        svc.onStartJob(null)
        runCurrent()
        assertEquals(1, f.holds)
        assertFalse("waits for the task's result", shadow.isJobFinished)
        f.running.value = 0
        runCurrent()
        assertEquals(1, f.released)
        assertTrue(shadow.isJobFinished)
        assertFalse(shadow.isRescheduleNeeded)
    }

    /** Review: woken where the app-start flush already sent it, the job still waits for the open task's result. */
    @Test fun anOpenTaskIsWaitedForEvenWhenThisFlushSentNothing() = runTest {
        val f = Fakes(open = 1, running = 1)
        val (svc, shadow) = service(f)
        f.ready.complete(Unit)
        svc.onStartJob(null)
        runCurrent()
        assertEquals(1, f.holds)
        assertFalse(shadow.isJobFinished)
        f.running.value = 0
        runCurrent()
        assertEquals(1, f.released)
        assertTrue(shadow.isJobFinished)
    }

    @Test fun theHoldEndsWithinTheJobsBudget() = runTest {
        val f = Fakes(sent = 1, running = 1)
        val (svc, shadow) = service(f)
        f.ready.complete(Unit)
        svc.onStartJob(null)
        advanceTimeBy(MemoJobWork.HOLD_MS + 1)
        assertEquals(1, f.released)
        assertTrue(shadow.isJobFinished)
    }

    @Test fun nothingToDoFinishesWithoutARetry() = runTest {
        val f = Fakes().apply { noHost = true }
        val (svc, shadow) = service(f)
        f.ready.complete(Unit)
        svc.onStartJob(null)
        runCurrent()
        assertTrue(shadow.isJobFinished)
        assertFalse(shadow.isRescheduleNeeded)
        assertEquals(0, f.holds)
    }

    @Test fun onStopJobStopsTheWorkAndAsksToRescheduleOnlyWhileAMemoWaits() = runTest {
        val f = Fakes(held = 1)
        val (svc, _) = service(f)
        svc.onStartJob(null)
        runCurrent()
        assertTrue(svc.onStopJob(null))
        f.ready.complete(Unit)
        runCurrent()
        assertEquals("stopped before it flushed", 0, f.flushes)
        f.held = 0
        assertFalse(svc.onStopJob(null))
    }
}
