package com.folio.hermeslive.cascade

import com.folio.hermes.cascade.AppLines
import com.folio.hermes.cascade.State
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Bill's goodbye at hang-up (`end.bye`, or `end.bye_tasks` with tasks running), read from the
 * actor's state: whether one is queued or playing, whether the End button may add one, and the
 * wait for it to finish.
 */
internal object Goodbyes {
    private val SLOTS = setOf(AppLines.BYE, AppLines.BYE_TASKS)

    /** How long the End button's goodbye has to show up in the queue before the call goes without it. */
    const val START_MS = 400L

    fun slot(tasksRunning: Boolean): String = if (tasksRunning) AppLines.BYE_TASKS else AppLines.BYE

    /** A goodbye line is fetching, playing or queued. */
    fun pending(s: State): Boolean =
        s.speech.current?.slot in SLOTS || s.speech.queue.any { u -> !u.dropped && u.pieces.any { it.slot in SLOTS } }

    /**
     * The End button says goodbye only when the call is quiet: nobody speaking, no words heard or
     * held, no pause running and no turn being carried out. Otherwise the call ends at once (Bill
     * stops, half-finished words are held), as before: a goodbye must never let a turn commit
     * after the hang-up.
     */
    fun canSay(s: State): Boolean =
        !s.userSpeaking && s.speech.current == null && s.turn.heard.isEmpty() && s.turn.pending.isEmpty() &&
            s.turn.pause == null && s.actions.active == null && s.actions.waiting.isEmpty()

    /**
     * Wait until no goodbye is pending (it played to the end), at most [capMs]. [waitForStart]:
     * one was just asked for, so first wait (≤ [START_MS]) for it to be queued. True if it played out.
     */
    suspend fun awaitPlayed(state: StateFlow<State>, capMs: Long, waitForStart: Boolean): Boolean = withTimeoutOrNull(capMs) {
        if (waitForStart && withTimeoutOrNull(START_MS) { state.first { pending(it) } } == null) return@withTimeoutOrNull false
        state.first { !pending(it) }
        true
    } ?: false
}
