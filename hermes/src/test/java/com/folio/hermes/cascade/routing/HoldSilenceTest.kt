package com.folio.hermes.cascade.routing

import com.folio.hermes.cascade.AppLines
import com.folio.hermes.cascade.CascadeEvent
import com.folio.hermes.cascade.Effect
import com.folio.hermes.cascade.Event
import com.folio.hermes.cascade.Timer
import com.folio.hermes.cascade.TurnRules
import com.folio.hermes.cascade.answer
import com.folio.hermes.openrouter.FailureKind
import com.folio.hermes.openrouter.JevAnswer
import com.folio.hermes.openrouter.OpenRouterResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hold-silence rule (2026-10-14), through the pure `reduce` on CEO routing: words held after
 * the keep_listening recheck never wait forever in silence. Found on the emulator smoke: the fast
 * pass heard "… we have in sto", Jev said `turn = done` at 0.51 (under the 0.60 floor) twice, and
 * the turn stayed held with Bill silent.
 *
 * Timeline of [hold]: speech ends at 0; early call 500; commit point 1,000 (Holding); recheck
 * 3,500, answered 3,800 (Parked); the silence fires at 7,800, then 11,800.
 */
class HoldSilenceTest {
    private val clipped = "check how many Kestrel aviators we have in sto"

    private fun unsure(action: String = "new_task", actionConf: Double = 0.98, turnConf: Double = 0.51): Map<String, JevAnswer> = mapOf(
        "turn" to answer("done", turnConf), "action" to answer(action, actionConf), "line" to answer("start.one"),
        "split" to answer("one"), "clarity" to answer("clear"),
    )

    /** Say [text], Jev unsure at the pause ([first]); the recheck answers [recheck]. Leaves the harness at 3,800 (parked). */
    private fun hold(h: CeoHarness, text: String, first: Map<String, JevAnswer>, recheck: OpenRouterResult<Map<String, JevAnswer>> = OpenRouterResult.Ok(first)) {
        h.at(0, Event.SpeechStarted)
        h.at(0, Event.Final(text))
        h.at(0, Event.SpeechEnded)
        val early = h.at(500, Event.TimerFired(Timer(TurnRules.EARLY, h.s.turn.pauseSeq))).filterIsInstance<Effect.CallJev>().single()
        h.at(600, Event.JevAnswered(early.seq, OpenRouterResult.Ok(first), 600))
        h.at(1_000, Event.TimerFired(Timer(TurnRules.COMMIT, h.s.turn.pauseSeq)))
        assertEquals(listOf(text), h.s.turn.pending)
        val re = h.at(3_500, Event.TimerFired(Timer(TurnRules.RECHECK, h.s.turn.pauseSeq))).filterIsInstance<Effect.CallJev>().single()
        h.at(3_800, Event.JevAnswered(re.seq, recheck, 3_800))
        assertTrue("parked", h.s.turn.parked)
        assertNull(h.s.turn.pause)
    }

    private fun silenceTimer(h: CeoHarness) = h.effects.filterIsInstance<Effect.StartTimer>().last { it.timer.name == TurnRules.SILENCE }

    private fun fireSilence(h: CeoHarness, at: Long) = h.at(at, Event.TimerFired(Timer(TurnRules.SILENCE, h.s.turn.silence!!.seq)))

    private fun committed(h: CeoHarness) = h.emitted().filterIsInstance<CascadeEvent.TurnCommitted>()

    private fun decisions(h: CeoHarness) = h.effects.filterIsInstance<Effect.LogDecision>().map { it.record }

    /** The `cascade.turn` telemetry for committed turns: how each was committed (`via`), in order. */
    private fun commitsVia(h: CeoHarness) = h.effects.filterIsInstance<Effect.Report>().filter { it.name == "cascade.turn" && it.attrs["outcome"] == "done" }.map { it.attrs["via"] }

