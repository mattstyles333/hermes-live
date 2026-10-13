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
 * Two scores per question (spec v2 §14 item 16):
 * - **raw:** Jev's answer as the probe scores it, with a confusion matrix;
 * - **effective:** what the app does with it ([RoutingEvals.effective]): `turnDoneFloor`, the
 *   action floor, and the single-candidate task rule. **The gate scores effective decisions.**
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
        val scores = EvalScoring.GATE.keys.associateWith { q ->
            val rows: List<Pair<EvalCase, Pair<String, Double>?>> = effective.map { (case, e) -> case to e[q]?.let { it to 1.0 } }
            score(q, rows, confusion = false)
        }
        println("   R-RT-9 gate on effective decisions (turn >= 90%, action >= 85%, task >= 90%): ${if (EvalScoring.passes(scores)) "PASS" else "FAIL"}")
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
    val GATE = mapOf("turn" to 90, "action" to 85, "task" to 90)

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
