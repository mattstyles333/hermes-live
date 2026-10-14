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
        val e = RoutingEvals.effective(case, mapOf("turn" to answer("done", 0.58), "action" to answer("new_task", 0.35)), bank)
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

    // ── spec v2 §14 item 21: harmful rate (gate ≤ 3%) and friction rate (target ≤ 15%) ──────

    private fun case(expect: Map<String, String>, tasks: Int = 0) = EvalCase(
        id = "x",
        tasks = (1..tasks).map { com.folio.hermes.cascade.TaskBrief("t$it", "Task $it", com.folio.hermes.cascade.TaskState.Running, "working", 10) },
        pending = "",
        heard = "words",
        expect = expect,
    )

    @Test fun aTaskActionOnSideTalkOrTheWrongTaskIsHarmful() {
        val sideTalk = case(mapOf("turn" to "ignore"))
        assertTrue(EvalScoring.outcome(sideTalk, mapOf("turn" to "done", "action" to "new_task", "task" to "none")) is EvalScoring.Outcome.Harmful)
        val update = case(mapOf("turn" to "done", "action" to "update_task", "task" to "t2"), tasks = 2)
        assertTrue(EvalScoring.outcome(update, mapOf("turn" to "done", "action" to "update_task", "task" to "t1")) is EvalScoring.Outcome.Harmful)
        val reply = case(mapOf("turn" to "done", "action" to "reply"))
        assertTrue("an unasked task", EvalScoring.outcome(reply, mapOf("turn" to "done", "action" to "new_task", "task" to "none")) is EvalScoring.Outcome.Harmful)
        val dictation = case(mapOf("turn" to "keep_listening"))
        assertTrue("ending a call mid-dictation", EvalScoring.outcome(dictation, mapOf("turn" to "done", "action" to "end_call", "task" to "none")) is EvalScoring.Outcome.Harmful)
    }

    @Test fun safeMissesAreFrictionAndHeldSideTalkIsNeither() {
        val newTask = case(mapOf("turn" to "done", "action" to "new_task", "task" to "none"))
        assertEquals(EvalScoring.Outcome.Friction("clarify"), EvalScoring.outcome(newTask, mapOf("turn" to "done", "action" to "clarify", "task" to "none")))
        assertEquals(EvalScoring.Outcome.Friction("delay"), EvalScoring.outcome(newTask, mapOf("turn" to "keep_listening", "action" to "clarify", "task" to "none")))
        assertEquals("a real turn ignored", EvalScoring.Outcome.Friction("dropped"), EvalScoring.outcome(newTask, mapOf("turn" to "ignore", "action" to "clarify", "task" to "none")))
        val sideTalk = case(mapOf("turn" to "ignore"))
        assertEquals(EvalScoring.Outcome.SafeHold, EvalScoring.outcome(sideTalk, mapOf("turn" to "keep_listening", "action" to "clarify", "task" to "none")))
        assertEquals("Bill answers side talk", EvalScoring.Outcome.Friction("wrong_reply"), EvalScoring.outcome(sideTalk, mapOf("turn" to "done", "action" to "reply", "task" to "none")))
    }

    @Test fun stopEverythingWithOneTaskIsRight() {
        val all = case(mapOf("turn" to "done", "action" to "cancel_task", "task" to "none"), tasks = 1)
        assertEquals(EvalScoring.Outcome.Right, EvalScoring.outcome(all, mapOf("turn" to "done", "action" to "cancel_task", "task" to "t1")))
    }

    @Test fun ratesGateHarmfulAtThreePercentAndReportFriction() {
        val right = List(97) { EvalScoring.Outcome.Right }
        val three = EvalScoring.rates(right + List(3) { EvalScoring.Outcome.Harmful("x") })
        assertTrue(three.passes)
        assertFalse(EvalScoring.rates(right.drop(1) + List(4) { EvalScoring.Outcome.Harmful("x") }).passes)
        val friction = EvalScoring.rates(List(84) { EvalScoring.Outcome.Right } + List(16) { EvalScoring.Outcome.Friction("clarify") })
        assertTrue("friction is a target, not the gate", friction.passes)
        assertFalse(friction.frictionMet)
    }

    /**
     * Jev answering exactly what each case expects: no harm. The only friction left is the app's
     * own rule: a cancel with no single target (all-scope "stop everything", or a misheard name
     * between two tasks) asks which one before stopping anything.
     */
    @Test fun perfectAnswersAreNeverHarmful() {
        val outcomes = cases.map { case -> case to EvalScoring.outcome(case, RoutingEvals.effective(case, case.expect.mapValues { (_, v) -> answer(v, 0.95) }, bank)) }
        val rates = EvalScoring.rates(outcomes.map { it.second })
        assertEquals(0, rates.harmful)
        val friction = outcomes.filter { it.second is EvalScoring.Outcome.Friction }
        assertTrue(
            friction.joinToString { "${it.first.id}:${it.second}" },
            friction.all { (c, o) -> o == EvalScoring.Outcome.Friction("clarify") && c.expect["action"] == "cancel_task" && c.expect["task"].let { it == null || it == "none" } && c.tasks.size > 1 },
        )
    }
}
