package com.folio.hermeslive.memo

import com.folio.hermes.telemetry.TelemetrySink
import com.folio.hermeslive.audio.AudioSource
import com.folio.hermeslive.tasks.HeldTasks
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What Hermes's speech-to-text made of one chunk. */
sealed interface MemoText {
    /** The words ("" for no speech). */
    data class Text(val text: String) : MemoText

    /** Not now, for a [reason]: `offline`, `signed_out`, `server_old` (no transcribe route), `no_stt`, a failure kind. */
    data class Hold(val reason: String) : MemoText

    /** The server refused the audio as too large (413): it will never take it. */
    data object TooLarge : MemoText
}

/** One WAV chunk to text through Hermes ([HermesMemoTranscriber] in the app). */
fun interface MemoTranscriber {
    suspend fun transcribe(wav: ByteArray, timeoutMs: Long): MemoText
}

/** The quiet notifications a memo gets. */
interface MemoNotices {
    /** "Memo sent to Hermes" (on submit). */
    fun sent(record: MemoRecord)

    /** "The memo had no speech". */
    fun empty(record: MemoRecord)
}

/** The `JobScheduler` job that wakes the app when the network is back ([MemoJobs] in the app). */
interface MemoScheduler {
    /** A memo is held: make sure the job is scheduled. */
    fun schedule()

    /** Nothing held: drop the job. */
    fun cancel()
}

/**
 * Sends offline memos to Hermes (offline-memo slice). Per memo: each chunk through [transcriber]
 * (its text stored, then its audio deleted, so a retry never transcribes a chunk twice), the
 * joined transcript stored (state `transcribed`), then one task through the shared task API with
 * the key `memo-<id>` ([HeldTasks]: a repeat after a kill returns the same task). No speech:
 * state `empty` and a quiet notice. 413: state `failed`. Anything else is held with a reason and
 * tried again ([flush]: on app start, after each memo, when the network is back, from the job),
 * backing off 1 min → 1 h for errors (not for `offline`, which the network itself retries).
 *
 * A memo whose record says `recording` but is not [recording] in this process was cut off by a
 * kill: its WAV files are repaired from their length and it is sent like any other.
 *
 * Sign-out wipes the records and audio at any time: after every wait the record is read again,
 * and a wiped one is left alone (nothing written back, nothing submitted).
 */
