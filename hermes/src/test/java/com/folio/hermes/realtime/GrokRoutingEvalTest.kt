package com.folio.hermes.realtime

import com.folio.hermes.cascade.TaskState
import com.folio.hermes.cascade.routing.EvalCase
import com.folio.hermes.cascade.routing.EvalScoring
import com.folio.hermes.cascade.routing.RoutingEvals
import com.folio.hermes.telemetry.TelemetrySink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in tests that call the real Grok Voice API. They run only with `XAI_API_KEY` set and never in CI. */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class LiveGrok

/**
 * The 88 routing phrases (hub `hermes-cascade-routing-evals-*.jsonl`) sent to Grok Voice as text
 * input items, with the same tools and instructions as a call, one session per case (manual turns:
 * `turn_detection` null, so no idle session time is billed). The case's tasks go in first as a
 * system note (as a call's context would); held words go in as an earlier user message. Grok's
 * first tool call is mapped onto the eval's `turn`/`action`/`task` and scored with the same
 * [EvalScoring] as `LiveJevEvalTest`: the gate is the harmful rate (≤ 3%), friction is reported.
 *
 * Run by the hub with the user's key (never in CI; the key is read from the environment and never
 * printed): `XAI_API_KEY=… ./gradlew :hermes:testDebugUnitTest --tests '*GrokRoutingEvalTest*' -i`.
 * Cost: about 88 short sessions, each a few seconds, plus $0.004 per text item.
 */
@LiveGrok
class GrokRoutingEvalTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun routingEvalsAgainstGrokVoice() {
        val key = System.getenv("XAI_API_KEY")
        assumeTrue("XAI_API_KEY not set", !key.isNullOrBlank())
        assumeTrue("never in CI", System.getenv("CI").isNullOrBlank())
        val model = System.getenv("GROK_VOICE_MODEL")?.takeIf { it.isNotBlank() } ?: GrokModels.DEFAULT
        val all = mutableListOf<Pair<EvalCase, EvalScoring.Outcome>>()
        for (set in listOf(RoutingEvals.TUNED, RoutingEvals.HELD_OUT)) {
            val outcomes = RoutingEvals.load(set).map { case ->
                val started = System.nanoTime()
                val call = runBlocking { firstCall(key!!, model, case) }
                val effective = effective(call)
                val outcome = EvalScoring.outcome(case, effective)
                println("${case.id}: ${call?.name ?: "(no tool)"} ${effective["task"]} → $outcome (${(System.nanoTime() - started) / 1_000_000} ms)")
                case to outcome
            }
            val rates = EvalScoring.rates(outcomes.map { it.second })
            println("$set: harmful ${rates.harmful}/${rates.total} (${"%.1f".format(rates.harmfulPct)}%), friction ${rates.friction}/${rates.total} (${"%.1f".format(rates.frictionPct)}%)")
            all += outcomes
        }
        val rates = EvalScoring.rates(all.map { it.second })
        println("all: harmful ${rates.harmful}/${rates.total} (${"%.1f".format(rates.harmfulPct)}%), friction ${rates.friction}/${rates.total} (${"%.1f".format(rates.frictionPct)}%)")
        all.filter { it.second is EvalScoring.Outcome.Harmful }.forEach { (c, o) -> println("harmful ${c.id}: $o") }
        assertTrue("harmful rate ${rates.harmfulPct}% is over the ${EvalScoring.HARMFUL_GATE_PCT}% gate", rates.passes)
    }

    /** One case in its own session: the task note, held words, the words heard, then one response; its first tool call. */
    private suspend fun firstCall(key: String, model: String, case: EvalCase): FunctionCall? {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val config = GrokConfig(model = model, resumption = false, reconnectAttempts = 0)
        val realtime = GrokRealtime(
            OkHttpClient(), key = { key }, config = { config },
            spec = { SessionSpec(GrokTools.INSTRUCTIONS, GrokTools.DEFINITIONS, turnDetection = false) }, scope = scope, telemetry = TelemetrySink.None,
        )
        try {
            realtime.connect()
            withTimeoutOrNull(15_000) { realtime.link.first { it is LinkState.Open } } ?: return null
            val events = realtime.events
            // Wait for the session to take the config before any item.
            withTimeoutOrNull(15_000) { events.first { it == ServerEvent.SessionUpdated } }
            if (case.tasks.isNotEmpty()) realtime.send(RealtimeProtocol.message("system", taskNote(case)))
            if (case.pending.isNotBlank()) realtime.send(RealtimeProtocol.message("user", case.pending))
            realtime.send(RealtimeProtocol.message("user", case.heard))
            realtime.send(RealtimeProtocol.responseCreate())
            val seen = withTimeoutOrNull(30_000) {
                events.takeWhile { it !is ServerEvent.ResponseDone }.toList()
            }.orEmpty()
            return seen.filterIsInstance<ServerEvent.Call>().firstOrNull()?.call
        } finally {
            realtime.close()
            scope.cancel()
        }
    }

    /** The tasks as a call's context note would put them. */
    private fun taskNote(case: EvalCase): String = "Open tasks: " + case.tasks.joinToString("; ") { t ->
        val status = when {
            t.lastEvent.startsWith("waiting") -> "waiting on the user's approval"
            t.lastEvent == "queued" -> "queued"
            t.state == TaskState.Done -> "done, has a result"
            else -> t.state.wire
        }
        "${t.id} \"${t.label}\" $status"
    } + "."

    /** Grok's tool choice as the eval's effective decision (no tool: Grok just answered). */
    private fun effective(call: FunctionCall?): Map<String, String> {
        if (call == null) return mapOf("turn" to "done", "action" to "reply", "task" to "none")
        val args = runCatching { json.parseToJsonElement(call.arguments).jsonObject }.getOrNull()
        val task = (args?.get("task_id") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && !it.equals("all", true) } ?: "none"
        val action = when (call.name) {
            GrokTools.START_TASK -> "new_task"
            GrokTools.UPDATE_TASK -> "update_task"
            GrokTools.CANCEL_TASK -> "cancel_task"
            GrokTools.TASK_STATUS -> "status"
            GrokTools.READ_RESULT -> "read_result"
            GrokTools.END_CALL -> "end_call"
            else -> "reply"
        }
        return mapOf("turn" to "done", "action" to action, "task" to if (action == "new_task" || action == "end_call") "none" else task)
    }
}
