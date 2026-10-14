package com.folio.hermes.cascade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechQueueTest {
    private val q = SpeechQueue()

    private fun playAll(): List<String> {
        val out = mutableListOf<String>()
        while (true) {
            val p = q.next() ?: break
            out += p.text
            q.played(p, 1_000, 1_000)
        }
        return out
    }

    @Test fun replyBeforeResultsAndInOrder() {
        val r = q.open(SpeechKind.Result, taskId = "t1")
        q.add(r, "The stock check's back.")
        q.close(r)
        val a = q.open(SpeechKind.Reply)
        q.add(a, "On it.", "ack.do")
        q.add(a, "Started.")
        q.close(a)
        assertEquals(listOf("On it.", "Started.", "The stock check's back."), playAll())
    }

    @Test fun aResultWaitsForTheUserToStopTalking() {
        val r = q.open(SpeechKind.Result, taskId = "t1")
        q.add(r, "That's back.")
        q.close(r)
        q.userSpeaking = true
        assertNull(q.next())
        q.userSpeaking = false
        q.userHolding = true // paused mid-thought: still the user's floor
        assertNull(q.next())
        q.userHolding = false
        assertEquals("That's back.", q.next()!!.text)
    }

    @Test fun aBackchannelMayPlayWhileHolding() {
        q.userHolding = true
        val u = q.open(SpeechKind.Reply)
        q.add(u, "Mm-hm.", "listen.backchannel")
        q.close(u)
        assertEquals("Mm-hm.", q.next()!!.text)
    }

    @Test fun anOpenUtteranceHoldsItsPlace() {
        val a = q.open(SpeechKind.Reply)
        val b = q.open(SpeechKind.Reply)
        q.add(b, "Second.")
        q.close(b)
        assertNull("the first reply is still being written", q.next())
        q.add(a, "First.")
        q.close(a)
        assertEquals(listOf("First.", "Second."), playAll())
    }

    @Test fun nothingStartsWhileBillIsTalking() {
        val a = q.open(SpeechKind.Reply)
        q.add(a, "One.")
        q.add(a, "Two.")
        val p = q.next()!!
        assertNull(q.next())
        assertEquals("Two.", q.upcoming()!!.text)
        q.played(p, 500, 500)
        assertEquals("Two.", q.next()!!.text)
    }

    @Test fun cutInDropsQueuedSpeechButKeepsResults() {
        val reply = q.open(SpeechKind.Reply, turnId = "u1")
        q.add(reply, "On it.")
        q.add(reply, "This part never plays.")
        q.close(reply)
        val note = q.open(SpeechKind.Notice)
        q.add(note, "Needs your okay.")
        q.close(note)
        val result = q.open(SpeechKind.Result, taskId = "t2")
        q.add(result, "The report's back.")
        q.close(result)
        val playing = q.next()!!
        val cut = q.cutIn()
        assertEquals(playing, cut.playing)
        q.played(playing, 200, 1_000)
        assertTrue(q.isDropped(reply))
        assertTrue(q.isDropped(note))
        assertEquals(listOf("The report's back."), playAll())
        // Only what was actually heard is recorded: a fifth of "On it." rounds to nothing.
        assertEquals("", q.heard(reply))
    }

    @Test fun playedMsKeepsOnlyTheHeardWords() {
        val u = q.open(SpeechKind.Reply)
        q.add(u, "One two three four five six seven eight nine ten.")
        val p = q.next()!!
        q.cutIn()
        q.played(p, 500, 1_000)
        assertEquals("One two three four five…", q.heard(u))
    }

    @Test fun cuttingAResultLeavesItsTaskUnread() {
        val r = q.open(SpeechKind.Result, taskId = "t9")
        q.add(r, "The thing's back.")
        q.add(r, "More detail.")
        q.close(r)
        q.next()
        assertEquals("t9", q.cutIn().interruptedResultTask)
        assertTrue(q.isDropped(r))
    }
}
