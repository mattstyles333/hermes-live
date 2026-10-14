package com.folio.hermes.cascade

import com.folio.hermes.openrouter.JevAnswer
import com.folio.hermes.openrouter.OpenRouterResult
import com.folio.hermes.telemetry.TelemetrySink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The actor end to end on a virtual clock with fake ports (R-ARC-3). No network, no keys. */
class CascadeActorTest {
    private val bank = PhraseBank.load()

    private fun ok(vararg pairs: Pair<String, JevAnswer>) = OpenRouterResult.Ok(mapOf(*pairs))
    private val reply = ok("turn" to answer("done"), "action" to answer("reply"), "line" to answer("ack.understood"), "enough" to answer("line_then_writer"))

    private inner class Rig(val scope: TestScope, settings: CascadeSettings = CascadeSettings()) : AutoCloseable {
        /** On the test dispatcher (so advanceUntilIdle drives it) but not a child of the test: closed at the end. */
        val job = SupervisorJob()
        val runScope = CoroutineScope(scope.coroutineContext + job)
        val clock = TestClock(scope)
        val openRouter = FakeOpenRouter(clock)
        val hermes = FakeHermes()
        val audio = FakeAudioOut(clock)
        val events = mutableListOf<Pair<Long, CascadeEvent>>()
        val ended = mutableListOf<Boolean>()

        /** Retention is checked against the virtual wall clock. */
        val decisions = DecisionLog(MemoryDecisionStore(), now = clock::wallMs)
        val actor = CascadeActor(
            scope = runScope,
            env = CascadeEnv(bank),
            settings = { settings },
            openRouter = openRouter,
            hermes = hermes,
            audio = audio,
            clock = clock,
            decisions = decisions,
            telemetry = TelemetrySink.None,
            onEndCall = { ended += it },
            seed = 7,
        )

        init {
            runScope.launch { actor.events.collect { events += scope.testScheduler.currentTime to it } }
            scope.runCurrent()
            actor.start()
            scope.runCurrent()
        }

        fun say(text: String) {
            actor.speechStarted()
            actor.final(text)
            actor.speechEnded()
        }

        fun at(event: CascadeEvent): Long = events.first { it.second == event }.first

        /** The decision logged for a committed turn. */
        fun done(): DecisionRecord = decisions.records().single { it.outcome == "done" }

        override fun close() = job.cancel()
    }

    private fun rigTest(block: suspend TestScope.(Rig) -> Unit) = runTest { Rig(this).use { block(it) } }

    @Test fun earlyCallAtHalfThePauseCommitAtPauseMs() = rigTest { rig ->
        rig.openRouter.jevScripts += FakeOpenRouter.JevScript(100, reply)
        rig.say("what's the weather")
        advanceUntilIdle()
        val call = rig.openRouter.jevCalls.single()
        assertEquals(500L, call.at)
        assertEquals(500L, call.state["silence_ms"]!!.jsonPrimitive.long)
        assertEquals(2_500L, call.timeoutMs)
        assertEquals(600L, call.answeredAt)
        assertEquals("committed at pause_ms, not when Jev answered", 1_000L, rig.at(CascadeEvent.TurnCommitted("u1")))
        assertEquals(500L, rig.at(CascadeEvent.JevFired(true)))
        assertEquals(listOf(true), rig.events.mapNotNull { (it.second as? CascadeEvent.JevFired)?.early })
        assertEquals(1_000L, rig.at(CascadeEvent.JevAnswered(100)))
        val done = rig.done()
        assertEquals(1, done.jevCalls)
        assertEquals(600L, done.speechEndToJevMs)
        assertEquals(100L, done.jevMs)
        assertEquals("what's the weather", rig.actor.state.value.recent.single().user)
        assertFalse(rig.actor.state.value.speech.userHolding)
        // The stock line is cached in a real call; here it costs the fake's TTS latency, then plays.
        assertTrue(rig.events.any { it.second == CascadeEvent.LinePlayed("ack.understood") })
        assertEquals(listOf("Sure thing."), rig.actor.state.value.writer.sentences)
        assertFalse(rig.actor.speaking)
        assertEquals(TurnPhase.Idle, rig.actor.ui.value.turn.phase)
    }

