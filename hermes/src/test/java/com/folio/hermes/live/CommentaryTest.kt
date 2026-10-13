package com.folio.hermes.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentaryTest {
    @Test fun shortTextIsOneCleanChunk() {
        assertEquals(listOf("Hello there. How are you?"), Commentary.chunk("  Hello\nthere.   How are you? "))
        assertEquals(emptyList<String>(), Commentary.chunk(" \n "))
    }

    @Test fun longTextSplitsOnSentencesWithinTheLimit() {
        val sentence = "This is sentence number one with some words in it."
        val text = List(60) { sentence }.joinToString(" ")
        val chunks = Commentary.chunk(text)
        assertTrue(chunks.size > 1)
        chunks.forEach { assertTrue(it.length <= Commentary.APPEND_CHAR_LIMIT); assertTrue(it.endsWith(".")) }
        assertEquals(text, chunks.joinToString(" "))
    }

    @Test fun overlongSentenceIsHardSplit() {
        val chunks = Commentary.chunk("Short. " + "x".repeat(3_000), limit = 1_400)
        assertEquals(listOf("Short.", "x".repeat(1_400), "x".repeat(1_400), "x".repeat(200)), chunks)
    }

    @Test fun speakableStripsMarkdown() {
        val md = "## Result\n- **Done**: see [the docs](https://example.com/docs) or https://example.com/x\n" +
            "```bash\nrm -rf /tmp/x\n```\nRun `make test` now. | a | b |"
        assertEquals("Result Done: see the docs or a link (code omitted) Run make test now., a, b,", Commentary.speakable(md).trim())
    }

    @Test fun cursorReleasesOnlyCompleteSentencesThenTheTail() {
        val c = SpeechCursor()
        assertNull(c.advance("Hello"))
        assertNull(c.advance("Hello there."))
        assertEquals("Hello there.", c.advance("Hello there. How"))
        assertNull(c.advance("Hello there. How are"))
        assertEquals("How are you?", c.advance("Hello there. How are you? I"))
        assertEquals("I am fine.", c.settle("Hello there. How are you? I am fine."))
        assertTrue(c.spokeAnything)
        assertNull(c.settle("Hello there. How are you? I am fine."))
    }

    @Test fun cursorIsStableWhileACodeFenceIsOpen() {
        val c = SpeechCursor()
        assertNull(c.advance("Here it is: ```py\nprint('a. b')"))
        val c2 = SpeechCursor()
        assertEquals("First.", c2.advance("First. ```py\nx = 1. y"))
        assertEquals("(code omitted) Done.", c2.settle("First. ```py\nx = 1. y\n``` Done."))
    }

    @Test fun settleWithoutStreamingSpeaksEverythingAndEmptyMeansSilence() {
        val c = SpeechCursor()
        assertEquals("All done.", c.settle("All done."))
        val silent = SpeechCursor()
        assertNull(silent.settle(""))
        assertFalse(silent.spokeAnything)
    }

    @Test fun clientEventsCarryIdsAndNullDelegation() {
        val e = LiveClientEvents()
        val say = e.commentary("del_1", "Hi.")
        assertEquals(listOf("""{"content":"Hi.","delegation_id":"del_1","event_id":"say_1","type":"session.commentary.append"}"""), say)
        assertEquals("""{"content":"Hermes is working.","delegation_id":null,"event_id":"think_2","type":"session.thinking.append"}""",
            e.thinking(null, " Hermes   is working. "))
        assertNull(e.thinking("x", "  "))
        assertEquals("""{"event_id":"mute_3","type":"session.input_audio.mute"}""", e.mute(true))
        assertEquals("""{"event_id":"unmute_4","type":"session.input_audio.unmute"}""", e.mute(false))
        assertEquals("""{"content":"Be brief.","delegation_id":null,"event_id":"instr_5","type":"session.instructions.append"}""", e.instructions("Be brief."))
        assertEquals("""{"type":"session.close"}""", e.close())
    }
}
