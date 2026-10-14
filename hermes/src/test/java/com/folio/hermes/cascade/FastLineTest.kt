package com.folio.hermes.cascade

import com.folio.hermes.cascade.routing.CeoRouting
import com.folio.hermes.cascade.writer.Briefs
import com.folio.hermes.openrouter.JevAnswer
import com.folio.hermes.openrouter.OpenRouterResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R-Q-3 / R-SPK-2 on the pure reducer, CEO routing, accurate pass on: the stock line plays at
 * commit, while the accurate pass is out; what needs the exact words waits for it and uses them;
 * the §0 ordering stays (a key doubt asks first; a turn behind another action waits its turn).
 * The whole call against the fake Hermes is `GoldenCallTest` 1b.
 */
class FastLineTest {
    private val bank = PhraseBank.load()

    private class Run(val reducer: CascadeReducer) {
        var s = reducer.reduce(State(settings = CascadeSettings(accuratePass = true)), Event.Start()).first
        val effects = mutableListOf<Effect>()

        fun at(ms: Long, event: Event): List<Effect> {
            val (next, fx) = reducer.reduce(s.copy(nowMs = ms, wallMs = ms), event)
            s = next
            effects += fx
            return fx
        }

        /** The newest timer started under [name]. */
        fun timer(name: String): Timer = effects.filterIsInstance<Effect.StartTimer>().last { it.timer.name == name }.timer

        /** One turn: [text] said and ended at [t0]; Jev answers [answers] after its early call; returns the commit step's effects. */
        fun turn(t0: Long, text: String, vararg answers: Pair<String, JevAnswer>): List<Effect> {
            at(t0, Event.SpeechStarted)
            at(t0, Event.Final(text))
            at(t0, Event.SpeechEnded)
            val early = at(t0 + 500, Event.TimerFired(timer(TurnRules.EARLY))).filterIsInstance<Effect.CallJev>().single()
            at(t0 + 600, Event.JevAnswered(early.seq, OpenRouterResult.Ok(mapOf("turn" to answer("done", 0.95)) + mapOf(*answers)), t0 + 600))
            return at(t0 + 1_000, Event.TimerFired(timer(TurnRules.COMMIT)))
        }
    }

    private fun run() = Run(CascadeReducer(CeoRouting.env(bank)))

    private val newTask = arrayOf(
        "action" to answer("new_task", 0.95), "split" to answer("one", 0.95), "clarity" to answer("clear", 0.95),
        "line" to answer("start.one", 0.95), "enough" to answer("line_then_writer", 0.95),
    )

    private fun lines(fx: List<Effect>) = fx.filterIsInstance<Effect.FetchClip>().filter { !it.prefetch }.mapNotNull { it.piece.slot }

    @Test fun theStartLinePlaysAtCommitAndTheBriefWaitsForTheAccurateWords() {
        val r = run()
        val commit = r.turn(0, "check the Castrel aviators", *newTask)
        assertTrue(Effect.AccuratePass("u1") in commit)
        assertEquals("the line at commit, from the cache", listOf("start.one"), lines(commit))
        assertTrue("no brief yet", commit.none { it is Effect.Write })
        assertEquals("start.one", r.s.actions.waiting.single().slot)

        val accurate = Transcript("check the Kestrel aviators", listOf(Word("check"), Word("the"), Word("Kestrel"), Word("aviators")), TranscriptPass.Accurate)
        val run = r.at(1_300, Event.AccurateFinal("u1", accurate))
        val brief = run.filterIsInstance<Effect.Write>().single { it.system == Briefs.SYSTEM }
        assertTrue(brief.prompt, brief.prompt.contains("Transcript: \"check the Kestrel aviators\"") && !brief.prompt.contains("Castrel"))
        assertEquals("the line is not played again", listOf("start.one"), lines(r.effects))
        assertEquals("check the Kestrel aviators", r.s.actions.active!!.text)
    }

    @Test fun aKeyDoubtAsksFirstSoNoLineAtCommit() {
        val r = run()
        val commit = r.turn(0, "email Sapphire about forty frames", *newTask, "clarity" to answer("key_doubt", 0.9))
        assertEquals(emptyList<String>(), lines(commit))
        assertTrue(commit.none { it is Effect.StreamWriter })
        val run = r.at(1_200, Event.AccurateFinal("u1", null))
        assertTrue("the doubt question is the writer's, after the pass", run.any { it is Effect.StreamWriter })
        assertEquals(emptyList<String>(), lines(r.effects))
    }

    @Test fun aTurnBehindAnotherActionKeepsItsPlace() {
        val r = run()
        r.turn(0, "check the Kestrel aviators", *newTask)
        r.at(1_200, Event.AccurateFinal("u1", null))
        // The first action waits on its brief; the next turn's line must not jump ahead of it.
        val second = r.turn(2_000, "and draft the newsletter", *newTask)
        assertEquals(emptyList<String>(), lines(second))
        assertEquals(listOf("start.one"), lines(r.effects))
    }
}
