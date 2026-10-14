package com.folio.hermeslive.cascade

import com.folio.hermes.cascade.AppLines
import com.folio.hermes.cascade.AudioClip
import com.folio.hermes.cascade.Event
import com.folio.hermes.cascade.PhraseBank
import com.folio.hermes.cascade.SpeechKind
import com.folio.hermes.cascade.ports.AudioOut
import com.folio.hermes.cascade.ports.SttEvent
import com.folio.hermeslive.audio.CascadeAudioFactory
import com.folio.hermeslive.audio.CascadeAudioIo
import com.folio.hermeslive.audio.Dbfs
import com.folio.hermeslive.audio.PlaybackMonitor
import com.folio.hermeslive.call.CallPhase
import com.folio.hermeslive.call.CallRequest
import com.folio.hermeslive.call.Earcon
import com.folio.hermeslive.call.FakeAudio
import com.folio.hermeslive.call.FakeEffects
import com.folio.hermeslive.data.LiveSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bill's goodbye at hang-up (the final slice): it plays to the end while the end animation runs,
 * then the call's audio is released, at most 2 s after the hang-up; the End button says a short
 * one unless someone is speaking. Nothing heavy runs on the hang-up's own frame (the 0.5 s freeze).
 */
class CascadeGoodbyeTest {
    /** A speaker that takes [clipMs] (virtual time) per clip, like `AudioTrackOut` (returns once drained), and logs it. */
    class TimedOut(private val clipMs: Long, private val now: () -> Long) : AudioOut, PlaybackMonitor {
        /** (started, ended or null if stopped first) per clip. */
        val plays = mutableListOf<Pair<Long, Long?>>()
        @Volatile private var stopped = false

        override suspend fun play(clip: AudioClip): Long {
            stopped = false
            val at = now()
            plays += at to null
            var played = 0L
            while (played < clipMs && !stopped) {
                delay(10)
                played += 10
            }
            if (!stopped) plays[plays.lastIndex] = at to now()
            return played
        }

        override fun stop() {
            stopped = true
        }

        override val playing: Boolean get() = false
        override fun levelDb(): Double = Dbfs.FLOOR
        override fun playedMs(): Long = 0
    }

    private inner class Rig(val test: TestScope, clipMs: Long) : AutoCloseable {
        val job = SupervisorJob()
        val scope = CoroutineScope(test.coroutineContext + job)
        val rt = TestRuntime(scope)
        val speech = FakeSpeechFactory()
        val audio = FakeAudio()
        val effects = FakeEffects()
        val out = TimedOut(clipMs) { test.testScheduler.currentTime }
        var released = 0
        var releasedAt = -1L
        val engine = CascadeCallEngine(
            scope = scope,
            settings = { LiveSettings() },
            runtime = { rt.runtime },
            speech = speech,
            audioFactory = { CascadeAudioFactory { _, _ -> CascadeAudioIo(ToneSource(), out, out) { released++; releasedAt = test.testScheduler.currentTime } } },
            audio = audio,
            effects = effects,
            offCall = { true },
            io = StandardTestDispatcher(test.testScheduler),
            clock = { test.testScheduler.currentTime },
        )

        fun live() {
            engine.start(CallRequest(entry = "test"))
            test.runCurrent()
        }

        val byeLines: List<String> = PhraseBank.load()[AppLines.BYE]!!.lines + PhraseBank.load()[AppLines.BYE_TASKS]!!.lines

        fun byesFetched(): Int = rt.openRouter.clips.count { it in byeLines }

        override fun close() = job.cancel()
    }

    private fun rig(clipMs: Long = 1_000, block: suspend TestScope.(Rig) -> Unit) = runTest { Rig(this, clipMs).use { block(it) } }