    @Test fun aHeldRequestCommitsAfterFourSecondsOfSilence() {
        val h = CeoHarness()
        hold(h, clipped, unsure())
        assertEquals(4_000L, silenceTimer(h).delayMs)
        assertTrue("nothing committed while held", committed(h).isEmpty())
        val out = fireSilence(h, 7_800)
        assertEquals(listOf("u2"), committed(h).map { it.turnId })
        assertTrue(h.s.turn.pending.isEmpty())
        // It goes on as any committed new task: the stock line, then the brief.
        val write = out.filterIsInstance<Effect.Write>().single()
        assertTrue("the brief carries the held words", clipped in write.prompt)
        assertEquals(listOf(TurnRules.HOLD_SILENCE), commitsVia(h))
        h.drain()
        assertEquals("start.one", h.slotsSaid().first())
        h.write(CeoHarness.briefs(CeoHarness.brief("Check stock of the Kestrel aviators.", "Kestrel stock", "checking Kestrel stock")))
        h.accept(h.submits().single())
        h.drain()
        val done = decisions(h).single { it.outcome == "done" }
        assertEquals(TurnRules.HOLD_SILENCE, done.via)
        assertEquals("new_task", done.route)
        // The parked record says what the recheck answered.
        val parked = decisions(h).single { it.outcome == "parked" }
        assertEquals(0.51, parked.answers.getValue("turn").confidence, 0.0)
    }

    @Test fun heldWordsThatAreNotARequestGetGoOnThenGoAheadThenNothingMore() {
        val h = CeoHarness()
        hold(h, "so the thing about the", unsure(action = "reply", actionConf = 0.3, turnConf = 0.2))
        fireSilence(h, 7_800)
        h.drain()
        assertEquals(listOf(AppLines.HOLD_GO_ON), h.slotsSaid())
        assertTrue(committed(h).isEmpty())
        assertEquals(listOf("so the thing about the"), h.s.turn.pending)
        assertEquals(4_000L, silenceTimer(h).delayMs)
        fireSilence(h, 11_800)
        h.drain()
        assertEquals(listOf(AppLines.HOLD_GO_ON, AppLines.HOLD_CONFIRM), h.slotsSaid())
        val timers = h.effects.count { it is Effect.StartTimer && it.timer.name == TurnRules.SILENCE }
        h.at(60_000, Event.TimerFired(Timer(TurnRules.SILENCE, h.s.turn.silence!!.seq)))
        assertEquals("asked twice, then nothing more", timers, h.effects.count { it is Effect.StartTimer && it.timer.name == TurnRules.SILENCE })
        assertEquals(2, decisions(h).count { it.outcome == "hold_prompt" })
        assertTrue("still held for the answer", h.s.turn.pending.isNotEmpty())
        assertTrue("results may play now", !h.s.turn.holding)
        // "Yes": the held words and the answer go to Jev together.
        h.at(13_000, Event.SpeechStarted)
        h.at(13_000, Event.Final("yes"))
        h.at(13_000, Event.SpeechEnded)
        val call = h.at(13_500, Event.TimerFired(Timer(TurnRules.EARLY, h.s.turn.pauseSeq))).filterIsInstance<Effect.CallJev>().single()
        assertTrue(call.request.key.contains("so the thing about the") && call.request.key.contains("yes"))
    }

