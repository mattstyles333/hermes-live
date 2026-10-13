package com.folio.hermes.cascade

/** What an utterance is, in priority order: a reply to the user's turn first, then notices, then task results. */
enum class SpeechKind { Reply, Notice, Result }

/** One TTS request's worth of speech. [slot] is set for a stock line. */
data class Piece(val utteranceId: Long, val index: Int, val text: String, val slot: String?)

/** Where a cut-in left things: the piece that was playing, and the task whose result was interrupted. */
data class CutIn(val playing: Piece?, val interruptedResultTask: String?)

/** One utterance in the queue (immutable). */
data class Utterance(
    val id: Long,
    val kind: SpeechKind,
    val taskId: String?,
    val turnId: String?,
    val pieces: List<Piece> = emptyList(),
    val added: Int = 0,
    val open: Boolean = true,
    val dropped: Boolean = false,
    val started: Boolean = false,
    val heard: String = "",
)

/**
 * What Bill says next, as a pure value: every operation returns a new state (owned by the
 * `CascadeActor`; R-ARC-1).
 *
 * - Utterances are spoken whole and in order of [SpeechKind], first come first served within a
 *   kind. An utterance still being written (open, nothing ready) holds its place: nothing behind it jumps ahead.
 * - Nothing starts while Bill is talking ([current]) or the user is speaking ([userSpeaking]);
 *   while they hold words not yet acted on ([userHolding]) only a reply may start. The user's
 *   speech always wins, so a finished result waits for them to finish.
 * - [cutIn] (the user spoke over Bill for 250 ms or more) drops every queued reply and notice
 *   and the rest of the utterance playing; queued results stay for later.
 * - [played] records what was actually heard: a piece cut short counts only its first
 *   `played_ms / duration` of words, so `recent` holds only what the user heard.
 */
