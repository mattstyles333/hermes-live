package com.folio.hermes.cascade

import com.folio.hermes.openrouter.JevQuestion
import kotlinx.serialization.json.JsonObject

/*
 * Core value types shared by the reducer and the routing, writer and budget packages (R-ARC-4:
 * frozen; additive changes only, with a hub-approved change note).
 */

/** One past exchange: what the user said and what Bill was actually heard saying. */
data class Exchange(val user: String, val bill: String)

/** A voice task's state, as the phone sees it. */
enum class TaskState(val wire: String) { Running("running"), Done("done"), Failed("failed"), Cancelled("cancelled") }

/**
 * A task as Jev and the writer see it. [lastEvent] and [ageS] are not in Jev's `state` since
 * R-RT-4 (the measured shape sends id, label and status only); they stay for the writer, the
 * R-CTX-1 budget and the R-RT-7 redirect window.
 */
data class TaskBrief(val id: String, val label: String, val state: TaskState, val lastEvent: String, val ageS: Long)

/** Everything one Jev call is about. */
data class TurnContext(
    /** Transcript since the user's previous pause (`latest`). */
    val heard: String,
    /** Utterances held by keep_listening; with [heard] they make `utterance`. */
    val pending: List<String>,
    val silenceMs: Long,
    val recent: List<Exchange>,
    val tasks: List<TaskBrief>,
    /** Bill is talking. */
    val speaking: Boolean,
)

/** One System One request. [key] identifies the words it is about (an answer for other words is stale). */
data class JevRequest(val state: JsonObject, val questions: Map<String, JevQuestion>, val key: String)

/** How an update reaches a running task. */
enum class UpdateMode(val wire: String) {
    /** `prompt.submit {queued: true}`: after the current step (the default). */
    Queue("queue"),

    /** Change what it is doing now (server busy mode, or interrupt then submit). */
    Redirect("redirect"),
}

/** What Jev's answer to a finished turn means. [wire] is also the handler registry key. */
sealed interface Route {
    val wire: String

    data object Reply : Route { override val wire = "reply" }

    /** Jev was not sure what to do (or which task): ask, never act. */
    data object Clarify : Route { override val wire = "clarify" }

    data object NewTask : Route { override val wire = "new_task" }

    data class UpdateTask(val taskId: String, val mode: UpdateMode) : Route { override val wire = "update_task" }

    /** [taskId] null: all tasks. Answered from the phone's event log only. */
    data class Status(val taskId: String?) : Route { override val wire = "status" }

    data class CancelTask(val taskId: String) : Route { override val wire = "cancel_task" }

    data class ReadResult(val taskId: String) : Route { override val wire = "read_result" }

    data object EndCall : Route { override val wire = "end_call" }

    /** Jev gave nothing usable: say "say that again?" and do nothing else. */
    data object AskRepeat : Route { override val wire = "ask_repeat" }

    /** Jev gave no response at all (offline): hold the turn in the outbox (memo mode, R-OFF). */
    data object Hold : Route { override val wire = "hold" }
}

/** A finished turn's plan: the [route], the stock [slot] to play first, and whether the [writer] speaks after it. */
data class Decision(val route: Route, val slot: String?, val writer: Boolean)

/** Jev's `turn` verdict after the confidence floor. */
enum class TurnVerdict { Done, KeepListening, Ignore }
