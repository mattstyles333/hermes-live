package com.folio.hermes.cascade

import com.folio.hermes.cascade.ports.SpeechToText
import com.folio.hermes.cascade.ports.SttEvent
import com.folio.hermes.openrouter.JevAnswer
import com.folio.hermes.openrouter.OpenRouterResult
import com.folio.hermes.telemetry.TelemetrySink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec v2 §14 follow-ups from S-C's report: the pause counts from the real end of speech
 * (`SpeechEndedAt`), a hold-to-talk release commits at once, and the accurate pass is waited
 * for (bounded) on committed turns while an ignored turn drops its audio.
 */
class SpeechTimingTest {
    private val bank = PhraseBank.load()
    private fun ok(vararg pairs: Pair<String, JevAnswer>) = OpenRouterResult.Ok(mapOf(*pairs))

    private class Run(val reducer: CascadeReducer, settings: CascadeSettings = CascadeSettings()) {
        var s = reducer.reduce(State(settings = settings), Event.Start()).first
        val effects = mutableListOf<Effect>()

        fun at(ms: Long, event: Event): List<Effect> {
            val (next, fx) = reducer.reduce(s.copy(nowMs = ms, wallMs = ms), event)
            s = next
            effects += fx
            return fx
        }
    }

    private fun run(settings: CascadeSettings = CascadeSettings()) = Run(CascadeReducer(CascadeEnv(bank)), settings)

    @Test fun thePauseCountsFromTheRealEndOfSpeech() {
        val r = run()
        r.at(0, Event.SpeechStarted)
        r.at(0, Event.Final("book the meeting room"))
        // The VAD said "ended" 400 ms after the user stopped.
        val fx = r.at(1_400, Event.SpeechEndedAt(1_000))
        val early = fx.filterIsInstance<Effect.StartTimer>().single { it.timer.name == TurnRules.EARLY }
        assertEquals("early call at 500 ms after the real end", 100L, early.delayMs)
        assertEquals(1_000L, r.s.turn.pause!!.endAt)
        assertEquals("the ring starts at the real end too", 1_000L, r.s.tickBegan)
        r.at(1_500, Event.TimerFired(early.timer))
        val commit = r.effects.filterIsInstance<Effect.StartTimer>().single { it.timer.name == TurnRules.COMMIT }
        assertEquals("commit at pause_ms after the real end", 500L, commit.delayMs)
    }

    @Test fun aStampFromAnotherClockIsClamped() {
        val r = run()
        r.at(0, Event.SpeechStarted)
        r.at(0, Event.Final("hello"))
        val past = r.at(10_000, Event.SpeechEndedAt(1)).filterIsInstance<Effect.StartTimer>().single { it.timer.name == TurnRules.EARLY }
        assertEquals("at most the early wait is skipped", 0L, past.delayMs)
        val r2 = run()
        r2.at(0, Event.SpeechStarted)
        r2.at(0, Event.Final("hello"))
        val future = r2.at(1_000, Event.SpeechEndedAt(9_000)).filterIsInstance<Effect.StartTimer>().single { it.timer.name == TurnRules.EARLY }
        assertEquals(500L, future.delayMs)
    }

    @Test fun plainSpeechEndedStillCountsFromArrival() {
        val r = run()
        r.at(0, Event.SpeechStarted)
        r.at(0, Event.Final("hello"))
        val fx = r.at(2_000, Event.SpeechEnded).filterIsInstance<Effect.StartTimer>().single { it.timer.name == TurnRules.EARLY }
        assertEquals(500L, fx.delayMs)
    }

    @Test fun holdToTalkReleaseCommitsAtOnceWhateverTurnSays() {
        val r = run()
        r.at(0, Event.HoldToTalk(true))
        r.at(0, Event.SpeechStarted)
        r.at(100, Event.Final("draft the reorder"))
        r.at(3_000, Event.HoldToTalk(false))
        val fx = r.at(3_010, Event.SpeechEndedAt(3_000))
        val call = fx.filterIsInstance<Effect.CallJev>().single()
        assertTrue("no pause timer", fx.filterIsInstance<Effect.StartTimer>().none { it.timer.name == TurnRules.EARLY || it.timer.name == TurnRules.COMMIT })
        assertFalse(r.s.transcript.released)
        // Jev thinks the user is mid-thought; the release says otherwise.
        r.at(3_300, Event.JevAnswered(call.seq, ok("turn" to answer("keep_listening"), "action" to answer("reply"), "enough" to answer("line_then_writer")), 3_300))
        assertTrue(r.effects.filterIsInstance<Effect.Emit>().any { it.event == CascadeEvent.TurnCommitted("u1") })
        assertTrue(r.s.turn.pending.isEmpty())
    }

    @Test fun aReleaseWithNoSpeechOpenCommitsTheWordsNow() {
        val r = run()
        r.at(0, Event.SpeechStarted)
        r.at(0, Event.Final("order more cases"))
        r.at(100, Event.SpeechEnded)
        val fx = r.at(200, Event.HoldToTalk(false))
        assertEquals(1, fx.filterIsInstance<Effect.CallJev>().size)
        assertTrue("the pause timers are cancelled", fx.filterIsInstance<Effect.CancelTimer>().any { it.timer.name == TurnRules.COMMIT })
    }

