package com.folio.hermes.cascade.routing

import com.folio.hermes.BusyMode
import com.folio.hermes.cascade.ActionState
import com.folio.hermes.cascade.AppLines
import com.folio.hermes.cascade.CascadeSettings
import com.folio.hermes.cascade.Decision
import com.folio.hermes.cascade.DecisionRecord
import com.folio.hermes.cascade.Event
import com.folio.hermes.cascade.HeldTurn
import com.folio.hermes.cascade.JevRequest
import com.folio.hermes.cascade.PhraseBank
import com.folio.hermes.cascade.Route
import com.folio.hermes.cascade.SpeechKind
import com.folio.hermes.cascade.State
import com.folio.hermes.cascade.TaskRequest
import com.folio.hermes.cascade.TaskResult
import com.folio.hermes.cascade.Timer
import com.folio.hermes.cascade.TurnContext
import com.folio.hermes.cascade.TurnEvent
import com.folio.hermes.cascade.Tx
import com.folio.hermes.cascade.WriterJob
import com.folio.hermes.TurnUpdate
import com.folio.hermes.openrouter.FailureKind
import com.folio.hermes.openrouter.JevAnswer
import com.folio.hermes.openrouter.OpenRouterResult

/** What a handler step concluded. */
sealed interface Next {
    /** The action is finished: the reducer closes its reply, logs it and starts the next one. */
    data object Done : Next

    /** The action asked for a result ([com.folio.hermes.cascade.ActionState.waitingOn]) and resumes when it comes. */
    data class Wait(val stage: String) : Next
}

/** A result an action waited for, matched by its correlation key (stale ones never arrive here). */
sealed interface ActionResult {
    /** The action's writer stream ended. Failure handling (outbox, fail slot) already ran. */
    data class Written(val spoke: Boolean, val failure: FailureKind?) : ActionResult

    /** One whole writer answer ([Tx.requestWrite]). */
    data class Wrote(val result: OpenRouterResult<String>) : ActionResult

    /** The server's busy mode ([Tx.readBusyMode]). */
    data class Busy(val mode: BusyMode?) : ActionResult

    /** `session.interrupt` for [taskId] came back. */
    data class Interrupted(val taskId: String, val ok: Boolean) : ActionResult

    /** A slice timer the action set (key `timer:<name>:<seq>`), e.g. the R-STT-5 8 s wait. */
    data class TimerFired(val timer: Timer) : ActionResult
}

/**
 * One route's action (the C1 `execute` branch for that route), as a small state machine. The
 * reducer opens the reply utterance, remembers the turn and plays the decision's stock slot (if
 * [playsSlotFirst]) before [start]; it closes the reply, logs the decision and reports
 * `cascade.action` after [Next.Done].
 */
interface RouteHandler {
    /** C1: the stock slot plays first, except when a new task would be over the cap. */
    fun playsSlotFirst(tx: Tx): Boolean = true

    fun start(tx: Tx): Next

    fun resume(tx: Tx, result: ActionResult): Next = Next.Done
}

/** Finished task results: queued for speech, written for the ear (R-TASK-5). */
interface ResultHandler {
    /** A task finished (or failed): queue its result utterance. */
    fun arrived(tx: Tx, result: TaskResult)

    /** The result's writer stream [job] ended ([spoke]: at least one sentence went in). */
    fun written(tx: Tx, job: WriterJob, spoke: Boolean)
}

/**
 * Turn hooks: the decisions around a turn that the routing slice (S-A) owns. Every default is
 * the C1 behaviour, so the reducer never changes for a routing feature.
 */
interface TurnHooks {
    /** The Jev request for a pause (R-RT-2, R-RT-4). The reducer then applies the R-CTX-1 budget. */
    fun ask(ctx: TurnContext, bank: PhraseBank, settings: CascadeSettings): JevRequest = CascadeBrain.request(ctx, bank, settings.backchannel)

    /** R-RT-8: false makes this pause commit on the timer alone (`pause + noJevCommitExtraMs`), with no Jev call. */
    fun useJev(state: State): Boolean = true

    /** Jev's answers for a finished turn → the plan (R-RT-3, R-RT-5..7). */
    fun decide(state: State, answers: Map<String, JevAnswer>, bank: PhraseBank): Decision =
        Router.decide(answers, state.tasks.briefs(state.wallMs), bank, state.settings)