    @Test fun anySpeechStartsTheSilenceAgain() {
        val h = CeoHarness()
        hold(h, clipped, unsure())
        val stale = h.s.turn.silence!!.seq
        h.at(6_000, Event.SpeechStarted)
        assertTrue(h.effects.any { it is Effect.CancelTimer && it.timer == Timer(TurnRules.SILENCE, stale) })
        assertNull(h.s.turn.silence)
        h.at(6_100, Event.TimerFired(Timer(TurnRules.SILENCE, stale)))
        assertTrue("a stale silence timer does nothing", committed(h).isEmpty())
        // The rest of the word: Jev is sure now, and the turn commits at the pause as usual.
        h.at(6_200, Event.Final("ck"))
        h.at(6_300, Event.SpeechEnded)
        val call = h.at(6_800, Event.TimerFired(Timer(TurnRules.EARLY, h.s.turn.pauseSeq))).filterIsInstance<Effect.CallJev>().single()
        h.at(6_900, Event.JevAnswered(call.seq, OpenRouterResult.Ok(unsure(turnConf = 0.97)), 6_900))
        h.at(7_300, Event.TimerFired(Timer(TurnRules.COMMIT, h.s.turn.pauseSeq)))
        assertEquals(1, committed(h).size)
        assertEquals("committed at the pause, not by the silence rule", listOf(null), commitsVia(h))
    }

    @Test fun whileBillSpeaksTheSilenceWaits() {
        val h = CeoHarness()
        hold(h, clipped, unsure())
        h.at(7_000, Event.Say("status.working", com.folio.hermes.cascade.SpeechKind.Reply))
        assertTrue(h.s.speaking)
        fireSilence(h, 7_800)
        assertTrue("not over Bill", committed(h).isEmpty())
        assertEquals(4_000L, silenceTimer(h).delayMs)
        h.drain()
        fireSilence(h, 11_800)
        assertEquals(1, committed(h).size)
    }

    @Test fun aFailedRecheckFallsBackToWhatJevSaidAboutTheSameWords() {
        val h = CeoHarness()
        hold(h, clipped, unsure(), recheck = OpenRouterResult.Failed(FailureKind.Timeout))
        assertEquals(FailureKind.Timeout.wire, decisions(h).single { it.outcome == "parked" }.failure)
        fireSilence(h, 7_800)
        assertEquals(1, committed(h).size)
    }

    @Test fun aHeldGoodbyeAsksInsteadOfHangingUp() {
        val h = CeoHarness()
        hold(h, "ok bye for", unsure(action = "end_call", actionConf = 0.8, turnConf = 0.3))
        fireSilence(h, 7_800)
        h.drain()
        assertTrue(committed(h).isEmpty())
        assertTrue(h.effects.none { it is Effect.EndCall })
        assertEquals(listOf(AppLines.HOLD_GO_ON), h.slotsSaid())
    }

    @Test fun anIgnoredBlipAfterHoldingStillEndsTheWait() {
        val h = CeoHarness()
        h.at(0, Event.SpeechStarted)
        h.at(0, Event.Final(clipped))
        h.at(0, Event.SpeechEnded)
        val early = h.at(500, Event.TimerFired(Timer(TurnRules.EARLY, h.s.turn.pauseSeq))).filterIsInstance<Effect.CallJev>().single()
        h.at(600, Event.JevAnswered(early.seq, OpenRouterResult.Ok(unsure()), 600))
        h.at(1_000, Event.TimerFired(Timer(TurnRules.COMMIT, h.s.turn.pauseSeq)))
        // A cough before the recheck: its pause cancels the recheck, and Jev ignores it.
        h.at(2_000, Event.SpeechStarted)
        h.at(2_000, Event.Final("hm"))
        h.at(2_100, Event.SpeechEnded)
        val blip = h.at(2_600, Event.TimerFired(Timer(TurnRules.EARLY, h.s.turn.pauseSeq))).filterIsInstance<Effect.CallJev>().single()
        h.at(2_700, Event.JevAnswered(blip.seq, OpenRouterResult.Ok(mapOf("turn" to answer("ignore"))), 2_700))
        h.at(3_100, Event.TimerFired(Timer(TurnRules.COMMIT, h.s.turn.pauseSeq)))
        assertEquals(listOf(clipped), h.s.turn.pending)
        assertNull(h.s.turn.pause)
        // Before: held with no timer at all, forever. Now the silence rule runs, on Jev's answer about the held words.
        fireSilence(h, 7_100)
        assertEquals(1, committed(h).size)
    }
}