class MemoSender(
    private val store: MemoStore,
    private val held: HeldTasks,
    private val transcriber: () -> MemoTranscriber,
    private val zone: () -> ZoneId,
    private val wall: () -> Long,
    private val telemetry: TelemetrySink,
    private val notices: MemoNotices,
    private val scheduler: MemoScheduler,
    private val recording: (String) -> Boolean = { false },
    private val limits: MemoSettings = MemoSettings(),
) {
    sealed interface Outcome {
        data class Sent(val taskId: String) : Outcome
        data class Held(val reason: String) : Outcome
        data object Empty : Outcome
        data class Failed(val reason: String) : Outcome
        data object Missing : Outcome
    }

    /** What a flush did: [sent] now, [held] still waiting (the earliest backoff in [nextAttemptAt]). */
    data class Flush(val sent: Int, val held: Int, val nextAttemptAt: Long?, val reasons: Set<String> = emptySet())

    /** Try every memo that is due ([force]: also those backing off). One flush at a time. */
    suspend fun flush(force: Boolean = false): Flush = held.exclusive {
        recover()
        var sent = 0
        for (memo in store.all().filter { it.pending }) {
            if (!force && memo.nextAttemptAt > wall()) continue
            if (sendLocked(memo.id) is Outcome.Sent) sent++
        }
        val left = store.all().filter { it.pending }
        if (left.isEmpty()) scheduler.cancel() else scheduler.schedule()
        Flush(sent, left.size, left.map { it.nextAttemptAt }.filter { it > wall() }.minOrNull(), left.mapNotNull { it.heldReason }.toSet())
    }

    /** Send memo [id] now, if it waits. */
    suspend fun send(id: String): Outcome = held.exclusive { sendLocked(id) }

    /** Memos cut off by a kill mid-recording: repair their files, then treat them as recorded (or discard a too-short one). */
    private fun recover() {
        for (memo in store.all().filter { it.state == MemoState.Recording && !recording(it.id) }) {
            val samples = (0 until memo.chunks).sumOf { WavWriter.repair(store.chunk(memo.id, it), AudioSource.SAMPLE_RATE) }
            val durationMs = samples * 1000 / AudioSource.SAMPLE_RATE
            val saved = durationMs >= limits.minMs
            if (saved) store.put(memo.copy(state = MemoState.Recorded, durationMs = durationMs)) else store.delete(memo.id)
            event("memo.end", mapOf("reason" to "recovered", "duration_s" to durationMs / 1000, "chunks" to memo.chunks, "saved" to saved))
        }
    }

    private suspend fun sendLocked(id: String): Outcome {
        var memo = store.get(id) ?: return Outcome.Missing
        if (!memo.pending) return memo.taskId?.let { Outcome.Sent(it) } ?: Outcome.Missing
        val started = wall()
        if (memo.state == MemoState.Recorded) {
            val t = transcriber()
            for (n in memo.parts.size until memo.chunks) {
                val file = store.chunk(id, n)
                val samples = WavWriter.repair(file, AudioSource.SAMPLE_RATE)
                val text = if (samples == 0L) {
                    ""
                } else {
                    when (val r = t.transcribe(file.readBytes(), timeoutFor(samples))) {
                        is MemoText.Text -> r.text.trim()
                        is MemoText.Hold -> return hold(id, r.reason, started)
                        MemoText.TooLarge -> return failed(id, "too_large", started)
                    }
                }
                // Wiped while Hermes listened (sign-out): write nothing back.
                memo = store.get(id) ?: return Outcome.Missing
                memo = memo.copy(parts = memo.parts + text)
                store.put(memo)
                file.delete()
            }
            val transcript = memo.parts.filter { it.isNotBlank() }.joinToString(" ").trim()
            store.deleteAudio(id)
            if (transcript.isEmpty()) {
                memo = memo.copy(state = MemoState.Empty, heldReason = null)
                store.put(memo)
                runCatching { notices.empty(memo) }
                send("empty", memo, started)
                return Outcome.Empty
            }
            memo = memo.copy(state = MemoState.Transcribed, transcript = transcript, heldReason = null)
            store.put(memo)
        }
        val transcribed = memo
        val r = held.send(transcribed.key, label(transcribed)) { HeldTasks.Compiled.Brief(brief(transcribed)) }
        return when (r) {
            is HeldTasks.Result.Sent -> {
                val now = store.get(id) ?: return Outcome.Missing
                val sent = now.copy(state = MemoState.Sent, taskId = r.taskId, heldReason = null)
                store.put(sent)
                runCatching { notices.sent(sent) }
                send("sent", sent, started)
                Outcome.Sent(r.taskId)
            }
            is HeldTasks.Result.Held -> hold(id, r.reason, started)
        }
    }

    private fun hold(id: String, reason: String, started: Long): Outcome {
        val memo = store.get(id) ?: return Outcome.Missing
        val attempts = memo.attempts + 1
        val next = if (reason in NO_BACKOFF) 0L else wall() + backoffMs(attempts)
        val heldMemo = memo.copy(attempts = attempts, heldReason = reason, nextAttemptAt = next)
        store.put(heldMemo)
        send("held", heldMemo, started, reason)
        return Outcome.Held(reason)
    }

    private fun failed(id: String, reason: String, started: Long): Outcome {
        val memo = store.get(id) ?: return Outcome.Missing
        val f = memo.copy(state = MemoState.Failed, heldReason = reason, attempts = memo.attempts + 1)
        store.put(f)
        send("failed", f, started, reason)
        return Outcome.Failed(reason)
    }

    private fun send(outcome: String, memo: MemoRecord, started: Long, reason: String? = null) = event(
        "memo.send",
        buildMap {
            put("outcome", outcome)
            put("attempts", memo.attempts)
            put("ms", wall() - started)
            put("chunks", memo.chunks)
            reason?.let { put("reason", it) }
        },
    )

    private fun event(name: String, attrs: Map<String, Any?>) = runCatching { telemetry.event(name, attrs, false) }

    /** The brief Hermes gets: the memo verbatim (≤ 10 min ≈ 1,500 words, far under any cap). */
    fun brief(memo: MemoRecord): String {
        val at = Instant.ofEpochMilli(memo.createdAt).atZone(zone())
        return "Offline voice memo recorded at ${BRIEF_TIME.format(at)} (${zoneName(at.zone)} time). " +
            "Treat it as the user's request(s):\n\n${memo.transcript.orEmpty()}"
    }

    fun label(memo: MemoRecord): String = "Voice memo · " + LABEL_TIME.format(Instant.ofEpochMilli(memo.createdAt).atZone(zone()))

    companion object {
        /** Retried by the network coming back, the account or the app start, not by a timer. */
        val NO_BACKOFF = setOf("offline", "signed_out", "no_runtime", "not_connected")
        const val BACKOFF_MIN_MS = 60_000L
        const val BACKOFF_MAX_MS = 60 * 60_000L

        fun backoffMs(attempts: Int): Long = (BACKOFF_MIN_MS shl (attempts - 1).coerceIn(0, 6)).coerceAtMost(BACKOFF_MAX_MS)

        /** 15 s plus 1 s for every 4 s of audio. */
        fun timeoutFor(samples: Long): Long = 15_000L + samples * 1000 / AudioSource.SAMPLE_RATE / 4

        private fun zoneName(zone: ZoneId): String = if (zone.id == "Europe/London") "London" else zone.id

        private val BRIEF_TIME = DateTimeFormatter.ofPattern("HH:mm 'on' EEE d MMM yyyy", Locale.UK)
        private val LABEL_TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)
    }
}
