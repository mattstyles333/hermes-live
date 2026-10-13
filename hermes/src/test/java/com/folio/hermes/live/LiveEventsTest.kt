package com.folio.hermes.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveEventsTest {
    @Test fun parsesTheEventsTheClientActsOn() {
        assertEquals(LiveServerEvent.Started("sess_1"), LiveServerEvent.parse("""{"type":"session.started","session":{"id":"sess_1"}}"""))
        assertEquals(
            LiveServerEvent.Transcript(LiveTranscriptFragment(Speaker.User, "Hi", 120, 480)),
            LiveServerEvent.parse("""{"type":"session.input_transcript.delta","delta":"Hi","start_ms":120,"end_ms":480.5}"""),
        )
        assertEquals(
            LiveServerEvent.Transcript(LiveTranscriptFragment(Speaker.Assistant, "Hello", 0, 0)),
            LiveServerEvent.parse("""{"type":"session.output_transcript.delta","delta":"Hello"}"""),
        )
        assertEquals(
            LiveServerEvent.DelegationCreated("del_9"),
            LiveServerEvent.parse("""{"type":"session.delegation.created","delegation":{"id":"del_9","type":"client","target":"backend"}}"""),
        )
        assertEquals(LiveServerEvent.UsageUpdated(61.5), LiveServerEvent.parse("""{"type":"session.usage.updated","usage":{"seconds":61.5}}"""))
        assertEquals(LiveServerEvent.Closed("idle", 90.0), LiveServerEvent.parse("""{"type":"session.closed","reason":"idle","usage":{"seconds":90}}"""))
        assertEquals(LiveServerEvent.Other("session.updated"), LiveServerEvent.parse("""{"type":"session.updated","extra":[1,2]}"""))
        assertNull(LiveServerEvent.parse("not json"))
        assertNull(LiveServerEvent.parse("""{"no_type":true}"""))
    }

    @Test fun lateAppendErrorsAreIgnorable() {
        assertEquals(
            LiveServerEvent.Error("context_injection_incomplete", "late", ignorable = true),
            LiveServerEvent.parse("""{"type":"error","error":{"code":"context_injection_incomplete","message":"late"}}"""),
        )
        assertEquals(
            LiveServerEvent.Error(null, "boom", ignorable = false),
            LiveServerEvent.parse("""{"type":"error","error":{"code":null,"message":"boom"}}"""),
        )
    }

    @Test fun historyFollowsTheCaps() {
        val turns = (1..30).map { LiveTurn(if (it % 2 == 0) Speaker.Assistant else Speaker.User, "turn $it") }
        val history = VoiceLiveApi.history(turns)
        assertEquals(24, history.size)
        assertEquals("turn 7", history.first().content.single().text)
        assertEquals("turn 30", history.last().content.single().text)
        assertEquals("assistant", history.last().role)
        assertEquals("output_text", history.last().content.single().type)
        assertEquals("input_text", history.first().content.single().type)
    }

    @Test fun historyCutsLongMessagesAndStopsAtTheBudget() {
        val long = "word ".repeat(400) // 2000 chars -> 1200 after cut
        val turns = listOf(LiveTurn(Speaker.User, "first"), LiveTurn(Speaker.User, "  ")) + List(6) { LiveTurn(Speaker.User, long) }
        val history = VoiceLiveApi.history(turns)
        // Each is cut to exactly 1200 (the cut is not re-trimmed, as in the source); 5 x 1200 = 6000 fits,
        // the sixth would not, so it stops there and never reaches "first".
        assertEquals(5, history.size)
        history.forEach { assertEquals(1_200, it.content.single().text.length) }
    }
}
