package com.folio.hermes.cascade.routing

import com.folio.hermes.cascade.PhraseBank
import com.folio.hermes.openrouter.JevAnswer
import com.folio.hermes.openrouter.OpenRouter
import com.folio.hermes.openrouter.OpenRouterResult
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in tests that call the real Jev. They run only with `OPENROUTER_API_KEY` set and never in CI. */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class LiveJev

/**
 * R-RT-9: the routing evals against the real Jev, with the requests the app builds (the routing
 * hooks' questions and wording, then the R-CTX-1 budget). The hub runs it; it is skipped without
 * `OPENROUTER_API_KEY`, and always when `CI` is set. The key is read from the environment and
 * never printed.
 *
 * Per question (spec v2 §14 item 16), for information:
 * - **raw:** Jev's answer as the probe scores it, with a confusion matrix;
 * - **effective:** what the app does with it ([RoutingEvals.effective]): `turnDoneFloor`, the
 *   action floor, and the single-candidate task rule.
 *
 * **The gate (spec v2 §14 item 21, replacing R-RT-9's per-question bars)** is two rates over the
 * effective decisions ([EvalScoring.outcome]):
 * - **harmful rate**, gate ≤ 3%: a task-affecting action executed when the expected outcome differs;
 * - **friction rate**, target ≤ 15% (reported, not gated): a safe miss (clarify, re-ask, delay).
 */
@LiveJev
class LiveJevEvalTest {
    private val questions = listOf("turn", "action", "task", "split", "update_mode", "clarity")

    @Test fun routingEvalsAgainstRealJev() {
        val key = System.getenv("OPENROUTER_API_KEY")
        assumeTrue("OPENROUTER_API_KEY not set", !key.isNullOrBlank())
        assumeTrue("never in CI", System.getenv("CI").isNullOrBlank())
        val bank = PhraseBank.load()
        val router = OpenRouter(key = { key })
        for (set in listOf(RoutingEvals.TUNED, RoutingEvals.HELD_OUT)) {
            val results = RoutingEvals.load(set).map { case ->
                val request = RoutingEvals.request(case, bank)
                val started = System.nanoTime()
                val result = runBlocking { router.jev.decide(request.state, request.questions, timeoutMs = 20_000) }
                Triple(case, (result as? OpenRouterResult.Ok)?.value, (System.nanoTime() - started) / 1_000_000)
            }
            report(set, results, bank)
        }
    }

    private fun report(set: String, results: List<Triple<EvalCase, Map<String, JevAnswer>?, Long>>, bank: PhraseBank) {
        val answered = results.filter { it.second != null }
        val latencies = answered.map { it.third }.sorted()
        println("\n== $set: ${results.size} cases, ${results.size - answered.size} errors")
        if (latencies.isNotEmpty()) println("   latency p50 ${latencies[latencies.size / 2]} ms, max ${latencies.last()} ms")
        println("   raw (Jev's answers):")
        for (q in questions) {
            val rows: List<Pair<EvalCase, Pair<String, Double>?>> = answered.map { (case, answers, _) ->
                case to answers!![q]?.let { a -> (a.choice ?: "(none)") to a.confidence }
            }
            score(q, rows, confusion = true)
        }
        println("   effective (after floors and the single-candidate rule):")
        val effective = answered.map { (case, answers, _) -> case to RoutingEvals.effective(case, answers!!, bank) }
        EvalScoring.GATE.keys.forEach { q ->
            val rows: List<Pair<EvalCase, Pair<String, Double>?>> = effective.map { (case, e) -> case to e[q]?.let { it to 1.0 } }
            score(q, rows, confusion = false)
        }
        // A case Jev never answered (error or timeout) is friction: the app asks again, it never acts.
        val outcomes = effective.map { (case, e) -> case to EvalScoring.outcome(case, e) } +
            (results - answered.toSet()).map { (case, _, _) -> case to EvalScoring.Outcome.Friction(EvalScoring.NO_ANSWER) }
        val rates = EvalScoring.rates(outcomes.map { it.second })
        println("   harmful: %d/%d = %.1f%% (gate <= %d%%)   %s".format(rates.harmful, rates.total, rates.harmfulPct, EvalScoring.HARMFUL_GATE_PCT,
            outcomes.filter { it.second is EvalScoring.Outcome.Harmful }.joinToString(" ") { (c, o) -> "${c.id}:${(o as EvalScoring.Outcome.Harmful).why}" }))
        println("   friction: %d/%d = %.1f%% (target <= %d%%)   %s".format(rates.friction, rates.total, rates.frictionPct, EvalScoring.FRICTION_TARGET_PCT,
            outcomes.filter { it.second is EvalScoring.Outcome.Friction }.joinToString(" ") { (c, o) -> "${c.id}:${(o as EvalScoring.Outcome.Friction).kind}" }))
        println("   safe holds of side talk (not friction): ${outcomes.count { it.second == EvalScoring.Outcome.SafeHold }}")
        println("   gate (harmful <= ${EvalScoring.HARMFUL_GATE_PCT}%): ${if (rates.passes) "PASS" else "FAIL"}   friction target: ${if (rates.frictionMet) "met" else "missed"}")
    }

