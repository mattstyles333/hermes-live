package com.folio.hermes.cascade

import com.folio.hermes.BusyMode
import com.folio.hermes.TurnUpdate
import com.folio.hermes.openrouter.FailureKind
import com.folio.hermes.openrouter.JevAnswer
import com.folio.hermes.openrouter.OpenRouterResult

/** How a clip fetch ended. */
enum class ClipOutcome {
    Ok,

    /** TTS failed after its retry. */
    Failed,

    /** Offline and not cached: skipped without a request. */
    Skipped,
}

/** A tap on a task card (v1 "Call screen visuals" §5). */
sealed interface TaskTap {
    data object Cancel : TaskTap

    /** "Not sent: tap to retry" (R-DUR-4), or retry a failed task with the same brief (R-TASK-7). */
    data object Retry : TaskTap

    /** "Read it all" / "Read in full" (R-TASK-5). */
    data object ReadAll : TaskTap

    /** ▶ on a finished card: play its spoken briefing (R-CONV-2). */
    data object Play : TaskTap

    /** "Tell it…": an update by text. */
    data class Tell(val text: String, val mode: UpdateMode) : TaskTap
}

/** The Resume card's choice (R-CONV-2). */
enum class ResumeChoice { Play, Continue, New }

/**
 * Everything that reaches the `CascadeActor`, one at a time through one channel (R-ARC-1):
 * inputs from the app and the ports, timers, and the results of [Effect]s (which carry the
 * effect's correlation id; R-ARC-2).
 *
 * Frozen (R-ARC-4): additive changes only, with a hub-approved change note.
 */
sealed interface Event {
    // ── lifecycle ───────────────────────────────────────────────────────────────────────────
    /** The call starts. [restored] is what the persistence seam read back (S-B), or null. */
    data class Start(val restored: State? = null) : Event

    /** The call ends: stop speaking, forget held words, log what is pending. */
    data object Stop : Event

    // ── microphone and speech-to-text (S-C, through `SpeechToText`) ─────────────────────────
    data object SpeechStarted : Event
    data object SpeechEnded : Event
    data class Partial(val text: String) : Event

    /** A fast-pass final, with per-word confidence when the engine has it (R-STT-4). */
    data class Final(val text: String, val words: List<Word> = emptyList()) : Event

    /** The accurate pass for committed turn [turnId] (R-STT-2); null [transcript] if none. */
    data class AccurateFinal(val turnId: String, val transcript: Transcript?) : Event

    /** VAD while Bill plays: the user has spoken for [continuousMs] (R-SPK-4). */
    data class UserSpeech(val continuousMs: Long) : Event

    /** Hold-to-talk pressed or released (R-STT-8). */
    data class HoldToTalk(val pressed: Boolean) : Event

    // ── audio out ───────────────────────────────────────────────────────────────────────────
    /** The player's output level, 0..1. */
    data class OutputLevel(val level: Float) : Event

    // ── connectivity ────────────────────────────────────────────────────────────────────────
    /** The phone's network came or went. Back online sends the outbox (each held turn once). */
    data class Network(val online: Boolean) : Event
    data class HermesConnection(val up: Boolean) : Event

    // ── the app ─────────────────────────────────────────────────────────────────────────────
    /** Play a slot the app picks itself (call start, approvals, warnings…). */
    data class Say(val slotId: String, val kind: SpeechKind = SpeechKind.Notice) : Event
    data class RenderProgressed(val progress: RenderProgress) : Event
    data class TaskTapped(val taskId: String, val tap: TaskTap) : Event

    /** The user picked [choice] for uncertain word [index] of turn [turnId] (v1 CEO §7, on screen). */
    data class WordChosen(val turnId: String, val index: Int, val choice: String) : Event
    data class ResumeChosen(val choice: ResumeChoice) : Event

    // ── timers ──────────────────────────────────────────────────────────────────────────────
    data class TimerFired(val timer: Timer) : Event

    // ── effect results ──────────────────────────────────────────────────────────────────────
    data class JevAnswered(val seq: Long, val result: OpenRouterResult<Map<String, JevAnswer>>, val atMs: Long) : Event
    data class WriterSentence(val seq: Long, val text: String) : Event

    /** The writer stream [seq] ended; [failure] when it threw. */
    data class WriterFinished(val seq: Long, val failure: FailureKind?) : Event
    data class WriterWrote(val seq: Long, val result: OpenRouterResult<String>) : Event
    data class BusyModeRead(val seq: Long, val mode: BusyMode?) : Event
    data class ClipReady(val piece: Piece, val outcome: ClipOutcome) : Event
    data class PlaybackStarted(val piece: Piece, val atMs: Long) : Event
    data class PlaybackFinished(val piece: Piece, val playedMs: Long, val durationMs: Long) : Event
    data class TaskUpdated(val taskId: String, val runId: Long, val update: TurnUpdate) : Event
    data class TaskRunEnded(val taskId: String, val runId: Long) : Event
    data class TaskInterrupted(val taskId: String, val ok: Boolean) : Event

    /** Hermes minted a stored session id for [taskId] (persist it; R-DUR-3). */
    data class TaskSessionBound(val taskId: String, val storedSessionId: String) : Event

    /**
     * [Effect.OpenTaskSession] for run [runId] is done: the session's [storedSessionId] (null when
     * the port cannot create ahead). The reducer stores it, then submits (R-DUR-2).
     */
    data class TaskSessionOpened(val taskId: String, val runId: Long, val storedSessionId: String?) : Event

    /**
     * Crash recovery found that Hermes never got run [runId] of [taskId] (the session has no such
     * prompt): send it again, exactly once ([com.folio.hermes.cascade.registry.TaskRecovery]).
     */
    data class RunNotReceived(val taskId: String, val runId: Long) : Event

    /**
     * Restore the stored state without starting a call (the off-call service, after process
     * death; R-CONV-3): tasks are recovered and finish, nothing is spoken. [restored] is what the
     * persistence seam read back (the actor fills it in).
     */
    data class Restore(val restored: State? = null) : Event

    /** Open past conversation [conversationId] from the history list (R-CONV-1): it becomes the current one. */
    data class OpenConversation(val conversationId: String) : Event
}

/** Events handled with no call open: tasks belong to the user and keep running between calls (R-CONV-3). */
val Event.offCall: Boolean
    get() = this is Event.Start || this is Event.TaskUpdated || this is Event.TaskRunEnded || this is Event.TaskInterrupted ||
        this is Event.TaskSessionBound || this is Event.HermesConnection || this is Event.RenderProgressed ||
        this is Event.TaskSessionOpened || this is Event.RunNotReceived || this is Event.Restore || this is Event.OpenConversation
