package com.folio.hermes.cascade.routing

import com.folio.hermes.cascade.CascadeSettings
import com.folio.hermes.cascade.Route
import com.folio.hermes.cascade.State
import com.folio.hermes.cascade.TaskRecord
import com.folio.hermes.cascade.TaskTable
import com.folio.hermes.cascade.TurnVerdict
import com.folio.hermes.openrouter.JevAnswer
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

    /**
     * What the app would actually do with [answers] for [case] (spec v2 §14 item 16): the turn
     * verdict after `turnDoneFloor`, the sure-goodbye rule and the ignore floor, then
     * `CeoRouter.decide` (the action floor, R-RT-5 lines, the single-candidate task rule) and
     * `CeoRouter.withWords` ("stop everything" cancels all). Returns the effective choice per
     * question: `turn` (`done`/`keep_listening`/`ignore`), `action` (the route; any clarifying
     * route is `clarify`), `task` (the routed task id, or `none`).
     */
    fun effective(case: EvalCase, answers: Map<String, JevAnswer>, bank: PhraseBank, settings: CascadeSettings = CascadeSettings()): Map<String, String> {
        val turn = when (Router.verdict(answers, settings)) {
            TurnVerdict.Done -> CascadeBrain.DONE
            TurnVerdict.Ignore -> CascadeBrain.IGNORE
            TurnVerdict.KeepListening -> "keep_listening"
        }
        val state = state(case, settings)
        val words = listOf(case.pending, case.heard).filter { it.isNotBlank() }.joinToString(" ")
        val decision = CeoRouter.withWords(state, answers, words, CeoRouter.decide(state, answers, bank))
        val action = when (val r = decision.route) {
            Route.Clarify, Route.AskRepeat -> "clarify"
            else -> r.wire
        }
        val task = when (val r = decision.route) {
            is Route.UpdateTask -> r.taskId
            is Route.CancelTask -> r.taskId
            is Route.ReadResult -> r.taskId
            is Route.Status -> r.taskId ?: CascadeBrain.NONE
            else -> CascadeBrain.NONE
        }
        return mapOf("turn" to turn, "action" to action, "task" to task)
    }

    /** A phone state holding [case]'s tasks (done tasks carry a result, as `read_result` candidates need). */
    fun state(case: EvalCase, settings: CascadeSettings = CascadeSettings()): State {
        val now = 1_760_000_000_000L
        val records = case.tasks.map { b ->
            TaskRecord(
                id = b.id, label = b.label, startedAt = now - b.ageS * 1_000, state = b.state,
                result = if (b.state == TaskState.Done) "result" else null,
                waiting = b.lastEvent.startsWith("waiting"), queued = b.lastEvent == CascadeBrain.QUEUED,
            )
        }
        return State(nowMs = now, wallMs = now, settings = settings, tasks = TaskTable(records, counter = records.size))
    }

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
