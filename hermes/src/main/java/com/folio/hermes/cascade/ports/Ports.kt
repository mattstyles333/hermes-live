package com.folio.hermes.cascade.ports

import com.folio.hermes.BusyMode
import com.folio.hermes.TurnUpdate
import com.folio.hermes.cascade.AudioClip
import com.folio.hermes.cascade.Transcript
import com.folio.hermes.cascade.Word
import com.folio.hermes.openrouter.JevAnswer
import com.folio.hermes.openrouter.JevQuestion
import com.folio.hermes.openrouter.OpenRouterResult
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

/*
 * The cascade's ports (R-ARC-5). Interfaces in `:hermes` (pure Kotlin, R-ARC-6); `:live`
 * implements the Android ones (S-C: SpeechToText, AudioOut; S-E/S-F: Store, Network), tests use
 * in-memory fakes. Frozen per R-ARC-4: additive changes only.
 */

/** Time. [nowMs] is monotonic (pauses, latencies); [wallMs] is wall time (records, memo sessions). */
interface Clock {
    fun nowMs(): Long
    fun wallMs(): Long

    object System : Clock {
        override fun nowMs(): Long = java.lang.System.nanoTime() / 1_000_000
        override fun wallMs(): Long = java.lang.System.currentTimeMillis()
    }
}

/** Connectivity: emits the current state first, then every change. */
interface Network {
    val online: Flow<Boolean>
}

/**
 * Durable storage (R-DUR, R-CONV): a small key-value map plus append-only logs. Writes are
 * synchronous and durable when they return (the actor runs every `Persist` effect before any
 * network effect of the same step; R-DUR-1). `:live` backs it with app-private files.
 */
interface Store {
    fun get(key: String): String?

    /** Write [value]; null removes the key. */
    fun put(key: String, value: String?)

    fun keys(prefix: String = ""): List<String>

    fun append(log: String, line: String)

    fun read(log: String): List<String>

    /** Keep only the last [keep] lines of [log] (0 clears it). */
    fun trim(log: String, keep: Int)
}

/** Speech-to-text events from the fast streaming pass, in order (R-STT-1). */
sealed interface SttEvent {
    /** VAD: the user started speaking. */
    data object SpeechStarted : SttEvent

    /** VAD: the user stopped speaking. */
    data object SpeechEnded : SttEvent

    data class Partial(val text: String) : SttEvent

    /** A finished utterance from the fast pass, with per-word confidence when the engine has it. */
    data class Final(val text: String, val words: List<Word> = emptyList()) : SttEvent

    /** VAD while Bill plays: the user has spoken for [continuousMs] (R-SPK-4). */
    data class UserSpeech(val continuousMs: Long) : SttEvent

    /** Hold-to-talk pressed or released (R-STT-8). */
    data class HoldToTalk(val pressed: Boolean) : SttEvent
}

/**
 * Speech-to-text: the fast stream ([events]) plus the accurate final-pass hook (R-STT-2). The
 * app feeds both from one `AudioRecord`; utterance audio stays in memory until both passes are
 * done (R-STT-3).
 */
interface SpeechToText {
    val events: Flow<SttEvent>

    /**
     * The accurate pass over the audio of committed turn [turnId] (everything since the previous
     * commit). Null when the engine has no accurate model or the audio is gone. The caller waits
     * at most `accuratePassWaitMs`.
     */
    suspend fun accurate(turnId: String): Transcript?
}

/** Plays whole PCM clips. `:live` implements it with an `AudioTrack` on `USAGE_VOICE_COMMUNICATION` (R-SPK-5). */
interface AudioOut {
    /** Play [clip] to the end, or until [stop]; returns the milliseconds actually played. */
    suspend fun play(clip: AudioClip): Long

    /** Stop at once (the user cut in). The running [play] returns. */
    fun stop()
}

/**
 * Hermes for voice tasks: one session per task (R-TASK-1).
 *
 * - [open] binds task [taskId] to a session: a new one (`session.create`, titled with [label]),
 *   or [storedSessionId] resumed (`session.resume`, R-DUR-3). Idempotent; [submit] opens on demand.
 * - [submit] sends one turn and streams it to completion ([queued] forces a queue on a busy
 *   session; [interruptFirst] stops a running turn first).
 * - [interrupt] is `session.interrupt`; false if the task has no session yet.
 * - [busyMode] reads the server's `busy_input_mode` (`config.get`, never set).
 * - [sessions] emits (task id, stored session id) whenever Hermes mints one (persist it).
 * - [connected] is the gateway's state.
 * - [create] makes task [taskId] a new session now, with no prompt (`session.create`), and returns
 *   its stored id, which the actor stores before the first submit (R-DUR-2: a restart can then
 *   ask Hermes whether the prompt arrived). It replaces any session the task had. Null: this port
 *   cannot, and the first [submit] creates the session as before (additive; default null).
 */
interface HermesPort {
    fun open(taskId: String, label: String, storedSessionId: String?)

    suspend fun create(taskId: String, label: String): String? = null

    fun submit(taskId: String, text: String, voiceContext: String, queued: Boolean, interruptFirst: Boolean): Flow<TurnUpdate>

    suspend fun interrupt(taskId: String): Boolean

    suspend fun busyMode(): BusyMode?

    val sessions: Flow<Pair<String, String>>

    val connected: Flow<Boolean>
}

/**
 * OpenRouter for the cascade: Jev (System One), the writer and Bill's voice (R-ARC-5). No key
 * means every call fails with `no_key` before any request (R-Q-1).
 */
interface OpenRouterPort {
    suspend fun jev(state: JsonObject, questions: Map<String, JevQuestion>, timeoutMs: Long): OpenRouterResult<Map<String, JevAnswer>>

    /** The writer, streamed as speakable sentences. Throws `OpenRouterException` on failure. */
    fun sentences(system: String, prompt: String): Flow<String>

    /** One whole writer answer (agent briefs, plans). */
    suspend fun write(system: String, prompt: String): OpenRouterResult<String>

    /** [text] in Bill's voice: from the TTS cache, or one request (then cached). */
    suspend fun clip(text: String): OpenRouterResult<AudioClip>

    /** True if [text] is cached (no request). */
    fun isCached(text: String): Boolean
}
