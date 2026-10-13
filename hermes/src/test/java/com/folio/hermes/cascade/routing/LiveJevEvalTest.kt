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
 * hooks' questions and wording, then the R-CTX-1 budget). Prints accuracy per question, scored as
 * the hub probe scores them, and a confusion matrix per question. The hub runs it; it is skipped
 * without the key, and always when `CI` is set. The key is read from the environment and never
 * printed.
 *
 * Note: the probe never sent `last_event`/`age_s`; this run is what validates them.
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
            report(set, results)
        }
    }

    private fun report(set: String, results: List<Triple<EvalCase, Map<String, JevAnswer>?, Long>>) {
        val answered = results.filter { it.second != null }
        val latencies = answered.map { it.third }.sorted()
        println("\n== $set: ${results.size} cases, ${results.size - answered.size} errors")
        if (latencies.isNotEmpty()) println("   latency p50 ${latencies[latencies.size / 2]} ms, max ${latencies.last()} ms")
        val scores = mutableMapOf<String, Pair<Int, Int>>()
        for (q in questions) {
            var total = 0
            var ok = 0
            val misses = mutableListOf<String>()
            val confusion = sortedMapOf<String, MutableMap<String, Int>>()
            for ((case, answers, _) in answered) {
                val expected = case.expect[q] ?: continue
                // As the probe: questions other than turn and clarity count only on finished turns.
                if (q != "turn" && q != "clarity" && case.expect["turn"] != "done") continue
                val got = answers!![q]
                if (q == "clarity" && got == null) continue
                total++
                val choice = got?.choice ?: "(none)"
                if (choice == expected) ok++ else misses += "${case.id}:$expected->$choice(${"%.2f".format(got?.confidence ?: 0.0)})"
                confusion.getOrPut(expected) { sortedMapOf() }.merge(choice, 1, Int::plus)
            }
            if (total == 0) continue
            scores[q] = ok to total
            println("   %-12s %d/%d = %d%%   misses: %s".format(q, ok, total, ok * 100 / total, misses.joinToString(" ")))
            confusion.forEach { (expected, row) -> println("      expected %-15s -> %s".format(expected, row)) }
        }
        val gate = mapOf("turn" to 90, "action" to 85, "task" to 90)
        val passed = gate.all { (q, min) -> scores[q]?.let { (ok, total) -> ok * 100 >= min * total } ?: true }
        println("   R-RT-9 gate (turn >= 90%, action >= 85%, task >= 90%): ${if (passed) "PASS" else "FAIL"}")
    }
}
