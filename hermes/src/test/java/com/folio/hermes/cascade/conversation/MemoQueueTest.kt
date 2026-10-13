package com.folio.hermes.cascade.conversation

import com.folio.hermes.cascade.CascadeEvent
import com.folio.hermes.cascade.CascadeSettings
import com.folio.hermes.cascade.FakeOpenRouter
import com.folio.hermes.cascade.HeldTurn
import com.folio.hermes.cascade.MemoSession
import com.folio.hermes.cascade.ports.MemoryStore
import com.folio.hermes.cascade.registry.DurableRig
import com.folio.hermes.cascade.registry.FakeServer
import com.folio.hermes.openrouter.FailureKind
import com.folio.hermes.openrouter.OpenRouterResult
import java.time.ZoneOffset
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Memo mode's storage and ordering (R-OFF-3, R-OFF-4 storage side). Made-up dictation only. */
class MemoQueueTest {
    private val settings = CascadeSettings()
    private val t0 = 1_760_000_000_000L
    private val minute = 60_000L

    private fun group(vararg turns: Pair<String, Long>, s: CascadeSettings = settings): List<MemoSession> =
        turns.fold(emptyList()) { acc, (id, at) -> MemoQueue.group(acc, id, HeldTurn("note $id", 0, at), s) }

    @Test fun turnsUnderTheGapShareASessionAndTheGapSplitsThem() {
        val sessions = group("u1" to t0, "u2" to t0 + minute, "u3" to t0 + 2 * minute + 30_000, "u4" to t0 + 5 * minute)
        assertEquals(listOf(listOf("u1", "u2", "u3"), listOf("u4")), sessions.map { it.turnIds })
        assertEquals(t0, sessions[0].startedWallMs)
        assertEquals(t0 + 2 * minute + 30_000, sessions[0].endedWallMs)
        assertEquals("exactly the gap splits", 2, group("u1" to t0, "u2" to t0 + settings.memoGapMs).size)
    }

    @Test fun aSessionIsCappedBySpanAndBySpeech() {
        val short = settings.copy(memoCapMs = 3 * minute)
        val bySpan = group("u1" to t0, "u2" to t0 + minute, "u3" to t0 + 2 * minute, "u4" to t0 + 3 * minute + 1, s = short)
        assertEquals(listOf(listOf("u1", "u2", "u3"), listOf("u4")), bySpan.map { it.turnIds })
        val tiny = settings.copy(memoCapMs = 2 * MemoQueue.MS_PER_WORD * 2)
        // Each "note uN" is two words: the cap fits two turns' speech.
        val bySpeech = group("u1" to t0, "u2" to t0 + 1, "u3" to t0 + 2, s = tiny)
        assertEquals(listOf(listOf("u1", "u2"), listOf("u3")), bySpeech.map { it.turnIds })
    }

    @Test fun batchesKeepSpokenOrderAndCarryTheOfflineNote() = runTest {
        DurableRig(this).use { r ->
            dictateOffline(r, listOf("first note" to 0L, "second note" to minute, "third note" to 10 * minute))
            val batches = MemoQueue.batches(r.state)
            assertEquals(listOf(listOf("first note", "second note"), listOf("third note")), batches.map { b -> b.turns.map { it.turn.text } })
            assertEquals("first note\nsecond note", batches[0].text)
            assertTrue(batches[0].note(ZoneOffset.UTC).matches(Regex("dictated offline \\d\\d:\\d\\d–\\d\\d:\\d\\d")))
            assertTrue(r.state.connection.memo)
            assertEquals(1, r.events.count { it == CascadeEvent.Memo(true) })
            assertEquals(3, r.events.count { it is CascadeEvent.TurnHeld })
        }
    }

    @Test fun memoSessionsSurviveARestartInOrderAndNeverExpire() = runTest {
        val store = MemoryStore()
        val server = FakeServer()
        DurableRig(this, store, server).use { dictateOffline(it, listOf("first note" to 0L, "second note" to minute, "third note" to 10 * minute)) }
        advanceTimeBy(30L * 24 * 60 * minute)
        DurableRig(this, store, server).use { r ->
            assertEquals(listOf(listOf("u1", "u2"), listOf("u3")), r.state.ceo.memoSessions.map { it.turnIds })
            assertEquals(listOf("u1", "u2", "u3"), r.state.outbox.ids())
            assertEquals(listOf(listOf("u1", "u2"), listOf("u3")), MemoQueue.batches(r.state).map { it.turnIds })
        }
    }

    @Test fun aReconnectRouterGetsTheBatchesInOrderAndMarksThemSent() = runTest {
        val seen = mutableListOf<List<String>>()
        val router = ReconnectRouter { tx, batches ->
            seen += batches.map { it.sessionId.orEmpty() }
            MemoQueue.markSent(tx, batches.first())
            true
        }
        DurableRig(this, reconnect = router).use { r ->
            dictateOffline(r, listOf("first note" to 0L, "second note" to 10 * minute))
            r.actor.network(true)
            advanceUntilIdle()
            assertEquals(listOf(listOf("m-u1", "m-u2")), seen)
            assertEquals(listOf("u2"), r.state.outbox.ids())
            assertTrue("u1" in r.state.outbox.sent)
            assertEquals(listOf("m-u2"), r.state.ceo.memoSessions.map { it.id })
            assertTrue(CascadeEvent.HeldSent("u1") in r.events)
            assertFalse("memo mode ends on reconnect", r.state.connection.memo)
            assertTrue("C1's flush did not run: no task", r.state.tasks.tasks.isEmpty())
        }
    }

    /** Lose the network, then dictate [notes] (text, offset from now) with Jev unreachable. */
    private fun TestScope.dictateOffline(r: DurableRig, notes: List<Pair<String, Long>>) {
        r.actor.network(false)
        runCurrent()
        r.openRouter.jevDefault = FakeOpenRouter.JevScript(10, OpenRouterResult.Failed(FailureKind.Offline))
        var at = 0L
        for ((text, offset) in notes) {
            advanceTimeBy(offset - at)
            at = offset + 2_000
            r.say(text)
        }
        advanceUntilIdle()
    }
}
