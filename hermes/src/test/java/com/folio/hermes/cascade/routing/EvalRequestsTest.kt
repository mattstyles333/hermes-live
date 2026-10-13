package com.folio.hermes.cascade.routing

import com.folio.hermes.cascade.PhraseBank
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The app's request for every eval case is the probe's FULL request (same questions, same
 * criteria, same state values), plus only `last_event` and `age_s` per task. So the measured
 * wording still applies, and a probe re-run measures what the app sends.
 */
class EvalRequestsTest {
    private val bank = PhraseBank.load()

    @Test fun everyEvalRequestMatchesTheProbeShape() {
        for (case in RoutingEvals.load(RoutingEvals.TUNED) + RoutingEvals.load(RoutingEvals.HELD_OUT)) {
            val request = RoutingEvals.request(case, bank)
            val statuses = case.tasks.map { CascadeBrain.status(it) }
            val expected = buildList {
                add("turn"); add("action")
                if (case.tasks.isNotEmpty()) add("task")
                add("split"); add("clarity"); add("line"); add("enough")
                if ("running" in statuses) add("update_mode")
            }
            assertEquals(case.id, expected, request.questions.keys.toList())
            request.questions["task"]?.let { q ->
                assertEquals(case.id, case.tasks.map { it.id } + "none", q.criteria.keys.toList())
                case.tasks.forEachIndexed { i, t -> assertEquals("${t.label} (${statuses[i]})", q.criteria[t.id]) }
            }
            val state = request.state
            assertEquals(case.heard, state["latest"]!!.jsonPrimitive.content)
            assertEquals(1_000L, state["silence_ms"]!!.jsonPrimitive.content.toLong())
            val tasks = state["tasks"]!!.jsonArray.map { it.jsonObject }
            assertEquals(case.id, case.tasks.map { it.id }.toSet(), tasks.map { it["id"]!!.jsonPrimitive.content }.toSet())
            tasks.forEach { t ->
                val i = case.tasks.indexOfFirst { it.id == t["id"]!!.jsonPrimitive.content }
                assertEquals(statuses[i], t["status"]!!.jsonPrimitive.content)
                assertEquals(listOf("id", "label", "status", "last_event", "age_s"), t.keys.toList())
            }
        }
    }
}
