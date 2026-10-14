package com.folio.hermeslive.meeting

import com.folio.hermes.openrouter.FailureKind
import com.folio.hermes.openrouter.OpenRouterResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.ZoneId

class MeetingBriefTest {
    private val utc = ZoneId.of("UTC")

    private fun meeting(segments: Int, words: Int = 12, marks: List<LogLine.Mark> = emptyList()): Pair<MeetingMeta, MeetingTranscript> {
        val lines = ArrayList<LogLine>()
        repeat(segments) { i ->
            val text = (1..words).joinToString(" ") { w -> "word$w" } + " line$i"
            lines += LogLine.Seg(i + 1, WALL0 + i * 10_000L, text)
        }
        lines += marks
        val meta = MeetingMeta(
            "m1", title = "Supplier call", startedAt = WALL0, phase = MeetingPhase.Queued,
            endedAt = WALL0 + 45 * 60_000L, additions = listOf("Priya, Sam and me"),
        )
        return meta to MeetingLog.rebuild(lines)
    }

    @Test fun theBriefFollowsTheTemplateWithTimesMarksAndAdditions() = runTest {
        val (meta, t) = meeting(3, marks = listOf(
            LogLine.Mark(WALL0 + 15_000, MarkKind.Note, "Note that as an action for Priya", from = 2, to = 2, seg = 3),
            LogLine.Mark(WALL0 + 25_000, MarkKind.Mark, from = 2, to = 3),
        ))
        val c = MeetingBrief.compile(meta, t, utc, FakeWriter())
        assertFalse(c.chunked)
        val b = c.brief
        assertTrue(b, b.startsWith("Goal: produce meeting notes. Context: Supplier call, Wed 14 Oct 2026, 09:00–09:45, duration 45 min (3 segments, 2 marked).\n"))
        assertTrue(b.contains("Notes needed: summary (5 lines), decisions, action items (owner, due date if said), open questions, follow-ups for me.\n"))
        assertTrue(b.contains("Then: offer to send the notes/action items where I ask (ask me first; never email without my OK).\n"))
        assertTrue(b.contains("Added by me after the meeting: Priya, Sam and me\n"))
        assertTrue(b.contains("Transcript (timestamped, with marks):\n[09:00] word1"))
        assertTrue(b.contains(">>> [09:00] NOTE from me: Note that as an action for Priya"))
        assertTrue(b.contains(">>> [09:00] MARKED as important (the words just before this)"))
        assertEquals("Meeting notes · Supplier call", MeetingBrief.label(meta))
    }

    @Test fun underTheCapItIsVerbatimAndNoWriterIsCalled() = runTest {
        val (meta, t) = meeting(300)
        val writer = FakeWriter()
        val c = MeetingBrief.compile(meta, t, utc, writer)
        assertFalse(c.chunked)
        assertEquals(0, writer.calls)
        assertTrue(c.brief.length <= MeetingBrief.CAP)
        assertTrue(c.brief.contains("line299"))
    }

    @Test fun overTheCapTheWriterSummarisesChunksAndMarkedPassagesGoVerbatim() = runTest {
        // About 2 hours of talk: 1,000 segments of ~110 characters.
        val (meta, t) = meeting(1_000, marks = listOf(LogLine.Mark(WALL0 + 5_000_000, MarkKind.Mark, from = 500, to = 501)))
        assertTrue(t.chars > MeetingBrief.CAP)
        val writer = FakeWriter()
        val c = MeetingBrief.compile(meta, t, utc, writer)
        assertTrue(c.chunked)
        assertEquals(writer.calls, c.chunks)
        assertTrue("chunks ${c.chunks}", c.chunks in 4..8)
        writer.prompts.forEach { assertTrue(it.length <= MeetingBrief.CHUNK + 200) }
        val b = c.brief
        assertTrue(b.length <= MeetingBrief.CAP)
        assertTrue(b.contains("so it was summarised in ${c.chunks} parts first"))
        assertTrue(b.contains("Part 1 summary:\nSUMMARY 1"))
        assertTrue(b.contains("Part ${c.chunks} summary:\nSUMMARY ${c.chunks}"))
        assertTrue(b.contains("Marked passages (verbatim):\n"))
        assertTrue(b.contains("line499") && b.contains("line500"))
        assertFalse("not verbatim", b.contains("line10\n"))
        assertTrue(b.startsWith("Goal: produce meeting notes."))
    }

    @Test fun withNoWriterTheStartAndEndGoAndTheGapIsSaid() = runTest {
        val (meta, t) = meeting(1_000)
        val c = MeetingBrief.compile(meta, t, utc, MeetingWriter.None)
        assertFalse(c.chunked)
        assertTrue(c.brief.length <= MeetingBrief.CAP)
        assertTrue(c.brief.contains("line0"))
        assertTrue(c.brief.contains("line999"))
        assertTrue(c.brief.contains("lines left out here"))
        assertTrue(c.brief.contains("no summariser was available"))
    }

    @Test fun aWriterThatCantBeReachedHoldsTheMeeting() = runTest {
        val (meta, t) = meeting(1_000)
        try {
            MeetingBrief.compile(meta, t, utc, FakeWriter { OpenRouterResult.Failed(FailureKind.Offline) })
            fail("expected WriterUnavailable")
        } catch (e: MeetingBrief.WriterUnavailable) {
            assertEquals(FailureKind.Offline, e.kind)
        }
    }

    @Test fun anInMeetingTaskBriefSaysWhereItCameFromAndAsksBeforeOutwardSteps() {
        val (meta, _) = meeting(1)
        val b = MeetingBrief.taskBrief("Draft the Kestrel reorder", meta, WALL0 + 60_000, utc)
        assertTrue(b.startsWith("Goal: Draft the Kestrel reorder.\n"))
        assertTrue(b.contains("during a meeting (\"Supplier call\") at 09:01"))
        assertTrue(b.contains("never email without my OK"))
    }
}
