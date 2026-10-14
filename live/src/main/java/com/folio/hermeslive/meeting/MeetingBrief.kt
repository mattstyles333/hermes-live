package com.folio.hermeslive.meeting

import com.folio.hermes.cascade.routing.BriefPlanner
import com.folio.hermes.openrouter.FailureKind
import com.folio.hermes.openrouter.OpenRouterResult
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The writer (DeepSeek Flash on OpenRouter) for chunk summaries. No key: `Failed(NoKey)` before any request. */
fun interface MeetingWriter {
    suspend fun write(system: String, prompt: String, maxTokens: Int): OpenRouterResult<String>

    companion object {
        val None = MeetingWriter { _, _, _ -> OpenRouterResult.Failed(FailureKind.NoKey) }
    }
}

/**
 * The brief that goes to Hermes as one agent task (hub design §5). Verbatim up to [CAP] characters
 * (about 15k tokens); a longer transcript is first summarised in chunks by the writer
 * ([BriefPlanner.chunks], [CHUNK] characters each), and the brief carries the chunk summaries
 * plus every marked passage verbatim, and says so.
 *
 * Why 60k: nothing in the app or the gateway caps `prompt.submit` (it goes over OkHttp's
 * WebSocket, which queues up to 16 MiB), so the cap is an agent-context choice, not a wire limit:
 * a 60k-character prompt leaves the agent most of its context for tools and the notes it writes.
 * About 75 minutes of steady talk fits verbatim.
 */
object MeetingBrief {
    const val CAP = 60_000
    const val CHUNK = 20_000
    const val SUMMARY_TOKENS = 900

    /** A marked passage takes the transcript this long before the marker (the Mark button comes after the words). */
    const val MARK_WINDOW_MS = 30_000L

    const val LABEL_PREFIX = "Meeting notes · "

    fun label(meta: MeetingMeta): String = LABEL_PREFIX + meta.title.trim().ifEmpty { "untitled" }.take(60)

    const val SUMMARY_SYSTEM =
        "You summarise one part of a meeting transcript for an agent who will write the meeting notes. " +
            "Keep every decision, action item (with its owner and due date if said), open question, name, number and date exactly as said. " +
            "Plain text, short lines, at most 25 lines. No preamble."

    /** The outcome of compiling: the brief, and how it was made (for telemetry: counts only). */
    data class Compiled(val brief: String, val chunked: Boolean, val chunks: Int, val transcriptChars: Int)

    /** Compiling needs the writer and it is not reachable now (offline, timeout): hold and retry. */
    class WriterUnavailable(val kind: FailureKind) : Exception(kind.name)

    fun header(meta: MeetingMeta, zone: ZoneId, segments: Int, marks: Int): String = buildString {
        append("Goal: produce meeting notes. Context: ")
        append(meta.title.trim().ifEmpty { "(no title given)" })
        append(", ").append(date(meta.startedAt, zone))
        append(", ").append(time(meta.startedAt, zone))
        meta.endedAt?.let { append("–").append(time(it, zone)) }
        append(", duration ").append(duration(meta.elapsedMs(meta.endedAt ?: meta.startedAt)))
        append(" (").append(segments).append(" segments, ").append(marks).append(" marked).\n")
        append("Notes needed: summary (5 lines), decisions, action items (owner, due date if said), open questions, follow-ups for me.\n")
        append("Then: offer to send the notes/action items where I ask (ask me first; never email without my OK).\n")
        if (meta.additions.isNotEmpty()) {
            append("Added by me after the meeting: ").append(meta.additions.joinToString(" / ") { it.trim() }).append('\n')
        }
        append("The transcript is from on-phone speech recognition: expect misheard words, and no speaker names unless said.\n")
    }

    /** The transcript as lines: `[14:02] text`, markers as `>>> [14:05] MARKED …` lines in time order. */
    fun transcriptLines(t: MeetingTranscript, zone: ZoneId): List<String> {
        data class Item(val at: Long, val order: Int, val line: String)
        val items = ArrayList<Item>()
        t.segments.forEach { items += Item(it.at, 0, "[${time(it.at, zone)}] ${it.text.trim()}") }
        t.markers.forEach { items += Item(it.at, 1, markerLine(it, zone)) }
        t.gaps.forEach { items += Item(it.at, 1, "--- [${time(it.at, zone)}] ${gapText(it.why)} ---") }
        return items.sortedWith(compareBy({ it.at }, { it.order })).map { it.line }
    }

    fun markerLine(m: Marker, zone: ZoneId): String = ">>> [${time(m.at, zone)}] " + when (m.kind) {
        MarkKind.Mark -> "MARKED as important (the words just before this)"
        MarkKind.Note -> "NOTE from me: ${m.text}"
        MarkKind.Task -> "TASK I started (sent separately): ${m.text}"
    }

    private fun gapText(why: String) = when (why) {
        "pause" -> "paused"
        "resume" -> "resumed"
        "resume_after_kill" -> "recording stopped unexpectedly; resumed here"
        else -> why
    }

    /** The verbatim brief when it fits in [CAP], else null. */
    fun verbatim(meta: MeetingMeta, t: MeetingTranscript, zone: ZoneId): String? {
        val head = header(meta, zone, t.segments.size, t.markers.size)
        val body = transcriptLines(t, zone).joinToString("\n")
        val brief = head + "Transcript (timestamped, with marks):\n" + body.ifEmpty { "(nothing was transcribed)" }
        return brief.takeIf { it.length <= CAP }
    }

