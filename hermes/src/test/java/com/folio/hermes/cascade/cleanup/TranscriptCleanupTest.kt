package com.folio.hermes.cascade.cleanup

import com.folio.hermes.cascade.CascadeReducer
import com.folio.hermes.cascade.CascadeSettings
import com.folio.hermes.cascade.Effect
import com.folio.hermes.cascade.Event
import com.folio.hermes.cascade.PhraseBank
import com.folio.hermes.cascade.State
import com.folio.hermes.cascade.Timer
import com.folio.hermes.cascade.Transcript
import com.folio.hermes.cascade.TranscriptPass
import com.folio.hermes.cascade.TurnRules
import com.folio.hermes.cascade.Word
import com.folio.hermes.cascade.answer
import com.folio.hermes.cascade.routing.CeoRouting
import com.folio.hermes.cascade.writer.Briefs
import com.folio.hermes.openrouter.FailureKind
import com.folio.hermes.openrouter.JevAnswer
import com.folio.hermes.openrouter.OpenRouterResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Transcript cleanup on the pure reducer with CEO routing and the accurate pass on: the stock
 * line plays at commit; the brief and submit wait for the cleanup and use its words; any failure
 * keeps the heard text; a changed name asks Jev again before the turn goes on; off by default.
 */
class TranscriptCleanupTest {
    private val bank = PhraseBank.load()

    private class Run(val reducer: CascadeReducer, settings: CascadeSettings) {
        var s = reducer.reduce(State(settings = settings), Event.Start()).first
        val effects = mutableListOf<Effect>()

        fun at(ms: Long, event: Event): List<Effect> {
            val (next, fx) = reducer.reduce(s.copy(nowMs = ms, wallMs = ms), event)
            s = next
            effects += fx
            return fx
        }

        fun timer(name: String): Timer = effects.filterIsInstance<Effect.StartTimer>().last { it.timer.name == name }.timer

        fun turn(t0: Long, text: String, vararg answers: Pair<String, JevAnswer>): List<Effect> {
            at(t0, Event.SpeechStarted)
            at(t0, Event.Final(text))
            at(t0, Event.SpeechEnded)
            val early = at(t0 + 500, Event.TimerFired(timer(TurnRules.EARLY))).filterIsInstance<Effect.CallJev>().single()
            at(t0 + 600, Event.JevAnswered(early.seq, OpenRouterResult.Ok(mapOf("turn" to answer("done", 0.95)) + mapOf(*answers)), t0 + 600))
            return at(t0 + 1_000, Event.TimerFired(timer(TurnRules.COMMIT)))
        }
    }

    private val on = CascadeSettings(accuratePass = true, cleanup = true, vocabulary = listOf("Kestrel"))

    private fun run(settings: CascadeSettings = on) = Run(CascadeReducer(CeoRouting.env(bank)), settings)

    private val newTask = arrayOf(
        "action" to answer("new_task", 0.95), "split" to answer("one", 0.95), "clarity" to answer("clear", 0.95),
        "line" to answer("start.one", 0.95), "enough" to answer("line_then_writer", 0.95),
    )

    private fun lines(fx: List<Effect>) = fx.filterIsInstance<Effect.FetchClip>().filter { !it.prefetch }.mapNotNull { it.piece.slot }

    private fun brief(fx: List<Effect>) = fx.filterIsInstance<Effect.Write>().single { it.system == Briefs.SYSTEM }

    private fun accurate(text: String) = Transcript(text, text.split(" ").map { Word(it) }, TranscriptPass.Accurate)

    @Test fun offByDefaultNothingIsAskedAndTheBriefGoesAtOnce() {
        val r = run(CascadeSettings(accuratePass = true, vocabulary = listOf("Kestrel")))
        r.turn(0, "check the castral aviators", *newTask)
        val fx = r.at(1_300, Event.AccurateFinal("u1", accurate("check the castral aviators")))
        assertTrue(r.effects.none { it is Effect.Cleanup })
        assertTrue(brief(fx).prompt.contains("castral"))
    }

    @Test fun offlineNothingIsAsked() {
        val r = run()
        r.turn(0, "check the castral aviators", *newTask)
        r.at(1_100, Event.Network(false))
        r.at(1_300, Event.AccurateFinal("u1", accurate("check the castral aviators")))
        assertTrue(r.effects.none { it is Effect.Cleanup })
    }

