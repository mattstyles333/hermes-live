package com.folio.hermes.cascade.routing

import com.folio.hermes.cascade.CascadeSettings
import com.folio.hermes.cascade.JevRequest
import com.folio.hermes.cascade.PhraseBank
import com.folio.hermes.cascade.TaskBrief
import com.folio.hermes.cascade.TaskState
import com.folio.hermes.cascade.TurnContext
import com.folio.hermes.cascade.budget.CappedJevStateBudget
import com.folio.hermes.cascade.budget.JevStateBudget
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One routing eval case (hub `hermes-cascade-routing-evals-*.jsonl`). */
data class EvalCase(
    val id: String,
    val tasks: List<TaskBrief>,
    val pending: String,
    val heard: String,
    /** Expected answer per question (`turn`, `action`, `task`, `split`, `update_mode`, `clarity`). */
    val expect: Map<String, String>,
)

/** Loads the eval sets from test resources and builds requests exactly as a call would. */
object RoutingEvals {
    const val TUNED = "hermes-cascade-routing-evals-2026-10-13.jsonl"
    const val HELD_OUT = "hermes-cascade-routing-evals-heldout-2026-10-13.jsonl"

    /** The probe's defaults for the fields the eval files do not carry. */
    const val DEFAULT_LAST_EVENT = "working"
    const val DEFAULT_AGE_S = 60L

    fun load(name: String): List<EvalCase> {
        val text = RoutingEvals::class.java.getResourceAsStream("/com/folio/hermes/cascade/routing/$name")!!.bufferedReader().use { it.readText() }
        return text.lineSequence().filter { it.isNotBlank() }.map { parse(Json.parseToJsonElement(it).jsonObject) }.toList()
    }

    private fun parse(o: JsonObject): EvalCase = EvalCase(
        id = o["id"]!!.jsonPrimitive.content,
        tasks = o["tasks"]?.jsonArray.orEmpty().map { t ->
            val task = t.jsonObject
            val status = task["status"]!!.jsonPrimitive.content
            // `waiting` and `queued` are running tasks to the phone; their last event says which.
            val (state, lastEvent) = when (status) {
                CascadeBrain.WAITING -> TaskState.Running to "waiting on the user (approval)"
                CascadeBrain.QUEUED -> TaskState.Running to CascadeBrain.QUEUED
                else -> TaskState.entries.first { it.wire == status } to DEFAULT_LAST_EVENT
            }
            TaskBrief(task["id"]!!.jsonPrimitive.content, task["label"]!!.jsonPrimitive.content, state, lastEvent, DEFAULT_AGE_S)
        },
        pending = o["pending"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        heard = o["heard"]!!.jsonPrimitive.content,
        expect = o["expect"]!!.jsonObject.filterKeys { it != "note" }.mapValues { it.value.jsonPrimitive.content },
    )

    /** The probe's context: pending as one held piece, 1000 ms of silence, no recent, Bill quiet. */
    fun context(case: EvalCase): TurnContext =
        TurnContext(case.heard, listOfNotNull(case.pending.takeIf { it.isNotBlank() }), 1_000, emptyList(), case.tasks, false)

    /** The request the app sends for [case]: the routing hooks' questions, then the R-CTX-1 budget. */
    fun request(
        case: EvalCase,
        bank: PhraseBank,
        settings: CascadeSettings = CascadeSettings(),
        budget: JevStateBudget = CappedJevStateBudget(),
    ): JevRequest {
        val request = CeoRouting.hooks().ask(context(case), bank, settings)
        return request.copy(state = budget.fit(request.state, settings))
    }
}
