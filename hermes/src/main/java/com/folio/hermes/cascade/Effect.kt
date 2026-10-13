package com.folio.hermes.cascade

/** A named timer. [seq] makes it unique per owner (a pause, a turn): a fired timer whose seq is stale is dropped. */
data class Timer(val name: String, val seq: Long)

/**
 * What `reduce` asks the world to do (R-ARC-1/2). Executors run effects; every result comes back
 * as an [Event] carrying the same correlation id ([Timer], `seq`, `taskId` + `runId`), and
 * `reduce` drops results whose id is stale.
 *
 * Ordering rule (R-DUR-1): the actor runs every durable effect ([Persist], [Append]) of one step
 * before any other effect of that step.
 *
 * Frozen (R-ARC-4): additive changes only, with a hub-approved change note.
 */
sealed interface Effect {
    // ── durable (run first) ─────────────────────────────────────────────────────────────────
    /** Write [value] under [key] in the `Store` (null removes it). */
    data class Persist(val key: String, val value: String?) : Effect

    /** Append [line] to the `Store` log [log]. */
    data class Append(val log: String, val line: String) : Effect

    // ── timers ──────────────────────────────────────────────────────────────────────────────
    /** Fire [timer] after [delayMs] (replaces a running timer with the same key). Result: [Event.TimerFired]. */
    data class StartTimer(val timer: Timer, val delayMs: Long) : Effect
    data class CancelTimer(val timer: Timer) : Effect

    // ── Jev ─────────────────────────────────────────────────────────────────────────────────
    /** One System One call. Result: [Event.JevAnswered] with [seq]. */
    data class CallJev(val seq: Long, val request: JevRequest, val timeoutMs: Long, val early: Boolean) : Effect
    data class CancelJev(val seq: Long) : Effect

    // ── writer ──────────────────────────────────────────────────────────────────────────────
    /** Stream the writer as sentences. Results: [Event.WriterSentence]s, then [Event.WriterFinished] (not after [CancelWriter]). */
    data class StreamWriter(val seq: Long, val system: String, val prompt: String) : Effect
    data class CancelWriter(val seq: Long) : Effect

    /** One whole writer answer. Result: [Event.WriterWrote]. */
    data class Write(val seq: Long, val system: String, val prompt: String) : Effect

    // ── Bill's voice ────────────────────────────────────────────────────────────────────────
    /**
     * Get [piece]'s clip (TTS cache first). [prefetch]: fetch ahead, no result event. Otherwise
     * the result is [Event.ClipReady]; with [offline] an uncached clip is skipped at once.
     */
    data class FetchClip(val piece: Piece, val offline: Boolean, val prefetch: Boolean = false) : Effect

    /** Forget fetched or fetching clips of [utterances], except [keep] (the piece playing). */
    data class DropClips(val utterances: Set<Long>, val keep: Piece?) : Effect

    /** Play [piece]'s fetched clip. Results: [Event.PlaybackStarted], then [Event.PlaybackFinished]. */
    data class Play(val piece: Piece) : Effect

    /** Stop playback now (cut-in, call end). */
    data object StopPlayback : Effect

    // ── Hermes ──────────────────────────────────────────────────────────────────────────────
    /**
     * Submit one turn to task [taskId]'s session (opening it first: new, or [storedSessionId]
     * resumed). Results: [Event.TaskUpdated]s, then always [Event.TaskRunEnded] (also when
     * cancelled). [stopOnRedirect]: stop reading at a redirected or steered `Accepted` (the
     * running turn's flow owns the answer).
     */
    data class SubmitTask(
        val taskId: String,
        val runId: Long,
        val label: String,
        val storedSessionId: String?,
        val text: String,
        val voiceContext: String,
        val queued: Boolean,
        val interruptFirst: Boolean,
        val stopOnRedirect: Boolean,
    ) : Effect

    /**
     * Create task [taskId]'s Hermes session ahead of run [runId]'s prompt (`HermesPort.create`).
     * Result: [Event.TaskSessionOpened]; on failure the run fails like a submit
     * ([Event.TaskUpdated] `Failed`, then [Event.TaskRunEnded]).
     */
    data class OpenTaskSession(val taskId: String, val runId: Long, val label: String) : Effect

    /** `session.interrupt`. Result: [Event.TaskInterrupted]. */
    data class InterruptTask(val taskId: String) : Effect

    /** Stop our side of every run of [taskId] (no session yet, so nothing is submitted later). */
    data class CancelTaskRuns(val taskId: String) : Effect

    /** Read the server's busy mode. Result: [Event.BusyModeRead]. */
    data class ReadBusyMode(val seq: Long) : Effect

    // ── speech-to-text ──────────────────────────────────────────────────────────────────────
    /** Run the accurate pass for committed turn [turnId] (R-STT-2). Result: [Event.AccurateFinal]. */
    data class AccuratePass(val turnId: String) : Effect

    // ── outputs ─────────────────────────────────────────────────────────────────────────────
    /** Save a phrase slot's rotation (the cursor survives restarts; R-SPK-1). */
    data class SaveRotation(val slotId: String, val rotation: Rotation) : Effect

    /** One decision-log record (on-device only; R-Q-2). */
    data class LogDecision(val record: DecisionRecord) : Effect

    /** One telemetry event: names, codes and timings only, never words (R-Q-2). */
    data class Report(val name: String, val attrs: Map<String, Any?>, val warn: Boolean = false) : Effect

    /** A one-shot moment for the call screen. */
    data class Emit(val event: CascadeEvent) : Effect

    /** Bill said goodbye: the app ends the call ([tasksRunning]: tasks carry on, R-CONV-3). */
    data class EndCall(val tasksRunning: Boolean) : Effect

    /** A notification (R-CONV-3: a finished task off-call, "Hear it"). [kind] is a stable id. */
    data class Notify(val kind: String, val taskId: String? = null) : Effect

    /** A haptic tick ([kind]: `turn_committed`, `task_finished`). */
    data class Haptic(val kind: String) : Effect

    /** The user corrected [heard] to [chosen] on screen: the app adds it to the vocabulary (`VocabularyStore.learn`). */
    data class LearnWord(val heard: String, val chosen: String) : Effect
}
