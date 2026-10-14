package com.folio.hermeslive.meeting

import com.folio.hermes.cascade.Transcript
import com.folio.hermes.cascade.TranscriptPass
import com.folio.hermes.cascade.ports.Clock
import com.folio.hermes.cascade.ports.SttEvent
import com.folio.hermes.telemetry.TelemetrySink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** One meeting's speech-to-text: the fast stream, the accurate pass over what finished since the last call, and the mic. */
interface MeetingSpeech {
    val events: Flow<SttEvent>

    /** The accurate text of every segment finished since the last call, with how many segments it covers. Null: none held. */
    suspend fun accurate(): AccurateBatch?

    /** Open the mic and start capture. Throws if the mic can't open. */
    fun start()

    /** Stop capture (the mic closes; audio held for the accurate pass is dropped: [MeetingSession] flushes first). */
    fun stop()

    /** Stop and free the models (blocks up to a running decode: off the main thread). */
    fun release()

    /** Input level, dBFS (the orb). */
    val levelDb: StateFlow<Double>
}

data class AccurateBatch(val transcript: Transcript, val segments: Int)

/** The cleanup for one stretch of meeting speech (text only). Null: keep the heard text. */
fun interface SegmentCleaner {
    suspend fun clean(heard: Transcript, other: String): String?
}

/** What a wake-phrase command asks for, acknowledged with a haptic (never speech). */
enum class Ack { Armed, Note, Task, Mark }

/** The meeting as the screen shows it. [lines]: the last few, oldest first. */
data class MeetingLive(
    val meta: MeetingMeta,
    val lines: List<LiveLine> = emptyList(),
    val partial: String? = null,
    val segments: Int = 0,
    val marks: Int = 0,
    /** Set to the newest marker's time for a moment's highlight. */
    val lastMarkAt: Long? = null,
)

/** One rolling-transcript line: a segment, or a marker. */
data class LiveLine(val at: Long, val text: String, val kind: MarkKind? = null)

/**
 * One meeting from start to end (hub design §2, §3): Bill stays silent; each finished segment is
 * appended to the store as the fast pass heard it, at once (a kill loses at most the segment
 * being spoken), then improved in place:
 *
 * - **Accurate pass on quiet.** [QUIET_MS] after speech ends (or [MAX_PENDING] segments into
 *   continuous speech) the accurate pass is taken over the segments since the last one and stored
 *   as one revision of that range. If its segment count does not match ours (a segment's `Final`
 *   raced its hand-over), the fast text stays: words are never guessed into the wrong place.
 * - **Cleanup** (the existing setting): an accepted cleanup is one more revision.
 * - **Wake phrase** ([WakePhrase]) on each segment's best text: a note becomes a marker, a task
 *   goes to [onTask] (held in the outbox until Hermes takes it), "Bill" alone arms the next one.
 * - **Before the mic stops** (pause, end, a one-sentence capture) the accurate pass is flushed,
 *   because stopping capture drops the audio it holds.
 *
 * Memory: only the segments since the last flush and the last [LIVE_LINES] lines; the transcript
 * lives in the store.
 */
