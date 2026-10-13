package com.folio.hermes.cascade

import com.folio.hermes.cascade.routing.CascadeBrain
import com.folio.hermes.openrouter.FailureKind
import com.folio.hermes.openrouter.JevAnswer
import com.folio.hermes.openrouter.OpenRouterResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnTakerTest {
    private val bank = PhraseBank.load()

    /** One scripted Jev call: answer [result] after [delayMs]. */
    private class Script(val delayMs: Long, val result: OpenRouterResult<Map<String, JevAnswer>>)

    private class Call(val at: Long, val silenceMs: Long, val key: String, val timeoutMs: Long) {
        var cancelled = false
        var answeredAt: Long? = null
    }

    private inner class Harness(val scope: TestScope, settings: CascadeSettings = CascadeSettings()) {
        val scripts = ArrayDeque<Script>()
        val calls = mutableListOf<Call>()
        val events = mutableListOf<Pair<Long, TurnEvent>>()
        val fired = mutableListOf<Pair<Long, Boolean>>()
        val taker = TurnTaker(
            scope = scope,
            settings = { settings },
            context = { heard, pending, silence ->
                CascadeBrain.request(TurnContext(heard, pending, silence, emptyList(), emptyList(), false), bank, false)
            },
            decide = { req, timeout ->
                val call = Call(scope.testScheduler.currentTime, req.state["silence_ms"]!!.jsonPrimitive.long, req.key, timeout)
                calls += call
                val script = scripts.removeFirstOrNull() ?: Script(100, done())
                try {
                    delay(script.delayMs)
                    call.answeredAt = scope.testScheduler.currentTime
                    script.result
                } catch (e: CancellationException) {
                    call.cancelled = true
                    throw e
                }
            },
            onEvent = { events += scope.testScheduler.currentTime to it },
            now = { scope.testScheduler.currentTime },
            onJevFired = { fired += scope.testScheduler.currentTime to it },
        )

        fun say(text: String) {
            taker.speechStarted()
            taker.final(text)
            taker.speechEnded()
        }
    }

    private fun done(c: Double = 0.9) = OpenRouterResult.Ok(mapOf("turn" to answer("done", c), "action" to answer("reply")))
    private fun keep() = OpenRouterResult.Ok(mapOf("turn" to answer("keep_listening")))

    @Test fun earlyCallAtHalfThePauseButNothingActsBeforePauseMs() = runTest {
        val h = Harness(this)
        h.say("what's the weather")
        advanceUntilIdle()
        assertEquals(1, h.calls.size)
        assertEquals(500L, h.calls[0].at)
        assertEquals(500L, h.calls[0].silenceMs)
        assertEquals(2_500L, h.calls[0].timeoutMs)
        assertEquals(600L, h.calls[0].answeredAt)
        val (at, event) = h.events.single()
        assertEquals("committed at pause_ms, not when Jev answered", 1_000L, at)
        event as TurnEvent.Done
        assertEquals("what's the weather", event.heard)
        assertEquals(JevTiming(speechEndToJevMs = 600, jevMs = 100, calls = 1), event.timing)
        assertEquals(listOf(500L to true), h.fired)
        assertTrue(!h.taker.holding)
    }

    @Test fun earlyTimeFollowsThePauseSetting() = runTest {
        for ((pause, early) in listOf(400L to 200L, 1_000L to 500L, 3_000L to 500L)) {
            val h = Harness(this, CascadeSettings(pauseMs = pause))
            val start = testScheduler.currentTime
            h.say("hello")
            advanceUntilIdle()
            assertEquals("early at pause $pause", early, h.calls[0].at - start)
            assertEquals("commit at pause $pause", pause, h.events.single().first - start)
        }
    }

    @Test fun speechResumingCancelsTheCallInFlight() = runTest {
        val h = Harness(this)
        h.scripts += Script(1_000, done())
        h.say("book me a")
        advanceTimeBy(700)
        runCurrent()
        assertEquals(1, h.calls.size)
        h.taker.speechStarted()
        runCurrent()
        assertTrue("the early call was cancelled", h.calls[0].cancelled)
        h.taker.final("table for two")
        advanceTimeBy(5_000)
        assertTrue("no decision while talking", h.events.isEmpty())
        h.taker.speechEnded()
        advanceUntilIdle()
        val event = h.events.single().second as TurnEvent.Done
        assertEquals("book me a table for two", event.heard)
    }

    @Test fun unansweredEarlyCallRacesASecondCallAtPauseMs() = runTest {
        val h = Harness(this)
        h.scripts += Script(2_000, done()) // early: slow
        h.scripts += Script(150, done()) // commit call: fast
        h.say("start the stock check")
        advanceUntilIdle()
        assertEquals(2, h.calls.size)
        assertEquals(1_000L, h.calls[1].at)
        assertEquals(1_000L, h.calls[1].silenceMs)
        val (at, event) = h.events.single()
        assertEquals(1_150L, at)
        assertEquals(2, (event as TurnEvent.Done).timing.calls)
        assertTrue("the slow early call is dropped", h.calls[0].cancelled)
        assertEquals(listOf(true, false), h.fired.map { it.second })
    }

    @Test fun aLateFinalMakesTheEarlyAnswerStale() = runTest {
        val h = Harness(this)
        h.say("cancel the")
        advanceTimeBy(800)
        runCurrent()
        h.taker.final("newsletter") // STT final lands after the VAD end
        advanceUntilIdle()
        assertEquals(2, h.calls.size)
        assertTrue(h.calls[0].key != h.calls[1].key)
        assertEquals("cancel the newsletter", (h.events.single().second as TurnEvent.Done).heard)
    }

    @Test fun bothCallsFailingAsksToRepeatAndNeverActs() = runTest {
        val h = Harness(this)
        h.scripts += Script(100, OpenRouterResult.Failed(FailureKind.Timeout))
        h.scripts += Script(2_500, OpenRouterResult.Failed(FailureKind.Timeout))
        h.say("do the thing")
        advanceUntilIdle()
        val event = h.events.single().second as TurnEvent.NoAnswer
        assertEquals(FailureKind.Timeout, event.failure.kind)
        assertEquals("do the thing", event.heard)
        assertTrue(h.events.none { it.second is TurnEvent.Done })
    }

    @Test fun offlineIsReportedAsOffline() = runTest {
        val h = Harness(this)
        h.scripts += Script(10, OpenRouterResult.Failed(FailureKind.Offline))
        h.scripts += Script(10, OpenRouterResult.Failed(FailureKind.Offline))
        h.say("email the supplier")
        advanceUntilIdle()
        assertEquals(FailureKind.Offline, (h.events.single().second as TurnEvent.NoAnswer).failure.kind)
    }

    @Test fun lowConfidenceDoneKeepsListeningAndCollects() = runTest {
        val h = Harness(this)
        h.scripts += Script(50, done(0.5)) // then Jev is sure (the default) for the next words
        h.say("right so I want three things")
        advanceTimeBy(1_100)
        runCurrent()
        val holding = h.events.single().second as TurnEvent.Holding
        assertEquals(listOf("right so I want three things"), holding.pending)
        assertTrue(h.taker.holding)
        h.taker.speechStarted()
        h.taker.final("one chase the order")
        h.taker.speechEnded()
        advanceUntilIdle()
        val done = h.events.last().second as TurnEvent.Done
        assertEquals(listOf("right so I want three things"), done.pending)
        assertEquals("one chase the order", done.heard)
        assertTrue(!h.taker.holding)
    }

    @Test fun aRecheckCommitsHeldWordsAfterMoreSilence() = runTest {
        val h = Harness(this)
        h.scripts += Script(50, keep())
        h.scripts += Script(50, done())
        h.say("and also")
        advanceUntilIdle()
        assertEquals(listOf("Holding", "Done"), h.events.map { it.second::class.simpleName })
        assertEquals(1_000L + 2_500L + 50L, h.events.last().first)
        assertEquals(3_500L, h.calls.last().silenceMs)
        assertEquals(listOf("and also"), h.events.last().second.pending)
    }

    @Test fun aSecondKeepListeningParksTheWordsAndOpensTheGate() = runTest {
        val h = Harness(this)
        h.scripts += Script(50, keep())
        h.scripts += Script(50, keep())
        h.say("hmm")
        advanceUntilIdle()
        assertEquals(listOf("Holding", "Parked"), h.events.map { it.second::class.simpleName })
        assertTrue("results may play now", !h.taker.holding)
        assertEquals(listOf("hmm"), h.taker.pending)
    }
}
