package com.folio.hermes.cascade

import com.folio.hermes.openrouter.JevQuestion
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** One past exchange: what the user said and what Bill was actually heard saying. */
data class Exchange(val user: String, val bill: String)

/** A voice task's state, as the phone sees it. */
enum class TaskState(val wire: String) { Running("running"), Done("done"), Failed("failed"), Cancelled("cancelled") }

/** A task as Jev sees it (`state.tasks[]`). */
data class TaskBrief(val id: String, val label: String, val state: TaskState, val lastEvent: String, val ageS: Long)

/** Everything one Jev call is about. */
data class TurnContext(
    /** Transcript since the last committed turn. */
    val heard: String,
    /** Utterances held by keep_listening. */
    val pending: List<String>,
    val silenceMs: Long,
    val recent: List<Exchange>,
    val tasks: List<TaskBrief>,
    /** Bill is talking. */
    val speaking: Boolean,
)

/** One System One request. [key] identifies the words it is about (an answer for other words is stale). */
data class JevRequest(val state: JsonObject, val questions: Map<String, JevQuestion>, val key: String)

/**
 * Builds the per-pause Jev call: `state` plus the questions `turn`, `action`, `line`, `enough`,
 * and, when there are tasks, `task` (and `update_mode` while any runs). Jev picks; it never writes.
 */
object CascadeBrain {
    const val TURN = "turn"
    const val ACTION = "action"
    const val TASK = "task"
    const val LINE = "line"
    const val ENOUGH = "enough"
    const val UPDATE_MODE = "update_mode"
    const val NONE = "none"

    // turn
    const val DONE = "done"
    const val KEEP_LISTENING = "keep_listening"
    const val IGNORE = "ignore"

    // action
    const val REPLY = "reply"
    const val NEW_TASK = "new_task"
    const val UPDATE_TASK = "update_task"
    const val STATUS = "status"
    const val CANCEL_TASK = "cancel_task"
    const val READ_RESULT = "read_result"
    const val END_CALL = "end_call"

    // enough
    const val LINE_ONLY = "line_only"
    const val LINE_THEN_WRITER = "line_then_writer"

    // update_mode
    const val QUEUE = "queue"
    const val REDIRECT = "redirect"

    /** The words a request is about: an early answer is reused only if these did not change. */
    fun key(heard: String, pending: List<String>): String = (pending + heard.trim()).joinToString("\u0001")

    fun request(ctx: TurnContext, bank: PhraseBank, backchannel: Boolean): JevRequest {
        val state = buildJsonObject {
            put("heard", ctx.heard.trim())
            putJsonArray("pending") { ctx.pending.forEach { add(it) } }
            put("silence_ms", ctx.silenceMs)
            putJsonArray("recent") {
                ctx.recent.forEach { e ->
                    addJsonObject {
                        put("user", e.user)
                        put("bill", e.bill)
                    }
                }
            }
            putJsonArray("tasks") {
                ctx.tasks.forEach { t ->
                    addJsonObject {
                        put("id", t.id)
                        put("label", t.label)
                        put("status", t.state.wire)
                        put("last_event", t.lastEvent)
                        put("age_s", t.ageS)
                    }
                }
            }
            put("speaking", ctx.speaking)
        }
        val questions = linkedMapOf<String, JevQuestion>()
        questions[TURN] = JevQuestion(
            "The user is talking to Bill, a voice assistant, and has paused for `silence_ms`. `heard` is what they said " +
                "since Bill last acted; `pending` is what they said before that is being held as one request. Is the turn finished?",
            linkedMapOf(
                DONE to "They finished a request, question or remark and expect Bill to act or answer",
                KEEP_LISTENING to "They are mid-thought or listing parts of one request; hold it and keep collecting",
                IGNORE to "Noise, filler, a cough, or Bill's own words echoed back; nothing to act on",
            ),
        )
        questions[ACTION] = JevQuestion(
            "If the turn is finished, what should Bill do with `pending` + `heard`, given `tasks` and `recent`?",
            linkedMapOf(
                REPLY to "Just talk: chat, a quick answer from general knowledge, or a reply about the conversation; no agent needed",
                NEW_TASK to "Start a new agent task: real work, lookups, anything needing tools, files, the web or the user's systems",
                UPDATE_TASK to "Pass new information or a change of plan to one of the existing tasks",
                STATUS to "Say how a task, or all tasks, are getting on",
                CANCEL_TASK to "Stop a running task",
                READ_RESULT to "Read out a finished task's result",
                END_CALL to "End the call: goodbye, that's all, hang up",
            ),
        )
        if (ctx.tasks.isNotEmpty()) {
            questions[TASK] = JevQuestion(
                "Which task do they mean, if the action is about a task?",
                linkedMapOf<String, String>().apply {
                    ctx.tasks.forEach { put(it.id, "${it.label} (${it.state.wire})") }
                    put(NONE, "No particular task, or all of them")
                },
            )
        }
        questions[LINE] = JevQuestion(
            "Which stock line should Bill say first? Pick the one whose use fits best, or none.",
            linkedMapOf<String, String>().apply {
                bank.jevSlots(backchannel).forEach { put(it.id, it.use) }
                put(NONE, "No stock line fits")
            },
        )
        questions[ENOUGH] = JevQuestion(
            "Is the stock line enough on its own, or does Bill need to say more after it?",
            linkedMapOf(
                LINE_ONLY to "The stock line fully answers; nothing specific to add",
                LINE_THEN_WRITER to "Bill must add something specific after the stock line",
            ),
        )
        if (ctx.tasks.any { it.state == TaskState.Running }) {
            questions[UPDATE_MODE] = JevQuestion(
                "If they are updating a running task: should it hear this after its current step, or change what it is doing now?",
                linkedMapOf(
                    QUEUE to "Add it after the current step (the default)",
                    REDIRECT to "Change what it is doing right now",
                ),
            )
        }
        return JevRequest(state, questions, key(ctx.heard, ctx.pending))
    }
}