data class SpeechQueueState(
    val queue: List<Utterance> = emptyList(),
    /** Retired utterances, oldest first (at most [KEEP_DONE]). */
    val done: List<Utterance> = emptyList(),
    val nextId: Long = 1L,
    /** The piece fetching or playing now (Bill holds the floor). */
    val current: Piece? = null,
    /** The user is speaking: nothing new starts. */
    val userSpeaking: Boolean = false,
    /** The user paused mid-thought (words held, not acted on): only a reply (a backchannel) may start. */
    val userHolding: Boolean = false,
) {
    /** True if nothing is queued or playing. */
    val isEmpty: Boolean get() = current == null && queue.isEmpty()

    /** Start an utterance; add pieces with [add], end it with [close]. */
    fun open(kind: SpeechKind, taskId: String? = null, turnId: String? = null): Pair<SpeechQueueState, Long> {
        val u = Utterance(nextId, kind, taskId, turnId)
        return copy(queue = queue + u, nextId = nextId + 1) to u.id
    }

    /** Append a piece to utterance [id]; false if it was dropped or closed. */
    fun add(id: Long, text: String, slot: String? = null): Pair<SpeechQueueState, Boolean> {
        val u = queue.firstOrNull { it.id == id } ?: return this to false
        if (!u.open || u.dropped || text.isBlank()) return this to false
        val next = u.copy(pieces = u.pieces + Piece(id, u.added, text.trim(), slot), added = u.added + 1)
        return replace(next) to true
    }

    fun close(id: Long): SpeechQueueState {
        val u = queue.firstOrNull { it.id == id } ?: return this
        return replace(u.copy(open = false)).retireIfEmpty(id)
    }

    /** Drop what is left of utterance [id]. */
    fun drop(id: Long): SpeechQueueState {
        val u = queue.firstOrNull { it.id == id } ?: return this
        return replace(u.copy(dropped = true, pieces = emptyList(), open = false)).retireIfEmpty(id)
    }

    fun find(id: Long): Utterance? = queue.firstOrNull { it.id == id } ?: done.lastOrNull { it.id == id }

    /** True if utterance [id] was dropped (by [drop] or a cut-in). */
    fun isDropped(id: Long): Boolean = find(id)?.dropped == true

    /** The next piece to play, now; null if Bill is talking, the user is speaking or holding, or nothing is ready. Marks it current. */
    fun next(): Pair<SpeechQueueState, Piece?> {
        if (current != null || userSpeaking) return this to null
        val u = head() ?: return this to null
        if (userHolding && u.kind != SpeechKind.Reply) return this to null
        val piece = u.pieces.firstOrNull() ?: return this to null
        return replace(u.copy(pieces = u.pieces.drop(1), started = true)).copy(current = piece) to piece
    }

    /** The piece that would play after the current one (for prefetch); ignores the user gate. */
    fun upcoming(): Piece? = head()?.pieces?.firstOrNull()

    /** [piece] stopped after [playedMs] of [durationMs]. */
    fun played(piece: Piece, playedMs: Long, durationMs: Long): SpeechQueueState {
        var s = if (current == piece) copy(current = null) else this
        val u = s.find(piece.utteranceId) ?: return s
        val text = heardPart(piece.text, playedMs, durationMs)
        if (text.isNotEmpty()) s = s.replace(u.copy(heard = if (u.heard.isEmpty()) text else u.heard + " " + text))
        return s.retireIfEmpty(u.id)
    }

    /** The user cut in: drop queued replies and notices, and the rest of what is playing. */
    fun cutIn(): Pair<SpeechQueueState, CutIn> {
        val playing = current
        val playingId = playing?.let { p -> queue.firstOrNull { it.id == p.utteranceId }?.id }
        var interruptedTask: String? = null
        val marked = queue.map { u ->
            val isPlaying = u.id == playingId
            if (u.kind != SpeechKind.Result || isPlaying) {
                if (isPlaying && u.kind == SpeechKind.Result) interruptedTask = u.taskId
                u.copy(dropped = true, pieces = emptyList(), open = false)
            } else {
                u
            }
        }
        var s = copy(queue = marked)
        marked.filter { it.dropped && it.id != playingId }.forEach { s = s.retire(it.id) }
        return s to CutIn(playing, interruptedTask)
    }

    /** What the user heard of utterance [id] so far. */
    fun heard(id: Long): String = find(id)?.heard.orEmpty()

    fun kindOf(id: Long): SpeechKind? = find(id)?.kind
    fun taskOf(id: Long): String? = find(id)?.taskId
    fun turnOf(id: Long): String? = find(id)?.turnId

    /** Tasks whose result is queued and not yet started. */
    fun queuedResults(): List<String> = queue.filter { it.kind == SpeechKind.Result && !it.started && !it.dropped }.mapNotNull { it.taskId }

    private fun head(): Utterance? {
        // A started utterance finishes before anything else; an open one with nothing ready holds its place.
        queue.firstOrNull { it.started && !it.dropped }?.let { return it }
        for (kind in SpeechKind.entries) {
            queue.firstOrNull { it.kind == kind && !it.dropped }?.let { return it }
        }
        return null
    }

    private fun replace(u: Utterance): SpeechQueueState =
        if (queue.any { it.id == u.id }) copy(queue = queue.map { if (it.id == u.id) u else it })
        else copy(done = done.map { if (it.id == u.id) u else it })

    private fun retireIfEmpty(id: Long): SpeechQueueState {
        val u = queue.firstOrNull { it.id == id } ?: return this
        return if (!u.open && u.pieces.isEmpty() && current?.utteranceId != u.id) retire(id) else this
    }

    private fun retire(id: Long): SpeechQueueState {
        val u = queue.firstOrNull { it.id == id } ?: return this
        return copy(queue = queue.filter { it.id != id }, done = (done.filter { it.id != id } + u).takeLast(KEEP_DONE))
    }

    companion object {
        const val KEEP_DONE = 64

        /** The first played/duration share of [text]'s words. */
        fun heardPart(text: String, playedMs: Long, durationMs: Long): String {
            if (durationMs <= 0 || playedMs >= durationMs - 40) return text
            if (playedMs <= 0) return ""
            val words = text.split(' ').filter { it.isNotEmpty() }
            val n = (words.size * playedMs / durationMs).toInt()
            return words.take(n).joinToString(" ").let { if (n in 1 until words.size) "$it…" else it }
        }
    }
}
