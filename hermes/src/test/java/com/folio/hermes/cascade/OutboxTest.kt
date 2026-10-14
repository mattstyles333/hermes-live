package com.folio.hermes.cascade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [OutboxState] rules. One flush at a time is the reducer's job: see `CascadeReduceTest.aSecondReconnectDoesNotStartASecondFlush`. */
class OutboxTest {
    private fun outbox(): OutboxState = listOf("u1", "u2", "u3").fold(OutboxState()) { box, id ->
        box.hold(id, HeldTurn("said $id", 0)).also { assertTrue(it.second) }.first
    }

    /** Send in order until [send] says no (C1's flush). */
    private fun flush(box: OutboxState, send: (OutboxEntry) -> Boolean): Pair<OutboxState, Int> {
        var b = box
        var count = 0
        while (true) {
            val entry = b.first() ?: break
            if (!send(entry)) break
            b = b.markSent(entry.turnId)
            count++
        }
        return b to count
    }

    @Test fun aDropMidFlushKeepsTheRestInOrder() {
        val sent = mutableListOf<String>()
        val (box, first) = flush(outbox()) { e -> (e.turnId != "u2").also { if (it) sent += e.turnId } }
        assertEquals(1, first)
        assertEquals(listOf("u2", "u3"), box.ids())
        val (empty, second) = flush(box) { e -> sent += e.turnId; true }
        assertEquals(2, second)
        assertEquals(listOf("u1", "u2", "u3"), sent)
        assertEquals(0, empty.size)
    }

    @Test fun aSentTurnIsNeverHeldOrSentAgain() {
        val (box, _) = flush(outbox()) { true }
        assertFalse(box.hold("u1", HeldTurn("again", 0)).second)
        val once = OutboxState().hold("x", HeldTurn("a", 0)).first
        assertFalse("already held", once.hold("x", HeldTurn("b", 0)).second)
        assertEquals(0, flush(box) { true }.second)
    }

    @Test fun stagesAreRemembered() {
        val box = outbox()
        assertFalse(box.stageDone("u1", "line:ack.do"))
        val marked = box.markStage("u1", "line:ack.do")
        assertTrue(marked.stageDone("u1", "line:ack.do"))
        assertFalse(marked.stageDone("u2", "line:ack.do"))
    }

    @Test fun sentAndStagesAreBounded() {
        var box = OutboxState()
        repeat(OutboxState.KEEP + 50) { i ->
            box = box.hold("u$i", HeldTurn("t", 0)).first.markStage("u$i", "writer").markSent("u$i")
        }
        assertEquals(OutboxState.KEEP, box.sent.size)
        assertEquals(OutboxState.KEEP, box.stages.size)
        assertEquals("u${OutboxState.KEEP + 49}", box.sent.last())
    }
}
