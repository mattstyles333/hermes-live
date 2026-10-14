package com.folio.hermes.cascade.routing

import com.folio.hermes.cascade.PhraseBank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tier 0 routing regressions without a key (test-shape plan, "recorded-Jev fixtures"): the live
 * Jev answers recorded by `LiveJevEvalTest` (record mode) are replayed through the real floors
 * and rules ([RoutingEvals.effective]) and scored as the live gate does ([EvalScoring]).
 *
 * - Every eval case must have a recording whose request fingerprint matches the request the app
 *   builds today; otherwise the wording, state shape or model changed and the test says
 *   "re-record".
 * - The harmful rate must stay within the gate (≤ 3%).
 * - Each case's effective decision must be the one recorded. If a rule change moves one on
 *   purpose, refresh them offline with `UPDATE_JEV_EFFECTIVE=1` (no key, no request) and review
 *   the diff.
 */
class RecordedJevTest {
    private val bank = PhraseBank.load()
    private val sets = listOf(RoutingEvals.TUNED, RoutingEvals.HELD_OUT)
    private val cases: Map<Pair<String, String>, EvalCase> =
        sets.flatMap { set -> RoutingEvals.load(set).map { (set to it.id) to it } }.toMap()

    @Test fun recordedAnswersStillRouteWithinTheGate() {
        if (System.getenv("UPDATE_JEV_EFFECTIVE") == "1") {
            println("updated ${JevRecording.updateEffective(bank, cases).path}")
        }
        val (name, recording) = JevRecording.latest() ?: run { fail("no jev-recorded-*.json; ${JevRecording.RERECORD}"); return }
        val byCase = recording.cases.associateBy { it.set to it.id }

        val missing = cases.keys.filter { it !in byCase }
        assertTrue("$name lacks ${missing.map { it.second }}; ${JevRecording.RERECORD}", missing.isEmpty())
        val changed = cases.filter { (k, case) -> byCase.getValue(k).fingerprint != JevRecording.fingerprint(case, bank, recording.model) }.keys
        assertTrue(
            "the Jev request changed for ${changed.size} of ${cases.size} cases (${changed.take(8).map { it.second }}): wording, state or model; ${JevRecording.RERECORD}",
            changed.isEmpty(),
        )

        val outcomes = cases.map { (k, case) ->
            val r = byCase.getValue(k)
            val answers = r.answers ?: return@map case to EvalScoring.Outcome.Friction(EvalScoring.NO_ANSWER)
            case to EvalScoring.outcome(case, RoutingEvals.effective(case, answers, bank))
        }
        val rates = EvalScoring.rates(outcomes.map { it.second })
        val harmful = outcomes.filter { it.second is EvalScoring.Outcome.Harmful }.map { (c, o) -> "${c.id}:${(o as EvalScoring.Outcome.Harmful).why}" }
        println(
            "recorded Jev ($name): harmful %d/%d = %.1f%%, friction %d/%d = %.1f%% %s".format(
                rates.harmful, rates.total, rates.harmfulPct, rates.friction, rates.total, rates.frictionPct, harmful,
            ),
        )
        assertEquals(cases.size, rates.total)
        assertTrue("harmful %.1f%% > %d%%: %s".format(rates.harmfulPct, EvalScoring.HARMFUL_GATE_PCT, harmful), rates.passes)

        val moved = cases.mapNotNull { (k, case) ->
            val r = byCase.getValue(k)
            val now = r.answers?.let { RoutingEvals.effective(case, it, bank) }.orEmpty()
            if (now != r.effective) "${case.id}: ${r.effective} -> $now" else null
        }
        assertTrue(
            "the routing rules changed ${moved.size} effective decisions (if intended: UPDATE_JEV_EFFECTIVE=1, then review):\n" + moved.joinToString("\n"),
            moved.isEmpty(),
        )
    }

    /**
     * The hub's 2026-10-14 rules on the recorded answers: the sure goodbyes (h44, h45: `turn = done`
     * at 0.25 / 0.41, `end_call` 0.99) now end the turn; side talk and radio still do not act (h14:
     * a goodbye Jev judged `ignore`; h15: the weather on the radio at `done` 0.52 with a reply);
     * "stop everything" with two running tasks cancels both (h38); a misheard name still asks
     * which (h40).
     */
    @Test fun sureGoodbyesActAndSideTalkStillDoesNot() {
        val recording = JevRecording.latest()!!.second
        fun effective(id: String): Map<String, String> {
            val r = recording.cases.single { it.id == id }
            return RoutingEvals.effective(cases.getValue(r.set to id), r.answers!!, bank)
        }
        assertEquals(mapOf("turn" to "done", "action" to "end_call", "task" to "none"), effective("h44"))
        assertEquals(mapOf("turn" to "done", "action" to "end_call", "task" to "none"), effective("h45"))
        assertEquals("ignore", effective("h14").getValue("turn"))
        assertEquals("keep_listening", effective("h15").getValue("turn"))
        assertEquals(mapOf("turn" to "done", "action" to "cancel_task", "task" to CeoRouter.ALL_TASKS), effective("h38"))
        assertEquals("clarify", effective("h40").getValue("action"))
    }

    /** The fingerprint is the request body itself: any wording or model change moves it. */
    @Test fun theFingerprintFollowsTheRequest() {
        val case = cases.values.first()
        val a = JevRecording.fingerprint(case, bank)
        assertEquals(a, JevRecording.fingerprint(case, bank))
        assertTrue(a != JevRecording.fingerprint(case, bank, model = "another/model"))
        assertTrue(a != JevRecording.fingerprint(case.copy(heard = case.heard + " please"), bank))
        assertNotNull(Regex("^[0-9a-f]{64}$").matchEntire(a))
    }
}