    @Test fun aCommittedTurnWaitsForTheAccuratePassWhenItIsOn() {
        val r = run(CascadeSettings(accuratePass = true))
        r.at(0, Event.SpeechStarted)
        r.at(0, Event.Final("email Sapphire"))
        r.at(0, Event.SpeechEnded)
        val early = r.at(500, Event.TimerFired(Timer(TurnRules.EARLY, 1))).filterIsInstance<Effect.CallJev>().single()
        r.at(600, Event.JevAnswered(early.seq, ok("turn" to answer("done"), "action" to answer("reply"), "enough" to answer("line_then_writer")), 600))
        val commit = r.at(1_000, Event.TimerFired(Timer(TurnRules.COMMIT, 1)))
        assertTrue(Effect.AccuratePass("u1") in commit)
        assertTrue("held behind the accurate gate", commit.none { it is Effect.StreamWriter })
        // The pass gave nothing within its wait (null): the turn goes on with the fast text.
        val go = r.at(2_500, Event.AccurateFinal("u1", null))
        assertTrue(go.any { it is Effect.StreamWriter })
    }

    @Test fun anIgnoredTurnDropsItsAudio() {
        val r = run(CascadeSettings(accuratePass = true))
        r.at(0, Event.SpeechStarted)
        r.at(0, Event.Final("no not you love"))
        r.at(0, Event.SpeechEnded)
        val early = r.at(500, Event.TimerFired(Timer(TurnRules.EARLY, 1))).filterIsInstance<Effect.CallJev>().single()
        r.at(600, Event.JevAnswered(early.seq, ok("turn" to answer("ignore", 0.9)), 600))
        val fx = r.at(1_000, Event.TimerFired(Timer(TurnRules.COMMIT, 1)))
        assertTrue(Effect.DropSpeech("u1") in fx)
        assertTrue(fx.none { it is Effect.AccuratePass })
    }

    @Test fun offByDefaultNothingWaitsAndNothingIsDropped() {
        val r = run()
        r.at(0, Event.SpeechStarted)
        r.at(0, Event.Final("email Sapphire"))
        r.at(0, Event.SpeechEnded)
        val early = r.at(500, Event.TimerFired(Timer(TurnRules.EARLY, 1))).filterIsInstance<Effect.CallJev>().single()
        r.at(600, Event.JevAnswered(early.seq, ok("turn" to answer("done"), "action" to answer("reply"), "enough" to answer("line_then_writer")), 600))
        val commit = r.at(1_000, Event.TimerFired(Timer(TurnRules.COMMIT, 1)))
        assertTrue(commit.none { it is Effect.AccuratePass || it is Effect.DropSpeech })
        assertTrue(commit.any { it is Effect.StreamWriter })
    }

    /** A speech-to-text whose accurate pass never answers, and which counts drops. */
    private class SlowStt : SpeechToText {
        val channel = Channel<SttEvent>(Channel.UNLIMITED)
        val never = CompletableDeferred<Transcript?>()
        var asked = 0
        val dropped = mutableListOf<String>()
        override val events: Flow<SttEvent> = channel.receiveAsFlow()
        override suspend fun accurate(turnId: String): Transcript? {
            asked++
            return never.await()
        }
        override fun drop(turnId: String) {
            dropped += turnId
        }
    }

    @Test fun theActorBoundsTheAccuratePassWait() = runTest {
        val job = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + job)
        val clock = TestClock(this)
        val openRouter = FakeOpenRouter(clock)
        openRouter.jevScripts += FakeOpenRouter.JevScript(100, ok("turn" to answer("done"), "action" to answer("reply"), "enough" to answer("line_then_writer")))
        val stt = SlowStt()
        val settings = CascadeSettings(accuratePass = true, accuratePassWaitMs = 2_500)
        val actor = CascadeActor(scope, CascadeEnv(bank), { settings }, openRouter, FakeHermes(), FakeAudioOut(clock), clock, telemetry = TelemetrySink.None, stt = stt, seed = 3)
        try {
            actor.start()
            runCurrent()
            stt.channel.send(SttEvent.SpeechStarted)
            stt.channel.send(SttEvent.Final("what's on today"))
            stt.channel.send(SttEvent.SpeechEndedAt(clock.nowMs()))
            advanceTimeBy(1_001)
            runCurrent()
            assertEquals(1, stt.asked)
            assertTrue("waiting for the pass", actor.state.value.writer.sentences.isEmpty())
            advanceTimeBy(2_400)
            runCurrent()
            assertTrue(actor.state.value.writer.sentences.isEmpty())
            advanceTimeBy(200)
            advanceUntilIdle()
            assertEquals("after 2.5 s the fast text goes on", listOf("Sure thing."), actor.state.value.writer.sentences)
            assertNull(actor.state.value.transcript.byTurn["u1"]?.takeIf { it.pass == TranscriptPass.Accurate })
        } finally {
            job.cancel()
        }
    }
}
