package com.folio.hermes.cascade

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboxTest {
    private fun outbox() = Outbox<HeldTurn>().apply {
        listOf("u1", "u2", "u3").forEach { assertTrue(hold(it, HeldTurn("said $it", 0))) }
    }

    @Test fun aDropMidFlushKeepsTheRestInOrder() = runBlocking {
        val box = outbox()
        val sent = mutableListOf<String>()
        val first = box.flush { e -> (e.turnId != "u2").also { if (it) sent += e.turnId } }
        assertEquals(1, first)
        assertEquals(listOf("u2", "u3"), box.ids())
        assertEquals(2, box.flush { e -> sent += e.turnId; true })
        assertEquals(listOf("u1", "u2", "u3"), sent)
        assertEquals(0, box.size)
    }

    @Test fun aSentTurnIsNeverHeldOrSentAgain() = runBlocking {
        val box = outbox()
        box.flush { true }
        assertFalse(box.hold("u1", HeldTurn("again", 0)))
        assertFalse("already held", Outbox<HeldTurn>().run { hold("x", HeldTurn("a", 0)); hold("x", HeldTurn("b", 0)) })
        assertEquals(0, box.flush { true })
    }

    @Test fun concurrentFlushesSendEachTurnOnce() = runBlocking {
        val box = outbox()
        val sent = CopyOnWriteArrayList<String>()
        val counts = List(4) {
            async(Dispatchers.Default) {
                box.flush { e ->
                    delay(20)
                    sent += e.turnId
                    true
                }
            }
        }.awaitAll()
        assertEquals(3, counts.sum())
        assertEquals(listOf("u1", "u2", "u3"), sent)
    }

    @Test fun stagesAreRemembered() {
        val box = outbox()
        assertFalse(box.stageDone("u1", "line:ack.do"))
        box.markStage("u1", "line:ack.do")
        assertTrue(box.stageDone("u1", "line:ack.do"))
        assertFalse(box.stageDone("u2", "line:ack.do"))
    }
}
