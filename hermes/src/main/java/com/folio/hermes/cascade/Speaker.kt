package com.folio.hermes.cascade

import com.folio.hermes.openrouter.OpenRouterResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Plays whole PCM clips. `:live` implements it with an `AudioTrack` on `USAGE_VOICE_COMMUNICATION`. */
interface AudioSink {
    /** Play [clip] to the end, or until [stop]; returns the milliseconds actually played. */
    suspend fun play(clip: AudioClip): Long

    /** Stop at once (the user cut in). The running [play] returns. */
    fun stop()
}

/** What the [Speaker] is doing, for the UI and the decision log. */
sealed interface SpeakerEvent {
    data class Started(val piece: Piece, val atMs: Long) : SpeakerEvent
    data class Finished(val piece: Piece, val playedMs: Long, val durationMs: Long) : SpeakerEvent

    /** No clip for [piece] (TTS failed after its retry); it was skipped. */
    data class Skipped(val piece: Piece) : SpeakerEvent
    data object Idle : SpeakerEvent
}

/**
 * Drives the [SpeechQueue] into the [AudioSink], one whole clip per piece, from [voice] (cache
 * first, so a stock line plays with no request). One request is prefetched ahead of playback.
 * Call [wake] whenever the queue or its gate changes; [cutIn] stops Bill at once.
 */
class Speaker(
    private val scope: CoroutineScope,
    private val queue: SpeechQueue,
    private val voice: BillVoice,
    private val sink: AudioSink,
    private val onEvent: (SpeakerEvent) -> Unit = {},
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    /** While offline, a piece that is not cached is skipped at once instead of waiting out timeouts. */
    private val offline: () -> Boolean = { false },
) {
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val fetches = LinkedHashMap<Piece, Deferred<OpenRouterResult<AudioClip>>>()
    private var loop: Job? = null

    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch { run() }
        wake()
    }

    fun stop() {
        loop?.cancel()
        loop = null
        sink.stop()
        synchronized(fetches) {
            fetches.values.forEach { it.cancel() }
            fetches.clear()
        }
    }

    fun wake() {
        signal.trySend(Unit)
    }

    /** The user spoke over Bill: stop now and drop what the queue drops. */
    fun cutIn(): CutIn {
        val cut = queue.cutIn()
        sink.stop()
        synchronized(fetches) {
            fetches.keys.filter { queue.isDropped(it.utteranceId) && it != cut.playing }.forEach { fetches.remove(it)?.cancel() }
        }
        wake()
        return cut
    }

    private suspend fun run() {
        while (true) {
            val piece = queue.next()
            if (piece == null) {
                onEvent(SpeakerEvent.Idle)
                signal.receive()
                continue
            }
            if (offline() && !voice.isCached(piece.text) && !hasFetch(piece)) {
                queue.played(piece, 0, 1)
                onEvent(SpeakerEvent.Skipped(piece))
                continue
            }
            val clip = fetch(piece).await()
            synchronized(fetches) { fetches.remove(piece) }
            prefetch()
            if (clip !is OpenRouterResult.Ok || queue.isDropped(piece.utteranceId)) {
                queue.played(piece, 0, 1)
                if (clip !is OpenRouterResult.Ok) onEvent(SpeakerEvent.Skipped(piece))
                continue
            }
            onEvent(SpeakerEvent.Started(piece, now()))
            val played = sink.play(clip.value)
            queue.played(piece, played, clip.value.durationMs)
            onEvent(SpeakerEvent.Finished(piece, played, clip.value.durationMs))
        }
    }

    private fun hasFetch(piece: Piece): Boolean = synchronized(fetches) { fetches[piece]?.isCompleted == true }

    private fun prefetch() {
        val next = queue.upcoming() ?: return
        if (offline() && !voice.isCached(next.text)) return
        fetch(next)
    }

    private fun fetch(piece: Piece): Deferred<OpenRouterResult<AudioClip>> = synchronized(fetches) {
        fetches.getOrPut(piece) {
            scope.async {
                try {
                    voice.clip(piece.text)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    OpenRouterResult.Failed(com.folio.hermes.openrouter.FailureKind.BadBody)
                }
            }
        }
    }
}
