package com.folio.hermes.cascade

import com.folio.hermes.SubmitStatus
import com.folio.hermes.TurnUpdate
import com.folio.hermes.cascade.ports.AudioOut
import com.folio.hermes.cascade.ports.Clock
import com.folio.hermes.cascade.ports.HermesPort
import com.folio.hermes.cascade.ports.OpenRouterPort
import com.folio.hermes.openrouter.FailureKind
import com.folio.hermes.openrouter.OpenRouterResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch

/*
 * Effect executors: they do I/O and post every result back as an [Event] with the effect's
 * correlation id. They hold connection resources (jobs, fetched clips), never call state.
 * The actor uses them, and so do the test drivers for the C1 tests, so both run the same code.
 */

/** Runs Hermes task effects on a [HermesPort]. */
class TaskRunner(private val scope: CoroutineScope, private val port: HermesPort, private val post: (Event) -> Unit) {
    private val runs = HashMap<String, MutableList<Job>>()

    /** [Effect.SubmitTask]: open the session, submit, stream the turn; always ends with [Event.TaskRunEnded]. */
    fun submit(e: Effect.SubmitTask) {
        port.open(e.taskId, e.label, e.storedSessionId)
        val job = scope.launch {
            try {
                port.submit(e.taskId, e.text, e.voiceContext, queued = e.queued, interruptFirst = e.interruptFirst)
                    .transformWhile { update ->
                        emit(update)
                        // A redirect's answer arrives on the running turn's flow; this one stops here.
                        !(e.stopOnRedirect && update is TurnUpdate.Accepted &&
                            (update.status == SubmitStatus.Redirected || update.status == SubmitStatus.Steered))
                    }
                    .collect { post(Event.TaskUpdated(e.taskId, e.runId, it)) }
            } catch (c: CancellationException) {
                throw c
            } catch (x: Exception) {
                // Anything unexpected ends this turn as a failure, never a silent "done".
                post(Event.TaskUpdated(e.taskId, e.runId, TurnUpdate.Failed(x.javaClass.simpleName)))
            } finally {
                post(Event.TaskRunEnded(e.taskId, e.runId))
            }
        }
        synchronized(runs) { runs.getOrPut(e.taskId) { mutableListOf() } += job }
    }

    /** [Effect.OpenTaskSession]: create the session; [Event.TaskSessionOpened], or the run fails like a submit. */
    fun open(e: Effect.OpenTaskSession) {
        val job = scope.launch {
            val stored = try {
                port.create(e.taskId, e.label)
            } catch (c: CancellationException) {
                // Cancelled before the prompt: the run ends unsent.
                post(Event.TaskRunEnded(e.taskId, e.runId))
                throw c
            } catch (x: Exception) {
                post(Event.TaskUpdated(e.taskId, e.runId, TurnUpdate.Failed(x.javaClass.simpleName)))
                post(Event.TaskRunEnded(e.taskId, e.runId))
                return@launch
            }
            post(Event.TaskSessionOpened(e.taskId, e.runId, stored))
        }
        synchronized(runs) { runs.getOrPut(e.taskId) { mutableListOf() } += job }
    }

    fun interrupt(taskId: String) {
        scope.launch {
            val ok = try {
                port.interrupt(taskId)
            } catch (c: CancellationException) {
                throw c
            } catch (x: Exception) {
                false
            }
            post(Event.TaskInterrupted(taskId, ok))
        }
    }

    fun cancelRuns(taskId: String) {
        synchronized(runs) { runs[taskId]?.toList() }.orEmpty().forEach { it.cancel() }
    }

    fun busyMode(seq: Long) {
        scope.launch {
            val mode = try {
                port.busyMode()
            } catch (c: CancellationException) {
                throw c
            } catch (x: Exception) {
                null
            }
            post(Event.BusyModeRead(seq, mode))
        }
    }

    fun cancelAll() {
        synchronized(runs) { runs.values.flatten().also { runs.clear() } }.forEach { it.cancel() }
    }
}

