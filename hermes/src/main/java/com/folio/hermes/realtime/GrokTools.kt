package com.folio.hermes.realtime

import com.folio.hermes.cascade.CascadeTasks
import com.folio.hermes.cascade.TaskReply
import com.folio.hermes.cascade.TaskView
import com.folio.hermes.cascade.UpdateMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The tools Grok gets, its instructions, and running a call against the shared task API. Tool
 * shape: the flat function form (`{"type":"function","name",…,"parameters"}`), as in the xAI
 * guide and its official phone cookbook (the WebSocket schema nests it under `function`; the
 * working examples do not).
 */
object GrokTools {
    const val START_TASK = "start_task"
    const val UPDATE_TASK = "update_task"
    const val TASK_STATUS = "task_status"
    const val CANCEL_TASK = "cancel_task"
    const val READ_RESULT = "read_result"
    const val END_CALL = "end_call"

    val NAMES = listOf(START_TASK, UPDATE_TASK, TASK_STATUS, CANCEL_TASK, READ_RESULT, END_CALL)

    /**
     * Bill-like: warm, brief, the CEO's assistant. Real work is delegated; results are never
     * invented; status only from `task_status`; approvals are tapped on screen.
     */
    val INSTRUCTIONS: String = """
        You are Grok, the voice of a busy CEO's assistant on a phone call. Be warm, brief and plain: one or two short sentences, spoken English, no lists or markdown.
        You cannot do real work yourself. Hermes agents do it. For anything that needs looking up, checking, writing, sending, changing or deciding, call start_task with a complete brief (everything the agent needs, in the user's own words and names) and a 2 to 4 word label. It returns at once; then say you're on it in a few words. Do not wait for it.
        Start one task per separate job. If the user adds to or corrects a running task, call update_task: mode "queue" adds it after the current step, mode "redirect" changes course now.
        Never invent results, numbers, names or progress. You only know what tools tell you. For "how's it going" or "is it done", call task_status and say what it reports. If a task has no result yet, say it's still working.
        When a system note says a task finished, tell the user the headline in a sentence or two and any decision they need to make. The full result is on their screen; offer more and call read_result only if they ask.
        Approvals and the agents' questions are answered by tapping on the screen, never by voice. If one is waiting, tell the user to tap it. Never say you approved anything.
        To stop work, call cancel_task (task_id "all" stops everything). When the user says goodbye or is done, say a short goodbye, then call end_call. Tasks keep running after the call.
        If you didn't catch something, ask briefly. Never make up a task id: use the ids tools gave you.
    """.trimIndent()

    val DEFINITIONS: JsonArray = buildJsonArray {
        add(
            tool(START_TASK, "Delegate a job to a Hermes agent. Returns at once with the task id; the result arrives later as a system note.") {
                str("brief", "The complete instruction for the agent: what to do, with every name, number and detail the user gave.")
                str("label", "A 2 to 4 word label for the task card, like \"Stock check\".")
                required("brief", "label")
            },
        )
        add(
            tool(UPDATE_TASK, "Add to or correct a task that was started.") {
                str("task_id", "The task id, like t1.")
                str("text", "What to tell the agent, in the user's words.")
                enumOf("mode", "queue: after its current step. redirect: change course now.", "queue", "redirect")
                required("task_id", "text", "mode")
            },
        )
        add(
            tool(TASK_STATUS, "How tasks are getting on, from the task log. Omit task_id for all tasks.") {
                str("task_id", "Optional task id.")
            },
        )
        add(
            tool(CANCEL_TASK, "Stop a task, or every task with task_id \"all\".") {
                str("task_id", "The task id, or \"all\".")
                required("task_id")
            },
        )
        add(
            tool(READ_RESULT, "The full result of a finished task, when the user asks for detail.") {
                str("task_id", "The task id.")
                required("task_id")
            },
        )
        add(tool(END_CALL, "End the call after saying goodbye. Tasks keep running.") {})
    }

    private class Params {
        val props = linkedMapOf<String, JsonObject>()
        val required = mutableListOf<String>()
        fun str(name: String, description: String) {
            props[name] = buildJsonObject { put("type", "string"); put("description", description) }
        }
        fun enumOf(name: String, description: String, vararg values: String) {
            props[name] = buildJsonObject {
                put("type", "string")
                put("description", description)
                putJsonArray("enum") { values.forEach { add(JsonPrimitive(it)) } }
            }
        }
        fun required(vararg names: String) {
            required += names
        }
    }

    private fun tool(name: String, description: String, params: Params.() -> Unit): JsonObject {
        val p = Params().apply(params)
        return buildJsonObject {
            put("type", "function")
            put("name", name)
            put("description", description)
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") { p.props.forEach { (k, v) -> put(k, v) } }
                putJsonArray("required") { p.required.forEach { add(JsonPrimitive(it)) } }
            }
        }
    }
}