    @Test fun theEndButtonSaysGoodbyeAndItPlaysToTheEndBeforeTheAudioGoes() = rig { r ->
        r.live()
        val tap = testScheduler.currentTime
        r.engine.end()
        // The tap's own frame: the end animation starts; nothing that blocks runs here.
        assertEquals(CallPhase.Ended, r.engine.state.value.phase)
        assertEquals("the call audio mode is not restored on the tap's frame", 0, r.audio.ended)
        assertEquals(0, r.released)
        assertEquals("the mic and models are not released on the tap's frame", 0, r.speech.built.single().released)
        runCurrent()
        assertEquals("the mic stops at once (off the main thread)", 1, r.speech.built.single().released)
        assertEquals(1, r.byesFetched())
        assertEquals("audio kept while the goodbye plays", 0, r.released)
        advanceTimeBy(500)
        assertTrue("Bill still running his goodbye", r.rt.core.actor.state.value.started)
        assertEquals(0, r.audio.ended)
        advanceTimeBy(600)
        runCurrent()
        val (start, end) = r.out.plays.last()
        assertEquals("the clip played to completion", 1_000L, end!! - start)
        assertEquals(1, r.released)
        assertTrue("released only after the clip ended", r.releasedAt >= end!!)
        assertTrue("within the 2 s cap", r.releasedAt - tap <= CascadeCallEngine.GOODBYE_CAP_MS)
        assertEquals(1, r.audio.ended)
        assertFalse(r.rt.core.actor.state.value.started)
        assertTrue(Earcon.Ended in r.effects.earcons)
    }

    @Test fun billsOwnGoodbyeIsHeardOnceAndToTheEnd() = rig { r ->
        r.live()
        // Effect.EndCall: the end_call action queued the line, then asked the app to end the call.
        r.rt.core.actor.send(Event.Say(AppLines.BYE, SpeechKind.Reply))
        runCurrent()
        r.engine.goodbyeSaid()
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals("no second goodbye", 1, r.byesFetched())
        val (start, end) = r.out.plays.last()
        assertEquals(1_000L, end!! - start)
        assertEquals(1, r.released)
        assertTrue(r.releasedAt >= end)
    }

    @Test fun aLongGoodbyeIsCutAtTheCap() = rig(clipMs = 5_000) { r ->
        r.live()
        val tap = testScheduler.currentTime
        r.engine.end()
        advanceTimeBy(CascadeCallEngine.GOODBYE_CAP_MS + 50)
        runCurrent()
        assertEquals(1, r.released)
        assertEquals(tap + CascadeCallEngine.GOODBYE_CAP_MS, r.releasedAt)
        assertEquals(1, r.audio.ended)
        assertEquals("stopped, not finished", null, r.out.plays.last().second)
    }

    @Test fun hangingUpMidSpeechSaysNothing() = rig { r ->
        r.live()
        r.speech.built.single().channel.trySend(SttEvent.SpeechStarted)
        runCurrent()
        assertTrue(r.rt.core.actor.state.value.userSpeaking)
        r.engine.end()
        runCurrent()
        assertEquals(0, r.byesFetched())
        assertEquals("released at once", 1, r.released)
        assertEquals(1, r.audio.ended)
    }

    @Test fun wordsStillBeingHeardEndTheCallAtOnceWithNoGoodbye() = rig { r ->
        r.live()
        r.speech.built.single().channel.trySend(SttEvent.Final("check the stock"))
        runCurrent()
        assertFalse(r.rt.core.actor.state.value.userSpeaking)
        r.engine.end()
        runCurrent()
        assertEquals("a goodbye must never let a turn commit after the hang-up", 0, r.byesFetched())
        assertEquals(1, r.released)
        assertFalse(r.rt.core.actor.state.value.started)
    }

    @Test fun aSecondEndPressCutsTheGoodbyeShort() = rig { r ->
        r.live()
        r.engine.end()
        advanceTimeBy(200)
        r.engine.end()
        assertEquals(1, r.released)
        assertEquals(1, r.audio.ended)
        advanceTimeBy(3_000)
        assertEquals("once", 1, r.released)
        assertEquals(1, r.audio.ended)
    }

    @Test fun talkingAgainDuringTheGoodbyeReleasesTheLastCallFirstAndKeepsTheNewOne() = rig { r ->
        r.live()
        r.engine.end()
        advanceTimeBy(300)
        r.engine.start(CallRequest(entry = "test"))
        assertEquals("the last call's audio mode ended before the new one began", 1, r.audio.ended)
        assertEquals(2, r.audio.began)
        runCurrent()
        assertEquals(CallPhase.Live, r.engine.state.value.phase)
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(CallPhase.Live, r.engine.state.value.phase)
        assertTrue("the old goodbye's teardown never stops the new call", r.rt.core.actor.state.value.started)
        assertEquals(1, r.audio.ended)
        assertEquals(1, r.released)
    }
}
