package com.folio.hermes.cascade.routing

import com.folio.hermes.cascade.PhraseBank
import com.folio.hermes.cascade.answer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec v2 §14 item 16, offline: the eval gate scores what the app does, not Jev's raw answer. */
class EffectiveEvalTest {
    private val bank = PhraseBank.load()
    private val cases = RoutingEvals.load(RoutingEvals.TUNED) + RoutingEvals.load(RoutingEvals.HELD_OUT)

    @Test fun aLowConfidenceTaskWithOneCandidateIsEffectivelyRight() {
        val case = cases.first { it.tasks.size == 1 && it.expect["action"] == "update_task" && it.expect["task"] == it.tasks.single().id }
        val raw = mapOf("turn" to answer("done", 0.9), "action" to answer("update_task", 0.9), "task" to answer("none", 0.02))
        val e = RoutingEvals.effective(case, raw, bank)
        assertEquals(case.tasks.single().id, e["task"])
        assertEquals("update_task", e["action"])
        val (ok, total, _) = EvalScoring.score("task", listOf(case to (e["task"]!! to 1.0)))
        assertEquals(1 to 1, ok to total)
        assertEquals("raw it is a miss", 0, EvalScoring.score("task", listOf(case to ("none" to 0.02))).first)
    }

    @Test fun doneBelowTheTurnFloorKeepsListeningAndAnUnsureActionClarifies() {
        val case = cases.first { it.expect["turn"] == "done" && it.tasks.isEmpty() }
        val e = RoutingEvals.effective(case, mapOf("turn" to answer("done", 0.58), "action" to answer("new_task", 0.5)), bank)
        assertEquals("keep_listening", e["turn"])
        assertEquals("clarify", e["action"])
        assertEquals("done", RoutingEvals.effective(case, mapOf("turn" to answer("done", 0.6)), bank)["turn"])
    }

    @Test fun lowConfidenceSideTalkIsNotADoneTurn() {
        val e = RoutingEvals.effective(cases.first(), mapOf("turn" to answer("done", 0.4)), bank)
        assertEquals("keep_listening", e["turn"])
    }

    @Test fun theGateUsesTheThresholds() {
        assertTrue(EvalScoring.passes(mapOf("turn" to (9 to 10), "action" to (17 to 20), "task" to (9 to 10))))
        assertFalse(EvalScoring.passes(mapOf("turn" to (8 to 10))))
    }

    /** Jev answering exactly what each case expects scores 100% effective on every gated question. */
    @Test fun perfectAnswersPassEffectively() {
        val rows = cases.map { case ->
            val answers = case.expect.mapValues { (_, v) -> answer(v, 0.95) }
            case to RoutingEvals.effective(case, answers, bank)
        }
        for (q in EvalScoring.GATE.keys) {
            val scored: List<Pair<EvalCase, Pair<String, Double>?>> = rows.map { (c, e) -> c to e[q]?.let { it to 1.0 } }
            val (ok, total, misses) = EvalScoring.score(q, scored)
            assertTrue("$q: $ok/$total misses $misses", EvalScoring.passes(mapOf(q to (ok to total))))
        }
    }
}