/** One tool call's outcome: its [output] (JSON, for `function_call_output`), and whether it ends the call. */
data class ToolOutcome(val output: String, val endCall: Boolean = false, val ok: Boolean = true)

/**
 * Runs Grok's function calls on the shared task API. Each call's `call_id` is its idempotency key,
 * so a call replayed after a reconnect never starts or sends anything twice. Outputs are short
 * JSON the model reads; results come only from the task table.
 */
class GrokToolRunner(private val tasks: CascadeTasks) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun run(call: FunctionCall): ToolOutcome {
        val args = runCatching { json.parseToJsonElement(call.arguments).jsonObject }.getOrNull() ?: JsonObject(emptyMap())
        fun arg(name: String): String? = (args[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        return when (call.name) {
            GrokTools.START_TASK -> {
                val brief = arg("brief") ?: return error("brief is required")
                reply(tasks.startTask(brief, arg("label") ?: brief.split(' ').take(3).joinToString(" "), null, call.callId))
            }
            GrokTools.UPDATE_TASK -> {
                val id = arg("task_id") ?: return error("task_id is required")
                val text = arg("text") ?: return error("text is required")
                val mode = if (arg("mode") == "redirect") UpdateMode.Redirect else UpdateMode.Queue
                reply(tasks.updateTask(id, text, mode, call.callId))
            }
            GrokTools.TASK_STATUS -> {
                val views = tasks.status(arg("task_id"))
                ToolOutcome(buildJsonObject { put("tasks", buildJsonArray { views.forEach { add(view(it)) } }) }.toString())
            }
            GrokTools.CANCEL_TASK -> {
                val id = arg("task_id") ?: return error("task_id is required")
                reply(tasks.cancelTask(if (id.equals("all", ignoreCase = true)) CascadeTasks.ALL else id, call.callId))
            }
            GrokTools.READ_RESULT -> {
                val id = arg("task_id") ?: return error("task_id is required")
                val result = tasks.readResult(id)
                val out = if (result == null) {
                    buildJsonObject { put("task_id", id); put("result", JsonPrimitive(null as String?)); put("note", "No result yet. Say it's still working, or check task_status.") }
                } else {
                    buildJsonObject {
                        put("task_id", id)
                        put("result", result.take(RESULT_CHARS))
                        if (result.length > RESULT_CHARS) put("note", "Cut for speech; the full text is on the screen.")
                    }
                }
                ToolOutcome(out.toString())
            }
            GrokTools.END_CALL -> ToolOutcome(buildJsonObject { put("ok", true) }.toString(), endCall = true)
            else -> error("unknown tool")
        }
    }

    private fun error(why: String) = ToolOutcome(buildJsonObject { put("error", why) }.toString(), ok = false)

    private fun reply(r: TaskReply): ToolOutcome = when (r) {
        is TaskReply.Started -> ToolOutcome(
            buildJsonObject {
                put("task_id", r.taskId)
                put("label", r.label)
                put("status", if (r.queued) "queued" else "started")
                put("note", if (r.queued) "All agents are busy; it starts when one frees up. Tell the user it's queued." else "Started ${r.taskId}. Say you're on it.")
            }.toString(),
        )
        is TaskReply.Updated -> ToolOutcome(buildJsonObject { put("task_id", r.taskId); put("sent", r.how) }.toString())
        is TaskReply.Cancelled -> ToolOutcome(buildJsonObject { putJsonArray("cancelled") { r.taskIds.forEach { add(JsonPrimitive(it)) } } }.toString())
        is TaskReply.TooLate -> ToolOutcome(buildJsonObject { put("task_id", r.taskId); put("note", "Already finished.") }.toString())
        is TaskReply.NotFound -> ToolOutcome(buildJsonObject { put("error", "no task ${r.taskId}"); put("note", "Call task_status to see the ids.") }.toString(), ok = false)
        TaskReply.AtCap -> ToolOutcome(buildJsonObject { put("error", "every agent is busy"); put("note", "Ask the user to wait or cancel a task.") }.toString(), ok = false)
        TaskReply.Noted -> ToolOutcome(buildJsonObject { put("ok", true) }.toString())
        TaskReply.NotReady, TaskReply.NoReply -> ToolOutcome(buildJsonObject { put("error", "the task list did not answer"); put("note", "Say so and try again in a moment.") }.toString(), ok = false)
    }

    private fun view(v: TaskView): JsonObject = buildJsonObject {
        put("task_id", v.id)
        put("label", v.label)
        put("status", v.status)
        put("last_event", v.lastEvent)
        put("elapsed_s", v.elapsedS)
        put("steps", v.steps)
        v.currentTool?.let { put("now_using", it) }
        v.lastNote?.let { put("last_note", it) }
        put("has_result", v.hasResult)
    }

    companion object {
        /** A result read out is cut here (the card has all of it). */
        const val RESULT_CHARS = 2_000
    }
}