    /**
     * Jev gave nothing usable (R-RT-8). C1: no response at all (offline) holds the turn in the
     * outbox; anything else says `clarify.again`. Never a task. [base] is the turn's record so far.
     */
    fun noAnswer(tx: Tx, event: TurnEvent.NoAnswer, base: DecisionRecord) {
        val text = (event.pending + event.heard).filter { it.isNotBlank() }.joinToString(" ")
        if (event.failure.kind == FailureKind.Offline) {
            tx.holdOffline(event.turnId, HeldTurn(text, event.speechEndAt, tx.s.wallMs))
            tx.log(base.copy(outcome = "held", failure = event.failure.kind.wire))
        } else {
            val line = tx.say(AppLines.SAY_AGAIN, SpeechKind.Reply)
            tx.log(base.copy(outcome = "no_answer", failure = event.failure.kind.wire, route = Route.AskRepeat.wire, slot = AppLines.SAY_AGAIN, line = line))
        }
    }

    /** keep_listening: a backchannel slot to murmur, or null. */
    fun backchannel(state: State, answers: Map<String, JevAnswer>, bank: PhraseBank): String? = Router.backchannel(answers, bank, state.settings)

    /**
     * A turn was committed and its action built, before it is queued. Add gates (e.g. `accurate`
     * for R-STT-2 with an [com.folio.hermes.cascade.Effect.AccuratePass], `key_doubt` for
     * R-STT-5) and the reducer waits until they open ([Tx] removes a gate via `openGate`).
     */
    fun committed(tx: Tx, action: ActionState, event: TurnEvent.Done): ActionState = action

    /** The accurate pass for a turn came back (R-STT-2). Default: nothing waits for it. */
    fun accurate(tx: Tx, event: Event.AccurateFinal) = Unit

    /** An approval, clarify or withdrawal from a task (tap only; R-TASK-6). */
    fun taskRequest(tx: Tx, request: TaskRequest) {
        when (request.update) {
            is TurnUpdate.ApprovalNeeded -> tx.say(AppLines.NEEDS_OK)
            is TurnUpdate.ClarifyNeeded -> tx.say(AppLines.QUESTION)
            else -> Unit
        }
    }

    /** A task update the task table applied (e.g. speak the summary after `Accepted`, R-TASK-2; `error.gave_up` on a failed submit). */
    fun taskUpdated(tx: Tx, event: Event.TaskUpdated) = Unit

    /** A timer the reducer does not own (a slice's). Return true if handled. */
    fun timer(tx: Tx, timer: Timer): Boolean = false

    /** A tap on a task card: Cancel, ReadAll, Tell (Retry is the registry's, Play the conversation's). Default: none yet (phase 1). */
    fun tap(tx: Tx, event: Event.TaskTapped) = Unit

    /** The user picked an alternative for an uncertain word. Default: nothing (phase 1). */
    fun wordChosen(tx: Tx, event: Event.WordChosen) = Unit
}

/**
 * The routing seam (R-ARC-1, spec v2 §12): the reducer delegates every route to the handler
 * registered under its [Route.wire], plus the [hooks] and the [result] handler. S-A changes
 * behaviour by changing or adding handlers here, never by editing the reducer.
 */
class HandlerRegistry(
    private val routes: Map<String, RouteHandler>,
    val hooks: TurnHooks = object : TurnHooks {},
    val result: ResultHandler = SpokenResults,
) {
    fun handler(route: Route): RouteHandler = routes[route.wire] ?: error("no handler for route ${route.wire}")

    /** A copy with [handler] registered for [wire] (replacing any). */
    fun with(wire: String, handler: RouteHandler): HandlerRegistry = HandlerRegistry(routes + (wire to handler), hooks, result)

    fun with(hooks: TurnHooks): HandlerRegistry = HandlerRegistry(routes, hooks, result)

    fun with(result: ResultHandler): HandlerRegistry = HandlerRegistry(routes, hooks, result)

    val wires: Set<String> get() = routes.keys

    companion object {
        /** C1's behaviour: one handler per route. */
        fun default(): HandlerRegistry = HandlerRegistry(
            mapOf(
                Route.Reply.wire to ReplyHandler,
                Route.Clarify.wire to ClarifyHandler,
                Route.AskRepeat.wire to AskRepeatHandler,
                Route.NewTask.wire to NewTaskHandler,
                Route.UpdateTask("", com.folio.hermes.cascade.UpdateMode.Queue).wire to UpdateTaskHandler,
                Route.Status(null).wire to StatusHandler,
                Route.CancelTask("").wire to CancelTaskHandler,
                Route.ReadResult("").wire to ReadResultHandler,
                Route.EndCall.wire to EndCallHandler,
            ),
        )
    }
}

