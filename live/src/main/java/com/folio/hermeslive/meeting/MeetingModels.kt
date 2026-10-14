package com.folio.hermeslive.meeting

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Meeting mode (hub design 2026-10-14): Bill listens silently, the phone keeps a timestamped
 * transcript (text only, never audio), and at the end the whole transcript goes to Hermes as one
 * agent task that writes the meeting notes. These are the stored shapes; [MeetingLog] keeps them.
 */

/** Where a meeting is in its life (stored by its serial name). */
@Serializable
enum class MeetingPhase {
    /** The mic is on and segments are being written. */
    @SerialName("recording") Recording,

    /** Paused by the user: the mic is off, the meeting goes on. */
    @SerialName("paused") Paused,

    /** Ended: waiting for "Anything to add?" and Send (or Save). */
    @SerialName("ended") Ended,

    /** Send was asked for; the brief is waiting for the network or for task sending to be connected. */
    @SerialName("queued") Queued,

    /** Hermes accepted the notes task ([MeetingMeta.taskId]). */
    @SerialName("sent") Sent,

    /** Kept on the phone only (the user chose not to send). */
    @SerialName("saved") Saved,
    ;

    /** The mic belongs to this meeting (recording or paused). */
    val live: Boolean get() = this == Recording || this == Paused
}

/**
 * One meeting's record. Times are wall clock (ms). The transcript itself is the meeting's log
 * ([MeetingLog]); this record changes only on start, pause, resume, end and send, so a segment
 * costs one append and nothing else.
 */
@Serializable
data class MeetingMeta(
    val id: String,
    val title: String = "",
    val startedAt: Long,
    val phase: MeetingPhase = MeetingPhase.Recording,
    /** Total time paused (and time lost to a kill, counted on resume). */
    val pausedMs: Long = 0,
    /** Set while paused: when the pause began. */
    val pausedAt: Long? = null,
    val endedAt: Long? = null,
    /** "Anything to add?": what the user typed or said after the meeting, in order. */
    val additions: List<String> = emptyList(),
    /** When Send was asked for. */
    val queuedAt: Long? = null,
    val sentAt: Long? = null,
    /** The Hermes task the notes went to. */
    val taskId: String? = null,
    /** Sends so far (the first is 1); each re-send from history makes a fresh request key. */
    val sends: Int = 0,
    /** Why the last send attempt did not go (a code: `offline`, `not_available`, `failed`). */
    val heldReason: String? = null,
) {
    /** Meeting time so far at [now] (wall ms), without pauses. */
    fun elapsedMs(now: Long): Long {
        val end = endedAt ?: pausedAt ?: now
        return (end - startedAt - pausedMs).coerceAtLeast(0)
    }

    /** The request key for the current send: stable across retries of one send (idempotency). */
    val requestKey: String get() = "meeting-$id-${sends.coerceAtLeast(1)}"
}

/** What a marker is: the Mark button, or something said after the wake word. */
@Serializable
enum class MarkKind {
    /** The Mark button: "this moment matters". */
    @SerialName("mark") Mark,

    /** "Bill, note that …": a note for the meeting notes. */
    @SerialName("note") Note,

    /** "Bill, start a task to …": an agent task, sent on its own. */
    @SerialName("task") Task,
}

/**
 * One line of a meeting's log. Appended as it happens, so a kill loses at most the segment being
 * spoken. Replaying the lines in order rebuilds the transcript ([MeetingLog.transcript]).
 */
@Serializable
sealed interface LogLine {
    /** A finished segment, as the fast pass heard it. */
    @Serializable
    @SerialName("seg")
    data class Seg(val n: Int, val at: Long, val text: String) : LogLine

    /**
     * Better text for segments [from]..[to] (the accurate or cloud pass, or the cleanup): the
     * text replaces [from]'s, and the rest of the range reads as part of it.
     */
    @Serializable
    @SerialName("rev")
    data class Rev(val from: Int, val to: Int, val text: String, val pass: String) : LogLine

    /**
     * A marker at [at]. [from]..[to]: the segments it covers (the Mark button takes the moment
     * before it was pressed; a note the segments before it). [text]: what was said after the wake
     * word ("note that as an action for Priya"), empty for the button.
     */
    @Serializable
    @SerialName("mark")
    data class Mark(val at: Long, val kind: MarkKind, val text: String = "", val from: Int? = null, val to: Int? = null, val seg: Int? = null) : LogLine

    /** The mic stopped ([why]: `pause`, `resume_after_kill`) or started again. */
    @Serializable
    @SerialName("gap")
    data class Gap(val at: Long, val why: String) : LogLine
}

/** A segment as the transcript shows it: the best text so far. */
data class Segment(val n: Int, val at: Long, val text: String, val pass: String)

/** A marker as the transcript shows it. */
data class Marker(val at: Long, val kind: MarkKind, val text: String, val from: Int?, val to: Int?, val seg: Int?)

/** A meeting's transcript rebuilt from its log. Segments that a revision folded into another are left out. */
data class MeetingTranscript(val segments: List<Segment>, val markers: List<Marker>, val gaps: List<LogLine.Gap>) {
    val lastSegment: Int get() = segments.maxOfOrNull { it.n } ?: 0
    val chars: Int get() = segments.sumOf { it.text.length + 1 }

    companion object {
        val Empty = MeetingTranscript(emptyList(), emptyList(), emptyList())
    }
}

/** An in-meeting agent task ("Bill, start a task to …"), held until Hermes takes it. */
@Serializable
data class MeetingTask(
    val meetingId: String,
    val n: Int,
    val brief: String,
    val label: String,
    val at: Long,
    val taskId: String? = null,
    val heldReason: String? = null,
) {
    val key: String get() = "meeting-$meetingId-task-$n"
}