/**
 * Bill's voice (C1 `Speaker`'s I/O half): fetches clips (cache first), keeps one prefetched, and
 * plays them on [out]. The reducer decides what plays and when.
 */
class SpeechPlayer(
    private val scope: CoroutineScope,
    private val voice: OpenRouterPort,
    private val out: AudioOut,
    private val clock: Clock,
    private val post: (Event) -> Unit,
) {
    private val fetches = LinkedHashMap<Piece, Deferred<OpenRouterResult<AudioClip>>>()
    private val ready = HashMap<Piece, AudioClip>()
    private var playing: Job? = null

    fun fetch(e: Effect.FetchClip) {
        val piece = e.piece
        if (e.prefetch) {
            if (e.offline && !voice.isCached(piece.text)) return
            fetchAsync(piece)
            return
        }
        // While offline, a piece that is not cached is skipped at once instead of waiting out timeouts.
        if (e.offline && !voice.isCached(piece.text) && !fetched(piece)) {
            post(Event.ClipReady(piece, ClipOutcome.Skipped))
            return
        }
        val deferred = fetchAsync(piece)
        scope.launch {
            val clip = deferred.await()
            synchronized(fetches) {
                fetches.remove(piece)
                if (clip is OpenRouterResult.Ok) ready[piece] = clip.value
            }
            post(Event.ClipReady(piece, if (clip is OpenRouterResult.Ok) ClipOutcome.Ok else ClipOutcome.Failed))
        }
    }

    fun play(piece: Piece) {
        val clip = synchronized(fetches) { ready.remove(piece) }
        playing = scope.launch {
            if (clip == null) {
                post(Event.PlaybackFinished(piece, 0, 1))
                return@launch
            }
            post(Event.PlaybackStarted(piece, clock.nowMs()))
            val played = out.play(clip)
            post(Event.PlaybackFinished(piece, played, clip.durationMs))
        }
    }

    /**
     * Fetch [lines] into the TTS cache ahead (`Effect.Prerender`), [PRERENDER_PARALLEL] at a
     * time, in order, skipping those already cached. Failures are ignored: the line is fetched
     * again when it plays. Cancelled with the call.
     */
    fun prerender(lines: List<String>) {
        val missing = lines.filterNot { voice.isCached(it) }
        if (missing.isEmpty()) return
        val gate = kotlinx.coroutines.sync.Semaphore(PRERENDER_PARALLEL)
        missing.forEach { line ->
            scope.launch {
                gate.acquire()
                try {
                    if (!voice.isCached(line)) runCatching { voice.clip(line) }
                } finally {
                    gate.release()
                }
            }
        }
    }

    /** Stop now; the running play returns with what was played. */
    fun stopPlayback() {
        out.stop()
    }

    fun drop(e: Effect.DropClips) {
        synchronized(fetches) {
            fetches.keys.filter { it.utteranceId in e.utterances && it != e.keep }.forEach { fetches.remove(it)?.cancel() }
            ready.keys.removeAll { it.utteranceId in e.utterances && it != e.keep }
        }
    }

    fun stop() {
        out.stop()
        playing?.cancel()
        synchronized(fetches) {
            fetches.values.forEach { it.cancel() }
            fetches.clear()
            ready.clear()
        }
    }

    companion object {
        /** Ahead-of-time line fetches at once (below the phrase renderer's 4: a turn's own clip may need the connection). */
        const val PRERENDER_PARALLEL = 2
    }

    private fun fetched(piece: Piece): Boolean = synchronized(fetches) { piece in ready || fetches[piece]?.isCompleted == true }

    private fun fetchAsync(piece: Piece): Deferred<OpenRouterResult<AudioClip>> = synchronized(fetches) {
        fetches.getOrPut(piece) {
            scope.async {
                try {
                    voice.clip(piece.text)
                } catch (c: CancellationException) {
                    throw c
                } catch (x: Exception) {
                    OpenRouterResult.Failed(FailureKind.BadBody)
                }
            }
        }
    }
}