    @Test fun aSlowEarlyCallIsRacedAndCancelled() = rigTest { rig ->
        rig.openRouter.jevScripts += FakeOpenRouter.JevScript(2_000, reply) // early: slow
        rig.openRouter.jevScripts += FakeOpenRouter.JevScript(150, reply) // commit call: fast
        rig.say("start the stock check")
        advanceUntilIdle()
        assertEquals(2, rig.openRouter.jevCalls.size)
        assertEquals(1_000L, rig.openRouter.jevCalls[1].at)
        assertEquals(1_000L, rig.openRouter.jevCalls[1].state["silence_ms"]!!.jsonPrimitive.long)
        assertEquals(1_150L, rig.at(CascadeEvent.TurnCommitted("u1")))
        assertEquals(2, rig.done().jevCalls)
        assertTrue("the slow early call is dropped", rig.openRouter.jevCalls[0].cancelled)
        assertEquals(listOf(true, false), rig.events.mapNotNull { (it.second as? CascadeEvent.JevFired)?.early })
    }

    @Test fun aLateFinalMakesTheEarlyAnswerStale() = rigTest { rig ->
        rig.say("cancel the")
        advanceTimeBy(800)
        runCurrent()
        rig.actor.final("newsletter") // STT final lands after the VAD end
        advanceUntilIdle()
        assertEquals(2, rig.openRouter.jevCalls.size)
        assertEquals("cancel the newsletter", rig.openRouter.jevCalls[1].state["utterance"]!!.jsonPrimitive.content)
        assertEquals("cancel the newsletter", rig.actor.state.value.recent.single().user)
    }

    @Test fun speechResumingCancelsTheCallInFlight() = rigTest { rig ->
        rig.openRouter.jevScripts += FakeOpenRouter.JevScript(1_000, reply)
        rig.say("book me a")
        advanceTimeBy(700)
        runCurrent()
        rig.actor.speechStarted()
        runCurrent()
        assertTrue(rig.openRouter.jevCalls[0].cancelled)
        rig.actor.final("table for two")
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue("no decision while talking", rig.events.none { it.second is CascadeEvent.TurnCommitted })
        rig.actor.speechEnded()
        advanceUntilIdle()
        assertEquals("book me a table for two", rig.actor.state.value.recent.single().user)
    }

    @Test fun aNewTaskRunsAndItsResultWaitsForTheUser() = rigTest { rig ->
        rig.openRouter.jevScripts += FakeOpenRouter.JevScript(
            100,
            ok("turn" to answer("done"), "action" to answer("new_task"), "line" to answer("start.lookup"), "enough" to answer("line_then_writer")),
        )
        rig.say("check stock of RB2140")
        advanceUntilIdle()
        val submit = rig.hermes.submits.single()
        assertEquals("Check RB2140 stock.", submit.text)
        assertFalse(submit.queued)
        assertEquals(Triple("t1", "stock check", null), rig.hermes.opened.single())
        assertEquals(SendState.Accepted, rig.actor.ui.value.tasks.single().send)
        assertTrue(rig.openRouter.clips.contains("Checking the stock now."))

        // The user starts talking; the result lands; it waits for them to finish.
        rig.actor.speechStarted()
        runCurrent()
        rig.hermes.complete("t1", "RB2140: 14 in stock")
        advanceTimeBy(3_000)
        runCurrent()
        assertTrue(rig.events.any { it.second == CascadeEvent.ResultQueued("t1") })
        assertTrue("never over the user", rig.events.none { it.second == CascadeEvent.ResultSpoken("t1") })
        rig.actor.speechEnded() // nothing heard: no pause
        advanceUntilIdle()
        assertTrue(rig.events.any { it.second == CascadeEvent.ResultSpoken("t1") })
        assertEquals(TaskUiStatus.Done, rig.actor.ui.value.tasks.single().status)
        assertFalse("read out", rig.actor.ui.value.tasks.single().unread)
        assertEquals(1, rig.hermes.submits.size)
    }