    @Test fun aChangedNameIsBriefedCaptionedAndJudgedAgain() {
        val r = run()
        val commit = r.turn(0, "check the castral aviator", *newTask)
        assertEquals("the line at commit", listOf("start.one"), lines(commit))
        val asked = r.at(1_300, Event.AccurateFinal("u1", accurate("check the castral aviators")))
        val cleanup = asked.filterIsInstance<Effect.Cleanup>().single()
        assertEquals("check the castral aviators", cleanup.input.heard)
        assertEquals("the fast pass is the other recogniser", "check the castral aviator", cleanup.input.other)
        assertEquals(listOf("Kestrel"), cleanup.input.vocabulary)
        assertTrue("the brief waits", asked.none { it is Effect.Write })

        val cleaned = r.at(1_900, Event.Cleaned("u1", OpenRouterResult.Ok("check the Kestrel aviators"), 600))
        val reask = cleaned.filterIsInstance<Effect.CallJev>().single()
        assertTrue("Jev judges the cleaned words", reask.request.state.toString().contains("check the Kestrel aviators"))
        assertTrue("the brief waits for Jev", cleaned.none { it is Effect.Write })
        assertEquals("the caption shows the cleaned words", "check the Kestrel aviators", r.s.transcript.byTurn.getValue("u1").text)

        val go = r.at(2_200, Event.JevAnswered(reask.seq, OpenRouterResult.Ok(mapOf("turn" to answer("done", 0.95)) + mapOf(*newTask)), 2_200))
        val prompt = brief(go).prompt
        assertTrue(prompt, prompt.contains("Transcript: \"check the Kestrel aviators\"") && !prompt.contains("castral aviators\""))
        assertEquals("the line is not played again", listOf("start.one"), lines(r.effects))
    }

    @Test fun aReaskThatPicksAnotherRouteAfterTheLineKeepsTheFirstDecision() {
        val r = run()
        r.turn(0, "check the castral aviators", *newTask)
        r.at(1_300, Event.AccurateFinal("u1", accurate("check the castral aviators")))
        val reask = r.at(1_900, Event.Cleaned("u1", OpenRouterResult.Ok("check the Kestrel aviators"), 600)).filterIsInstance<Effect.CallJev>().single()
        val go = r.at(2_200, Event.JevAnswered(reask.seq, OpenRouterResult.Ok(mapOf("turn" to answer("done", 0.95), "action" to answer("status", 0.95))), 2_200))
        assertTrue("still the new task the user heard start", brief(go).prompt.contains("Kestrel"))
    }

    @Test fun aClippedWordIsFixedWithoutAskingJevAgain() {
        val r = run()
        r.turn(0, "what we have in sto", *newTask)
        r.at(1_300, Event.AccurateFinal("u1", accurate("what we have in sto")))
        val fx = r.at(1_800, Event.Cleaned("u1", OpenRouterResult.Ok("what we have in stock"), 500))
        assertTrue(fx.none { it is Effect.CallJev })
        assertTrue(brief(fx).prompt.contains("Transcript: \"what we have in stock\""))
    }

    @Test fun aFailureATimeoutOrARefusedSwapKeepsTheHeardText() {
        for (result in listOf(
            OpenRouterResult.Failed(FailureKind.Timeout),
            OpenRouterResult.Failed(FailureKind.BadBody, 200),
            OpenRouterResult.Ok("check the Oakley aviators"),
        )) {
            val r = run()
            r.turn(0, "check the castral aviators", *newTask)
            r.at(1_300, Event.AccurateFinal("u1", accurate("check the castral aviators")))
            val fx = r.at(2_800, Event.Cleaned("u1", result, 1_500))
            assertTrue("$result", brief(fx).prompt.contains("Transcript: \"check the castral aviators\""))
            assertEquals("check the castral aviators", r.s.transcript.byTurn.getValue("u1").text)
        }
    }

    @Test fun aLateReaskAnswerIsNeverTakenAsATurns() {
        val r = run()
        r.turn(0, "check the castral aviators", *newTask)
        r.at(1_300, Event.AccurateFinal("u1", accurate("check the castral aviators")))
        val reask = r.at(1_900, Event.Cleaned("u1", OpenRouterResult.Ok("check the Kestrel aviators"), 600)).filterIsInstance<Effect.CallJev>().single()
        r.at(1_950, Event.Stop)
        val before = r.s.turn
        r.at(2_200, Event.JevAnswered(reask.seq, OpenRouterResult.Ok(mapOf("turn" to answer("done", 0.95))), 2_200))
        assertEquals(before, r.s.turn)
        assertTrue(r.s.actions.waiting.isEmpty())
    }
}