    /** Prints one question's score; returns (ok, total). */
    private fun score(q: String, rows: List<Pair<EvalCase, Pair<String, Double>?>>, confusion: Boolean): Pair<Int, Int> {
        val (ok, total, misses) = EvalScoring.score(q, rows)
        if (total == 0) return 0 to 0
        println("   %-12s %d/%d = %d%%   misses: %s".format(q, ok, total, ok * 100 / total, misses.joinToString(" ")))
        if (confusion) {
            val matrix = sortedMapOf<String, MutableMap<String, Int>>()
            rows.forEach { (case, got) ->
                val expected = EvalScoring.expected(q, case) ?: return@forEach
                if (q == "clarity" && got == null) return@forEach
                matrix.getOrPut(expected) { sortedMapOf() }.merge(got?.first ?: "(none)", 1, Int::plus)
            }
            matrix.forEach { (expected, row) -> println("      expected %-15s -> %s".format(expected, row)) }
        }
        return ok to total
    }
}

/** Scoring shared by the live harness and its offline test. */
object EvalScoring {
    /** The old R-RT-9 per-question bars: still printed per question, no longer the gate. */
    val GATE = mapOf("turn" to 90, "action" to 85, "task" to 90)

    const val HARMFUL_GATE_PCT = 3
    const val FRICTION_TARGET_PCT = 15
    const val NO_ANSWER = "no_answer"

    /** Actions that change the user's work: starting, steering or stopping a task, or hanging up. */
    val TASK_ACTIONS = setOf("new_task", "update_task", "cancel_task", "end_call")

    /** What the app would do with one case, against what the case expects (spec v2 §14 item 21). */
    sealed interface Outcome {
        /** The effective decision is the expected one. */
        data object Right : Outcome

        /** A task-affecting action executed when the expected outcome differs ([why]: a short code). */
        data class Harmful(val why: String) : Outcome

        /**
         * A safe miss: nothing task-affecting happened, but the user is slowed down. [kind]:
         * `clarify` (a question because of the floors), `delay` (keep_listening on a finished turn:
         * the 2.5 s re-ask), `dropped` (a real turn ignored: the user must say it again),
         * `wrong_reply` (a non-task action other than the expected one, or Bill answering side talk),
         * `no_answer` (Jev failed: the app asks again).
         */
        data class Friction(val kind: String) : Outcome

        /** Side talk held as keep_listening (expected `ignore`): safe, and nothing is said. Not friction. */
        data object SafeHold : Outcome
    }