class MeetingSession(
    private val log: MeetingLog,
    private val speech: MeetingSpeech,
    private val clock: Clock,
    private val scope: CoroutineScope,
    initial: MeetingMeta,
    private val wakeWord: () -> Boolean = { true },
    private val cleaner: () -> SegmentCleaner? = { null },
    private val onTask: suspend (request: String, at: Long, seg: Int) -> Unit = { _, _, _ -> },
    private val ack: (Ack) -> Unit = {},
    private val telemetry: TelemetrySink = TelemetrySink.None,
) {
    private data class Pending(val n: Int, val at: Long, val text: String)

    private val lock = Mutex()

    /** One flush at a time: each takes the accurate pass for exactly the segments it took. */
    private val flushing = Mutex()
    private val _live: MutableStateFlow<MeetingLive>
    val live: StateFlow<MeetingLive>

    private var meta = initial
    private var nextSeg: Int
    private val pending = ArrayList<Pending>()
    private val recent = ArrayDeque<Pending>()
    private var armedUntil: Long? = null
    private var capture: CompletableDeferred<String?>? = null
    private val captured = ArrayList<String>()
    private var flushJob: Job? = null
    private var collector: Job? = null
    private val cleanups = Channel<Triple<IntRange, Transcript, String>>(Channel.UNLIMITED)
    private var notes = 0
    private var tasks = 0

    val id: String get() = meta.id
    val current: MeetingMeta get() = meta

    init {
        val t = log.transcript(initial.id)
        nextSeg = t.lastSegment
        notes = t.markers.count { it.kind == MarkKind.Note }
        tasks = t.markers.count { it.kind == MarkKind.Task }
        val lines = (t.segments.map { LiveLine(it.at, it.text) } + t.markers.map { LiveLine(it.at, liveText(it.kind, it.text), it.kind) })
            .sortedBy { it.at }.takeLast(LIVE_LINES)
        t.segments.takeLast(RECENT).forEach { recent.addLast(Pending(it.n, it.at, it.text)) }
        _live = MutableStateFlow(MeetingLive(meta, lines, segments = t.segments.size, marks = t.markers.size))
        live = _live.asStateFlow()
        scope.launch { for ((range, heard, other) in cleanups) clean(range, heard, other) }
    }

    /** Listen to the speech events (once). */
    fun attach() {
        if (collector != null) return
        collector = scope.launch { speech.events.collect { on(it) } }
    }

    /** Store the new meeting (the clock starts now) and record. [entry]: where it was started from (telemetry). */
    suspend fun begin(title: String, entry: String = "app") {
        lock.withLock {
            setMeta(meta.copy(title = title.trim().take(MAX_TITLE), startedAt = clock.wallMs(), phase = MeetingPhase.Recording))
            log.create(meta)
            event("meeting.started", mapOf("entry" to entry, "titled" to meta.title.isNotEmpty()))
        }
        record()
    }

    /** Open the mic and record (a new meeting, or Resume). */
    suspend fun record() = lock.withLock {
        attach()
        if (meta.phase == MeetingPhase.Paused) {
            val pausedAt = meta.pausedAt ?: clock.wallMs()
            setMeta(meta.copy(phase = MeetingPhase.Recording, pausedMs = meta.pausedMs + (clock.wallMs() - pausedAt).coerceAtLeast(0), pausedAt = null))
            log.append(meta.id, LogLine.Gap(clock.wallMs(), "resume"))
            event("meeting.resumed", emptyMap())
        }
        speech.start()
    }

    /** After a kill: the time since the last thing written counts as paused, then record again. */
    suspend fun resumeAfterKill() {
        lock.withLock {
            val t = log.transcript(meta.id)
            val last = listOfNotNull(t.segments.maxOfOrNull { it.at }, t.markers.maxOfOrNull { it.at }, t.gaps.maxOfOrNull { it.at }, meta.pausedAt, meta.startedAt).max()
            log.append(meta.id, LogLine.Gap(clock.wallMs(), "resume_after_kill"))
            setMeta(meta.copy(phase = MeetingPhase.Paused, pausedAt = meta.pausedAt ?: last))
        }
        record()
    }

    suspend fun pause() {
        flush()
        lock.withLock {
            if (meta.phase != MeetingPhase.Recording) return
            speech.stop()
            setMeta(meta.copy(phase = MeetingPhase.Paused, pausedAt = clock.wallMs()))
            log.append(meta.id, LogLine.Gap(clock.wallMs(), "pause"))
            _live.value = _live.value.copy(partial = null)
            event("meeting.paused", emptyMap())
        }
    }

    /** End the meeting: flush, mic off, the record says Ended (the mic is free again). */
    suspend fun end(): MeetingMeta {
        if (meta.phase == MeetingPhase.Recording) flush()
        return lock.withLock {
            speech.stop()
            val now = clock.wallMs()
            val ended = meta.copy(
                phase = MeetingPhase.Ended,
                endedAt = now,
                pausedMs = meta.pausedMs + (meta.pausedAt?.let { now - it } ?: 0L).coerceAtLeast(0),
                pausedAt = null,
            )
            setMeta(ended)
            log.clearActive(meta.id)
            _live.value = _live.value.copy(partial = null)
            event(
                "meeting.ended",
                mapOf("duration_s" to ended.elapsedMs(now) / 1000, "segments" to _live.value.segments, "marks" to _live.value.marks, "notes" to notes, "tasks" to tasks),
            )
            ended
        }
    }

    /** The Mark button: the moment before the press matters ([MeetingBrief.MARK_WINDOW_MS]). */
    suspend fun mark() = lock.withLock {
        val now = clock.wallMs()
        val window = recent.filter { it.at >= now - MeetingBrief.MARK_WINDOW_MS }
        val from = window.firstOrNull()?.n ?: recent.lastOrNull()?.n
        val to = recent.lastOrNull()?.n
        addMarker(LogLine.Mark(now, MarkKind.Mark, from = from, to = to))
        ack(Ack.Mark)
        event("meeting.mark", mapOf("kind" to "mark"))
    }

    /**
     * One spoken sentence for the title or "Anything to add?": the mic opens, the first finished
     * utterance (its accurate text when ready) is returned, and the mic closes. Not stored as
     * meeting speech. Null: nothing said within [timeoutMs].
     */
    suspend fun captureOne(timeoutMs: Long = CAPTURE_TIMEOUT_MS): String? {
        attach()
        val result = CompletableDeferred<String?>()
        lock.withLock {
            capture = result
            captured.clear()
            speech.start()
        }
        val text = withTimeoutOrNull(timeoutMs) { result.await() }
        lock.withLock {
            capture = null
            flushJob?.cancel()
            speech.stop()
        }
        return text?.trim()?.takeIf { it.isNotEmpty() } ?: captured.joinToString(" ").trim().takeIf { it.isNotEmpty() }
    }

    /** Stop listening for good (the meeting is over and sent or saved). */
    fun close() {
        collector?.cancel()
        flushJob?.cancel()
        cleanups.close()
    }

    private suspend fun on(e: SttEvent) {
        lock.withLock {
            when (e) {
                is SttEvent.Partial -> if (meta.phase == MeetingPhase.Recording) _live.value = _live.value.copy(partial = e.text)
                SttEvent.SpeechStarted -> flushJob?.cancel()
                is SttEvent.Final -> final(e.text)
                is SttEvent.SpeechEndedAt, SttEvent.SpeechEnded -> scheduleFlush(QUIET_MS)
                else -> Unit
            }
        }
    }

    private fun final(text: String) {
        if (text.isBlank()) return
        if (capture != null) {
            captured += text
            pending += Pending(0, clock.wallMs(), text)
            scheduleFlush(QUIET_MS)
            return
        }
        if (meta.phase != MeetingPhase.Recording) return
        val p = Pending(++nextSeg, clock.wallMs(), text)
        log.append(meta.id, LogLine.Seg(p.n, p.at, p.text))
        pending += p
        recent.addLast(p)
        while (recent.size > RECENT) recent.removeFirst()
        val v = _live.value
        _live.value = v.copy(lines = (v.lines + LiveLine(p.at, p.text)).takeLast(LIVE_LINES), partial = null, segments = v.segments + 1)
        if (pending.size >= MAX_PENDING) scheduleFlush(SPLIT_FLUSH_MS)
    }

    private fun scheduleFlush(afterMs: Long) {
        if (pending.isEmpty()) return
        flushJob?.cancel()
        flushJob = scope.launch {
            delay(afterMs)
            flush()
        }
    }

    /** Take the accurate pass over the pending segments and store it; then the wake phrase. */
    suspend fun flush() = flushing.withLock { flushLocked() }

    private suspend fun flushLocked() {
        val batch = lock.withLock { if (pending.isEmpty()) null else pending.toList().also { pending.clear() } } ?: return
        val accurate = withTimeoutOrNull(ACCURATE_WAIT_MS) { speech.accurate() }
        lock.withLock {
            val usable = accurate?.takeIf { it.segments == batch.size && it.transcript.pass == TranscriptPass.Accurate && it.transcript.text.isNotBlank() }
            val c = capture
            if (c != null) {
                c.complete(usable?.transcript?.text ?: batch.joinToString(" ") { it.text })
                return
            }
            val fast = batch.joinToString(" ") { it.text }
            if (batch.size == 1) {
                val text = usable?.transcript?.text ?: fast
                if (usable != null && text != fast) revise(batch.first().n..batch.first().n, text, "accurate")
                usable?.let { enqueueCleanup(batch.first().n..batch.first().n, it.transcript, fast) }
                wake(batch.first().copy(text = text))
            } else {
                val commands = batch.map { it to parse(it.text) }
                val anyCommand = commands.any { it.second != WakePhrase.Parsed.None }
                if (usable != null && !anyCommand && usable.transcript.text != fast) {
                    revise(batch.first().n..batch.last().n, usable.transcript.text, "accurate")
                    enqueueCleanup(batch.first().n..batch.last().n, usable.transcript, fast)
                }
                batch.forEach { wake(it) }
            }
        }
    }

    private fun revise(range: IntRange, text: String, pass: String) {
        log.append(meta.id, LogLine.Rev(range.first, range.last, text, pass))
        // The rolling lines show the better text: replace the range's lines by one.
        val v = _live.value
        val firstAt = recent.firstOrNull { it.n == range.first }?.at
        if (firstAt != null) {
            val folded = recent.filter { it.n in range }.map { it.at }.toSet()
            val lines = v.lines.filterNot { it.kind == null && it.at in folded && it.at != firstAt }
                .map { if (it.kind == null && it.at == firstAt) it.copy(text = text) else it }
            _live.value = v.copy(lines = lines)
        }
        recent.replaceAll { if (it.n == range.first) it.copy(text = text) else it }
    }

    private fun enqueueCleanup(range: IntRange, heard: Transcript, fast: String) {
        if (cleaner() == null) return
        cleanups.trySend(Triple(range, heard, fast))
    }

    private suspend fun clean(range: IntRange, heard: Transcript, other: String) {
        val c = cleaner() ?: return
        val text = runCatching { c.clean(heard, other) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() && it != heard.text } ?: return
        lock.withLock { revise(range, text, "clean") }
    }

    private fun parse(text: String): WakePhrase.Parsed {
        if (!wakeWord()) return WakePhrase.Parsed.None
        val armed = armedUntil?.let { clock.nowMs() <= it } == true
        return WakePhrase.parse(text, armed)
    }

    private suspend fun wake(seg: Pending) {
        val parsed = parse(seg.text)
        if (parsed != WakePhrase.Parsed.Armed) armedUntil = null
        when (parsed) {
            WakePhrase.Parsed.None -> Unit
            WakePhrase.Parsed.Armed -> {
                armedUntil = clock.nowMs() + WakePhrase.ARM_MS
                ack(Ack.Armed)
                event("meeting.wake", mapOf("kind" to "armed"))
            }
            is WakePhrase.Parsed.Note -> {
                val before = recent.lastOrNull { it.n < seg.n }?.n
                addMarker(LogLine.Mark(seg.at, MarkKind.Note, parsed.text, from = before, to = before, seg = seg.n))
                notes++
                ack(Ack.Note)
                event("meeting.wake", mapOf("kind" to "note"))
            }
            is WakePhrase.Parsed.Task -> {
                addMarker(LogLine.Mark(seg.at, MarkKind.Task, parsed.request, seg = seg.n))
                tasks++
                ack(Ack.Task)
                event("meeting.wake", mapOf("kind" to "task"))
                scope.launch { runCatching { onTask(parsed.request, seg.at, seg.n) } }
            }
        }
    }

    private fun addMarker(m: LogLine.Mark) {
        log.append(meta.id, m)
        val v = _live.value
        _live.value = v.copy(lines = (v.lines + LiveLine(m.at, liveText(m.kind, m.text), m.kind)).takeLast(LIVE_LINES), marks = v.marks + 1, lastMarkAt = m.at)
    }

    private fun setMeta(next: MeetingMeta) {
        meta = next
        log.put(next)
        _live.value = _live.value.copy(meta = next)
    }

    private fun event(name: String, attrs: Map<String, Any?>) = runCatching { telemetry.event(name, attrs, false) }

    companion object {
        /** Quiet after speech before the accurate pass is taken. */
        const val QUIET_MS = 1_200L

        /** Continuous speech: segments (each ≤ 30 s) before a flush anyway. */
        const val MAX_PENDING = 4
        const val SPLIT_FLUSH_MS = 500L
        const val ACCURATE_WAIT_MS = 10_000L
        const val LIVE_LINES = 8
        const val RECENT = 40
        const val CAPTURE_TIMEOUT_MS = 20_000L
        const val MAX_TITLE = 120

        fun liveText(kind: MarkKind, text: String): String = when (kind) {
            MarkKind.Mark -> "Marked"
            MarkKind.Note -> text
            MarkKind.Task -> "Task: $text"
        }
    }
}