    /**
     * The brief for [meta] and [t]: verbatim when it fits, else chunk summaries by [writer] plus
     * the marked passages verbatim. Throws [WriterUnavailable] when the writer can't be reached
     * now (the caller holds the meeting and tries again later). With no writer at all (no
     * OpenRouter key) the brief keeps the start and end of the transcript and every marked
     * passage, and says what was left out.
     */
    suspend fun compile(meta: MeetingMeta, t: MeetingTranscript, zone: ZoneId, writer: MeetingWriter): Compiled {
        val lines = transcriptLines(t, zone)
        val chars = lines.sumOf { it.length + 1 }
        verbatim(meta, t, zone)?.let { return Compiled(it, chunked = false, chunks = 1, transcriptChars = chars) }
        val head = header(meta, zone, t.segments.size, t.markers.size)
        val marked = markedPassages(t, zone)
        val chunks = BriefPlanner.chunks(lines.joinToString("\n"), CAP, CHUNK)
        val summaries = ArrayList<String>(chunks.size)
        for ((i, chunk) in chunks.withIndex()) {
            val prompt = "Part ${i + 1} of ${chunks.size} of the transcript of \"${meta.title.ifBlank { "a meeting" }}\":\n$chunk"
            when (val r = writer.write(SUMMARY_SYSTEM, prompt, SUMMARY_TOKENS)) {
                is OpenRouterResult.Ok -> summaries += r.value.trim()
                is OpenRouterResult.Failed -> {
                    if (r.kind == FailureKind.NoKey || r.kind == FailureKind.Refused) return Compiled(truncated(head, lines, marked, chars), chunked = false, chunks = 0, transcriptChars = chars)
                    throw WriterUnavailable(r.kind)
                }
            }
        }
        val brief = buildString {
            append(head)
            append("The transcript was long (").append(chars).append(" characters), so it was summarised in ").append(chunks.size)
                .append(" parts first. Below: the part summaries, then every marked passage verbatim.\n")
            summaries.forEachIndexed { i, s -> append("\nPart ").append(i + 1).append(" summary:\n").append(s).append('\n') }
            append("\nMarked passages (verbatim):\n")
            append(marked.ifEmpty { "(none)" })
        }
        return Compiled(fit(brief), chunked = true, chunks = chunks.size, transcriptChars = chars)
    }

    /** Each marker with the transcript it refers to: the segments in its range, else the [MARK_WINDOW_MS] before it. */
    fun markedPassages(t: MeetingTranscript, zone: ZoneId): String = t.markers.joinToString("\n\n") { m ->
        val covered = if (m.from != null && m.to != null) {
            t.segments.filter { it.n in m.from..m.to }
        } else {
            t.segments.filter { it.at in (m.at - MARK_WINDOW_MS)..m.at }
        }
        (covered.map { "[${time(it.at, zone)}] ${it.text.trim()}" } + markerLine(m, zone)).joinToString("\n")
    }

    /** No writer: the start and end of the transcript within the cap, the middle left out and said so. */
    private fun truncated(head: String, lines: List<String>, marked: String, chars: Int): String {
        val note = "The transcript was too long to send whole ($chars characters) and no summariser was available, " +
            "so the middle is left out (marked below). Every marked passage follows verbatim.\n"
        val tail = "\n\nMarked passages (verbatim):\n" + marked.ifEmpty { "(none)" }
        val budget = (CAP - head.length - note.length - tail.length - 200).coerceAtLeast(2_000)
        val first = ArrayList<String>()
        val last = ArrayDeque<String>()
        var used = 0
        var i = 0
        var j = lines.size - 1
        while (i <= j) {
            val takeFirst = first.size <= last.size
            val line = if (takeFirst) lines[i] else lines[j]
            if (used + line.length + 1 > budget) break
            used += line.length + 1
            if (takeFirst) first += lines[i++] else last.addFirst(lines[j--])
        }
        val cut = j - i + 1
        val body = (first + listOf("--- [$cut lines left out here] ---") + last).joinToString("\n")
        return fit(head + note + "Transcript (timestamped, with marks; the middle left out):\n" + body + tail)
    }

    /** Never over the cap: cut from the end, and say so. */
    private fun fit(brief: String): String =
        if (brief.length <= CAP) brief else brief.take(CAP - 60).trimEnd() + "\n[… cut here: the brief was too long]"

    private fun date(ms: Long, zone: ZoneId) = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.UK).format(Instant.ofEpochMilli(ms).atZone(zone))

    fun time(ms: Long, zone: ZoneId): String = DateTimeFormatter.ofPattern("HH:mm", Locale.UK).format(Instant.ofEpochMilli(ms).atZone(zone))

    /** "1 h 12 min", "23 min", "40 s". */
    fun duration(ms: Long): String {
        val s = ms / 1000
        return when {
            s < 60 -> "$s s"
            s < 3600 -> "${s / 60} min"
            else -> "${s / 3600} h ${(s % 3600) / 60} min"
        }
    }

    /** A task said in the meeting ("Bill, start a task to …"), as a brief of its own. */
    fun taskBrief(request: String, meta: MeetingMeta, at: Long, zone: ZoneId): String = buildString {
        append("Goal: ").append(request.trim().trimEnd('.')).append(".\n")
        append("Context: I asked for this by voice during a meeting")
        if (meta.title.isNotBlank()) append(" (\"").append(meta.title.trim()).append("\")")
        append(" at ").append(time(at, zone)).append(", ").append(date(at, zone)).append(". The words are from speech recognition and may be misheard.\n")
        append("Constraints: ask me before anything outward-facing (email, messages, payments); never email without my OK.\n")
        append("Done when: the job is done, or you have told me what you need from me.")
    }
}