    data class Rates(val total: Int, val harmful: Int, val friction: Int) {
        val harmfulPct: Double get() = if (total == 0) 0.0 else harmful * 100.0 / total
        val frictionPct: Double get() = if (total == 0) 0.0 else friction * 100.0 / total
        val passes: Boolean get() = harmful * 100 <= HARMFUL_GATE_PCT * total
        val frictionMet: Boolean get() = friction * 100 <= FRICTION_TARGET_PCT * total
    }

    fun rates(outcomes: List<Outcome>): Rates =
        Rates(outcomes.size, outcomes.count { it is Outcome.Harmful }, outcomes.count { it is Outcome.Friction })

    /**
     * Classifies [effective] (`turn`, `action`, `task` as [RoutingEvals.effective] gives them) for
     * [case]:
     * - **harmful**: the turn is acted on (`done`) with a [TASK_ACTIONS] action, and the case
     *   expected another turn verdict, another action, or (update or cancel) another task. A
     *   case expecting task `none` with that action is all-scope ("stop everything"), so any of
     *   its tasks is in scope;
     * - otherwise **right** when turn, action and task match what the case expects (as [expected]
     *   scores them);
     * - otherwise **safe hold** when side talk (expected `ignore`) was held as keep_listening;
     * - otherwise **friction**.
     */
    fun outcome(case: EvalCase, effective: Map<String, String>): Outcome {
        val turn = effective["turn"]
        val action = effective["action"]
        val task = effective["task"]
        val wantTurn = case.expect["turn"]
        val wantAction = case.expect["action"]
        val wantTask = case.expect["task"]
        if (turn == CascadeBrain.DONE && action in TASK_ACTIONS) {
            when {
                wantTurn != CascadeBrain.DONE -> return Outcome.Harmful("acted_on_$wantTurn")
                wantAction != null && wantAction != action -> return Outcome.Harmful("${wantAction}_as_$action")
                (action == "update_task" || action == "cancel_task") && wantTask != null && wantTask != CascadeBrain.NONE && wantTask != task ->
                    return Outcome.Harmful("wrong_task")
            }
        }
        val right = listOf("turn", "action", "task").all { q ->
            val want = expected(q, case) ?: return@all true
            // All-scope cancel: any task id the app picked is within "everything".
            if (q == "task" && want == CascadeBrain.NONE && action == "cancel_task") return@all true
            effective[q] == want
        }
        if (right) return Outcome.Right
        if (wantTurn == CascadeBrain.IGNORE && turn == "keep_listening") return Outcome.SafeHold
        val kind = when {
            wantTurn == CascadeBrain.DONE && turn == CascadeBrain.IGNORE -> "dropped"
            wantTurn == CascadeBrain.DONE && turn != CascadeBrain.DONE -> "delay"
            action == "clarify" -> "clarify"
            else -> "wrong_reply"
        }
        return Outcome.Friction(kind)
    }

    /** The expected answer for [q], or null when the probe does not score [q] on [case]. */
    fun expected(q: String, case: EvalCase): String? {
        val expected = case.expect[q] ?: return null
        // As the probe: questions other than turn and clarity count only on finished turns.
        if (q != "turn" && q != "clarity" && case.expect["turn"] != "done") return null
        return expected
    }

    /** (ok, total, misses) for [q] over [rows] of (case, (choice, confidence) or null). */
    fun score(q: String, rows: List<Pair<EvalCase, Pair<String, Double>?>>): Triple<Int, Int, List<String>> {
        var ok = 0
        var total = 0
        val misses = mutableListOf<String>()
        for ((case, got) in rows) {
            val expected = expected(q, case) ?: continue
            if (q == "clarity" && got == null) continue
            total++
            val choice = got?.first ?: "(none)"
            if (choice == expected) ok++ else misses += "${case.id}:$expected->$choice(${"%.2f".format(got?.second ?: 0.0)})"
        }
        return Triple(ok, total, misses)
    }

    fun passes(scores: Map<String, Pair<Int, Int>>): Boolean =
        GATE.all { (q, min) -> scores[q]?.let { (ok, total) -> total == 0 || ok * 100 >= min * total } ?: true }
}
