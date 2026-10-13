package com.folio.hermes.cascade

/** Test adapter: C1's mutable `SpeechQueue` API over the production [SpeechQueueState], for `SpeechQueueTest`. */
class SpeechQueue {
    var s = SpeechQueueState()

    var userSpeaking: Boolean
        get() = s.userSpeaking
        set(v) { s = s.copy(userSpeaking = v) }

    var userHolding: Boolean
        get() = s.userHolding
        set(v) { s = s.copy(userHolding = v) }

    val playing: Piece? get() = s.current
    val isEmpty: Boolean get() = s.isEmpty

    fun open(kind: SpeechKind, taskId: String? = null, turnId: String? = null): Long = s.open(kind, taskId, turnId).let { s = it.first; it.second }
    fun add(id: Long, text: String, slot: String? = null): Boolean = s.add(id, text, slot).let { s = it.first; it.second }
    fun close(id: Long) { s = s.close(id) }
    fun drop(id: Long) { s = s.drop(id) }
    fun isDropped(id: Long): Boolean = s.isDropped(id)
    fun next(): Piece? = s.next().let { s = it.first; it.second }
    fun upcoming(): Piece? = s.upcoming()
    fun played(piece: Piece, playedMs: Long, durationMs: Long) { s = s.played(piece, playedMs, durationMs) }
    fun cutIn(): CutIn = s.cutIn().let { s = it.first; it.second }
    fun heard(id: Long): String = s.heard(id)
}
