package com.folio.hermeslive.meeting

import com.folio.hermeslive.tasks.TaskSubmitter
import com.folio.hermes.cascade.ports.Store
import kotlinx.serialization.json.Json

/**
 * Meetings in the cascade's durable [Store] (the same `FileStore`: fsynced, CRC per line, torn
 * lines skipped). Every key starts with `cascade.meeting.`, so a Hermes sign-out wipes meetings
 * with the rest of Bill's account-bound state (`CascadeSignOut`), and Bill's runtime wipes them
 * when another account signs in (`CascadeSignOut.ownedBy`, run by [TaskSubmitter.prepare] before the
 * outbox reads a meeting): a held meeting never reaches another account's server.
 *
 * | Key / log | What |
 * |---|---|
 * | `cascade.meeting.meta.<id>` | [MeetingMeta] (changes on start, pause, resume, end, send) |
 * | `cascade.meeting.log.<id>` (log) | [LogLine]s, appended as they happen |
 * | `cascade.meeting.brief.<id>` | the compiled brief, written before it is submitted (a retry sends the same text) |
 * | `cascade.meeting.task.<id>.<n>` | [MeetingTask]: an in-meeting agent task until Hermes takes it |
 * | `cascade.meeting.active` | the id of the meeting that owns the mic (recording or paused) |
 *
 * No audio is ever stored: text only (R-STT-3).
 */
class MeetingLog(private val store: () -> Store) {
    private val s: Store get() = store()

    fun create(meta: MeetingMeta) {
        put(meta)
        s.put(ACTIVE, meta.id)
    }

    fun meta(id: String): MeetingMeta? = s.get(META + id)?.let { runCatching { JSON.decodeFromString(MeetingMeta.serializer(), it) }.getOrNull() }

    fun put(meta: MeetingMeta) = s.put(META + meta.id, JSON.encodeToString(MeetingMeta.serializer(), meta))

    /** The meeting that owns the mic, if its record says it is still live. */
    fun active(): MeetingMeta? = s.get(ACTIVE)?.let(::meta)?.takeIf { it.phase.live }

    fun clearActive(id: String) {
        if (s.get(ACTIVE) == id) s.put(ACTIVE, null)
    }

    fun append(id: String, line: LogLine) = s.append(LOG + id, JSON.encodeToString(LogLine.serializer(), line))

    fun lines(id: String): List<LogLine> = s.read(LOG + id).mapNotNull { runCatching { JSON.decodeFromString(LogLine.serializer(), it) }.getOrNull() }

    fun transcript(id: String): MeetingTranscript = rebuild(lines(id))

    fun brief(id: String): String? = s.get(BRIEF + id)

    fun putBrief(id: String, brief: String?) = s.put(BRIEF + id, brief)

    fun tasks(id: String? = null): List<MeetingTask> = s.keys(if (id == null) TASK else "$TASK$id.")
        .mapNotNull { k -> s.get(k)?.let { runCatching { JSON.decodeFromString(MeetingTask.serializer(), it) }.getOrNull() } }
        .sortedWith(compareBy({ it.at }, { it.n }))

    fun putTask(task: MeetingTask) = s.put("$TASK${task.meetingId}.${task.n}", JSON.encodeToString(MeetingTask.serializer(), task))

    /** Every meeting, newest first. */
    fun all(): List<MeetingMeta> = s.keys(META).mapNotNull { meta(it.removePrefix(META)) }.sortedByDescending { it.startedAt }

    /** Forget a meeting: its record, transcript, brief and tasks. */
    fun delete(id: String) {
        clearActive(id)
        s.put(META + id, null)
        s.put(BRIEF + id, null)
        s.trim(LOG + id, 0)
        s.keys("$TASK$id.").forEach { s.put(it, null) }
    }

    companion object {
        const val PREFIX = "cascade.meeting."
        const val META = PREFIX + "meta."
        const val LOG = PREFIX + "log."
        const val BRIEF = PREFIX + "brief."
        const val TASK = PREFIX + "task."
        const val ACTIVE = PREFIX + "active"

        val JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
            classDiscriminator = "t"
        }

        /** Replay [lines] in order: revisions replace text, folded segments drop out. */
        fun rebuild(lines: List<LogLine>): MeetingTranscript {
            val segs = LinkedHashMap<Int, Segment>()
            val folded = HashSet<Int>()
            val markers = ArrayList<Marker>()
            val gaps = ArrayList<LogLine.Gap>()
            for (line in lines) when (line) {
                is LogLine.Seg -> if (line.n !in segs) segs[line.n] = Segment(line.n, line.at, line.text, "fast")
                is LogLine.Rev -> {
                    val first = segs[line.from] ?: continue
                    segs[line.from] = first.copy(text = line.text, pass = line.pass)
                    for (n in line.from + 1..line.to) if (n in segs) folded += n
                }
                is LogLine.Mark -> markers += Marker(line.at, line.kind, line.text, line.from, line.to, line.seg)
                is LogLine.Gap -> gaps += line
            }
            // An instruction to Bill stays in the transcript too (its marker points at it): a false
            // wake never loses words.
            return MeetingTranscript(segs.values.filter { it.n !in folded && it.text.isNotBlank() }, markers, gaps)
        }
    }
}
