package com.folio.hermeslive.memo

import com.folio.hermes.cascade.ports.Store
import com.folio.hermes.telemetry.TelemetrySink
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Where a memo is: recorded on the phone, transcribed by Hermes, then sent as a task. */
@Serializable
enum class MemoState {
    /** The mic is (or was, before a kill) writing its chunks. */
    @SerialName("recording") Recording,

    /** Ended; the audio waits on the phone for Hermes to transcribe it. */
    @SerialName("recorded") Recorded,

    /** The transcript is stored and the audio deleted; the task is not started yet. */
    @SerialName("transcribed") Transcribed,

    @SerialName("sent") Sent,

    /** Hermes heard no speech: nothing to send. */
    @SerialName("empty") Empty,

    /** Can never be sent (the server refused the audio as too large). */
    @SerialName("failed") Failed,
}

/** One memo's durable record (`cascade.memo.<id>`). Text and counts only; the audio is in [MemoStore.audioDir]. */
@Serializable
data class MemoRecord(
    val id: String,
    /** Wall clock, epoch ms. */
    val createdAt: Long,
    val state: MemoState = MemoState.Recording,
    /** WAV chunks written (`chunk-000.wav` …), each at most [MemoSettings.chunkMs]. */
    val chunks: Int = 0,
    val durationMs: Long = 0,
    /** Each chunk's transcript so far, in order (a retry never transcribes a chunk twice). */
    val parts: List<String> = emptyList(),
    /** The whole transcript, once every chunk is in. */
    val transcript: String? = null,
    val taskId: String? = null,
    /** Failed send attempts. */
    val attempts: Int = 0,
    val heldReason: String? = null,
    /** A text memo (shared text or Folio's prompt, offline): no audio, nothing to transcribe. */
    val typed: Boolean = false,
    /** Wall clock when it became `sent`, `empty` or `failed` ([MemoRetention] prunes it 7 days on). */
    val doneAt: Long? = null,
    /** The task's label, kept once sent (the words are dropped). */
    val label: String? = null,
) {
    /** Waiting to go to Hermes. */
    val pending: Boolean get() = state == MemoState.Recorded || state == MemoState.Transcribed

    /** The idempotent task key: the same memo never starts two tasks. */
    val key: String get() = "memo-$id"
}

/**
 * Memos in the cascade's durable [Store] (keys `cascade.memo.<id>`, so a Hermes sign-out wipes
 * them with the rest of the account's state) and their audio under [dir] (`cascade/memo/<id>/`,
 * deleted by the same sign-out, `CascadeSignOut.wipe`). Each chunk's audio is deleted as soon as
 * its transcript is stored. Unsent memos have no expiry.
 */
class MemoStore(private val store: () -> Store, val dir: File) {
    private val s: Store get() = store()

    fun get(id: String): MemoRecord? = s.get(PREFIX + id)?.let { runCatching { JSON.decodeFromString(MemoRecord.serializer(), it) }.getOrNull() }

    fun put(record: MemoRecord) = s.put(PREFIX + record.id, JSON.encodeToString(MemoRecord.serializer(), record))

    /** Every memo, oldest first. */
    fun all(): List<MemoRecord> = s.keys(PREFIX).mapNotNull { get(it.removePrefix(PREFIX)) }.sortedWith(compareBy({ it.createdAt }, { it.id }))

    fun audioDir(id: String): File = File(dir, id)

    fun chunk(id: String, n: Int): File = File(audioDir(id), "chunk-%03d.wav".format(n))

    fun deleteAudio(id: String) {
        audioDir(id).deleteRecursively()
    }

    /** Forget a memo: its record and its audio. */
    fun delete(id: String) {
        s.put(PREFIX + id, null)
        deleteAudio(id)
    }

    companion object {
        const val PREFIX = "cascade.memo."
        val JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }
    }
}

/**
 * Finished memos (`sent`, `empty`, `failed`) are deleted [DAYS] after they finished, at start and
 * after each flush (as `MeetingRetention` does for meetings). Memos still waiting are never pruned.
 */
object MemoRetention {
    const val DAYS = 7
    const val DAY_MS = 86_400_000L

    fun prune(store: MemoStore, nowWall: Long, telemetry: TelemetrySink = TelemetrySink.None, memos: List<MemoRecord> = store.all()): Int {
        val cutoff = nowWall - DAYS * DAY_MS
        val old = memos.filter { m -> m.state in DONE && (m.doneAt ?: m.createdAt) < cutoff }
        old.forEach { store.delete(it.id) }
        if (old.isNotEmpty()) runCatching { telemetry.event("memo.deleted", mapOf("count" to old.size, "reason" to "retention"), false) }
        return old.size
    }

    private val DONE = setOf(MemoState.Sent, MemoState.Empty, MemoState.Failed)
}

/** A memo's limits. */
data class MemoSettings(
    /** One WAV file per this much audio (60 s ≈ 1.9 MB, far under the server's 25 MB transcribe cap). */
    val chunkMs: Long = 60_000L,
    /** A memo stops here and says so (10 min ≈ 1,500 words: sent verbatim, no summary). */
    val capMs: Long = 10 * 60_000L,
    /** Shorter than this is discarded. */
    val minMs: Long = 1_000L,
    /** No voice ready within this: an earcon and a haptic instead. */
    val ttsReadyMs: Long = 1_500L,
    /** The start line is cut here; the mic opens after it either way. */
    val lineCapMs: Long = 4_000L,
)
