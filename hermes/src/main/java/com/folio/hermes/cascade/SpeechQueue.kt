package com.folio.hermes.cascade

/** What an utterance is, in priority order: a reply to the user's turn first, then notices, then task results. */
enum class SpeechKind { Reply, Notice, Result }

/** One TTS request's worth of speech. [slot] is set for a stock line. */
data class Piece(val utteranceId: Long, val index: Int, val text: String, val slot: String?)

/** Where a cut-in left things: the piece that was playing, and the task whose result was interrupted. */
data class CutIn(val playing: Piece?, val interruptedResultTask: String?)

/**
 * What Bill says next. Pure and single-threaded (guarded, but call it from one thread).
 *
 * - Utterances are spoken whole and in order of [SpeechKind], first come first served within a
 *   kind. An utterance still being written (open, nothing ready) holds its place: nothing behind it jumps ahead.
 * - Nothing starts while Bill is talking ([playing]) or the user is speaking ([userSpeaking]);
 *   while they hold words not yet acted on ([userHolding]) only a reply may start. The user's
 *   speech always wins, so a finished result waits for them to finish.
 * - [cutIn] (the user spoke over Bill for 250 ms or more) drops every queued reply and notice
 *   and the rest of the utterance playing; queued results stay for later.
 * - [played] records what was actually heard: a piece cut short counts only its first
 *   `played_ms / duration` of words, so `recent` holds only what the user heard.
 */
class SpeechQueue {
    private class Utterance(val id: Long, val kind: SpeechKind, val taskId: String?, val turnId: String?) {
        val pieces = ArrayDeque<Piece>()
        var added = 0
        var open = true
        var dropped = false
        var started = false
        val heard = StringBuilder()
    }

    private val lock = Any()
    private val queue = mutableListOf<Utterance>()
    private val done = LinkedHashMap<Long, Utterance>()
    private var nextId = 1L
    private var current: Piece? = null

    /** The user is speaking: nothing new starts. */
    @Volatile var userSpeaking: Boolean = false

    /** The user paused mid-thought (words held, not acted on): only a reply (a backchannel) may start. */
    @Volatile var userHolding: Boolean = false

    val playing: Piece? get() = synchronized(lock) { current }

    /** Start an utterance; add pieces with [add], end it with [close]. */
    fun open(kind: SpeechKind, taskId: String? = null, turnId: String? = null): Long = synchronized(lock) {
        val u = Utterance(nextId++, kind, taskId, turnId)
        queue += u
        u.id
    }

    /** Append a piece to utterance [id]; false if it was dropped or closed. */
    fun add(id: Long, text: String, slot: String? = null): Boolean = synchronized(lock) {
        val u = queue.firstOrNull { it.id == id } ?: return false
        if (!u.open || u.dropped || text.isBlank()) return false
        u.pieces.addLast(Piece(id, u.added++, text.trim(), slot))
        true
    }

    fun close(id: Long): Unit = synchronized(lock) {
        queue.firstOrNull { it.id == id }?.let {
            it.open = false
            retireIfEmpty(it)
        }
    }

    /** Drop what is left of utterance [id]. */
    fun drop(id: Long): Unit = synchronized(lock) {
        queue.firstOrNull { it.id == id }?.let {
            it.dropped = true
            it.pieces.clear()
            it.open = false
            retireIfEmpty(it)
        }
    }

    /** True if utterance [id] was dropped (by [drop] or a cut-in). */
    fun isDropped(id: Long): Boolean = synchronized(lock) {
        (queue.firstOrNull { it.id == id } ?: done[id])?.dropped == true
    }

    /** The next piece to play, now; null if Bill is talking, the user is speaking or holding, or nothing is ready. Marks it playing. */
    fun next(): Piece? = synchronized(lock) {
        if (current != null || userSpeaking) return null
        val u = head() ?: return null
        if (userHolding && u.kind != SpeechKind.Reply) return null
        val piece = u.pieces.removeFirstOrNull()?.also { u.started = true } ?: return null
        current = piece
        piece
    }

    /** The piece that would play after the current one (for prefetch); ignores the user gate. */
    fun upcoming(): Piece? = synchronized(lock) { head()?.pieces?.firstOrNull() }

    /** True if nothing is queued or playing. */
    val isEmpty: Boolean get() = synchronized(lock) { current == null && queue.isEmpty() }

    /** [piece] stopped after [playedMs] of [durationMs]. */
    fun played(piece: Piece, playedMs: Long, durationMs: Long): Unit = synchronized(lock) {
        if (current == piece) current = null
        val u = queue.firstOrNull { it.id == piece.utteranceId } ?: done[piece.utteranceId] ?: return
        val text = heardPart(piece.text, playedMs, durationMs)
        if (text.isNotEmpty()) {
            if (u.heard.isNotEmpty()) u.heard.append(' ')
            u.heard.append(text)
        }
        retireIfEmpty(u)
    }

    /** The user cut in: drop queued replies and notices, and the rest of what is playing. */
    fun cutIn(): CutIn = synchronized(lock) {
        val playing = current
        val playingUtterance = playing?.let { p -> queue.firstOrNull { it.id == p.utteranceId } }
        var interruptedTask: String? = null
        for (u in queue) {
            val isPlaying = u === playingUtterance
            if (u.kind != SpeechKind.Result || isPlaying) {
                if (isPlaying && u.kind == SpeechKind.Result) interruptedTask = u.taskId
                u.dropped = true
                u.pieces.clear()
                u.open = false
            }
        }
        queue.filter { it.dropped && it !== playingUtterance }.forEach(::retire)
        CutIn(playing, interruptedTask)
    }

    /** What the user heard of utterance [id] so far. */
    fun heard(id: Long): String = synchronized(lock) {
        (queue.firstOrNull { it.id == id } ?: done[id])?.heard?.toString().orEmpty()
    }

    fun kindOf(id: Long): SpeechKind? = synchronized(lock) { (queue.firstOrNull { it.id == id } ?: done[id])?.kind }
    fun taskOf(id: Long): String? = synchronized(lock) { (queue.firstOrNull { it.id == id } ?: done[id])?.taskId }
    fun turnOf(id: Long): String? = synchronized(lock) { (queue.firstOrNull { it.id == id } ?: done[id])?.turnId }

    /** Tasks whose result is queued and not yet started. */
    fun queuedResults(): List<String> = synchronized(lock) {
        queue.filter { it.kind == SpeechKind.Result && !it.started && !it.dropped }.mapNotNull { it.taskId }
    }

    private fun head(): Utterance? {
        // A started utterance finishes before anything else; an open one with nothing ready holds its place.
        queue.firstOrNull { it.started && !it.dropped }?.let { return it }
        for (kind in SpeechKind.entries) {
            queue.firstOrNull { it.kind == kind && !it.dropped }?.let { return it }
        }
        return null
    }

    private fun retireIfEmpty(u: Utterance) {
        if (!u.open && u.pieces.isEmpty() && current?.utteranceId != u.id) retire(u)
    }

    private fun retire(u: Utterance) {
        queue.remove(u)
        done[u.id] = u
        while (done.size > KEEP_DONE) done.remove(done.keys.first())
    }

    private companion object {
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