    @Test fun cuttingInStopsBillAndDropsTheRestOfHisReply() = rigTest { rig ->
        rig.openRouter.jevScripts += FakeOpenRouter.JevScript(100, reply)
        rig.openRouter.writes = { _, _ -> listOf("One.", "Two.", "Three.", "Four.") }
        rig.say("tell me a story")
        advanceTimeBy(1_000 + 300 + 100) // committed, stock line fetched, playing
        runCurrent()
        assertTrue(rig.actor.ui.value.bill.speaking)
        rig.actor.userSpeech(300)
        advanceUntilIdle()
        assertTrue(rig.events.any { it.second == CascadeEvent.UserCutIn })
        assertEquals("only the cut-short line played", 1, rig.audio.played.size)
        assertTrue(rig.audio.played.single().second < rig.openRouter.clipMs)
        assertFalse(rig.actor.speaking)
    }

    @Test fun endCallTellsTheApp() = rigTest { rig ->
        rig.openRouter.jevScripts += FakeOpenRouter.JevScript(100, ok("turn" to answer("done"), "action" to answer("end_call")))
        rig.say("that's all, bye")
        advanceUntilIdle()
        assertEquals(listOf(false), rig.ended)
    }

    @Test fun stopForgetsHeldWordsAndStopsBill() = rigTest { rig ->
        rig.actor.say(AppLines.HI)
        advanceTimeBy(350)
        runCurrent()
        rig.actor.speechStarted()
        rig.actor.final("hold on")
        rig.actor.stop()
        advanceUntilIdle()
        val s = rig.actor.state.value
        assertFalse(s.started)
        assertEquals("", s.turn.heard)
        assertFalse(s.speaking)
    }

    @Test fun stopThenStartLeavesBillAbleToSpeak() = rigTest { rig ->
        rig.openRouter.jevScripts += FakeOpenRouter.JevScript(100, reply)
        rig.openRouter.writes = { _, _ -> listOf("One.", "Two.", "Three.") }
        rig.say("tell me a story")
        advanceTimeBy(1_000 + 300 + 100) // the stock line is playing, the writer's sentences are queued
        runCurrent()
        assertTrue(rig.actor.ui.value.bill.speaking)
        rig.actor.stop()
        advanceTimeBy(5_000)
        runCurrent()
        assertFalse(rig.actor.speaking)
        rig.actor.start()
        rig.actor.say(AppLines.HI)
        advanceUntilIdle()
        val hi = rig.events.last { it.second is CascadeEvent.LinePlayed }.second
        assertEquals(CascadeEvent.LinePlayed(AppLines.HI), hi)
        assertFalse(rig.actor.speaking)
    }

    @Test fun aTaskKeepsRunningAcrossStopAndItsResultWaitsForTheNextCall() = rigTest { rig ->
        rig.openRouter.jevScripts += FakeOpenRouter.JevScript(
            100,
            ok("turn" to answer("done"), "action" to answer("new_task"), "line" to answer("start.lookup"), "enough" to answer("line_then_writer")),
        )
        rig.say("check stock of RB2140")
        advanceUntilIdle()
        rig.actor.stop()
        advanceUntilIdle()
        rig.hermes.complete("t1", "RB2140: 14 in stock")
        advanceUntilIdle()
        assertEquals("the run ended while no call was open", TaskUiStatus.Done, rig.actor.ui.value.tasks.single().status)
        assertTrue("nothing is spoken off-call", rig.events.none { it.second == CascadeEvent.ResultQueued("t1") })
        assertEquals(1, rig.actor.state.value.offCall.results.size)

        rig.actor.start()
        advanceUntilIdle()
        assertEquals(1, rig.events.count { it.second == CascadeEvent.ResultQueued("t1") })
        assertTrue(rig.events.any { it.second == CascadeEvent.ResultSpoken("t1") })
        assertTrue(rig.actor.state.value.offCall.results.isEmpty())
        assertEquals(1, rig.hermes.submits.size)
    }
}
