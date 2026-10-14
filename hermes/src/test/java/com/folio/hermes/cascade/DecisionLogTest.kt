package com.folio.hermes.cascade

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DecisionLogTest {
    private fun rec(n: Int, at: Long) = DecisionRecord(turnId = "u$n", atMs = at, outcome = "done", route = "reply", jevCalls = 1)

    @Test fun keepsTheLast1000Turns() {
        val store = MemoryDecisionStore()
        val log = DecisionLog(store, now = { 10_000L })
        repeat(1_250) { log.record(rec(it, 10_000L)) }
        val records = log.records()
        assertEquals(1_000, records.size)
        assertEquals("u250", records.first().turnId)
        assertEquals("u1249", records.last().turnId)
        assertTrue("the append-only store is compacted", store.readAll().size < 1_250)
        assertEquals(1_000, DecisionLog(store, now = { 10_000L }).records().size)
    }

    @Test fun dropsTurnsOlderThan14Days() {
        val day = 24 * 60 * 60 * 1000L
        var now = 20 * day
        val log = DecisionLog(MemoryDecisionStore(), now = { now })
        log.record(rec(1, 5 * day))
        log.record(rec(2, 10 * day))
        assertEquals(listOf("u2"), log.records().map { it.turnId })
        now = 25 * day
        assertEquals(emptyList<String>(), log.records().map { it.turnId })
    }

    @Test fun offRecordsNothingAndClearEmpties() {
        var on = false
        val store = MemoryDecisionStore()
        val log = DecisionLog(store, enabled = { on })
        log.record(rec(1, System.currentTimeMillis()))
        assertTrue(log.records().isEmpty())
        on = true
        log.record(rec(2, System.currentTimeMillis()))
        assertEquals(1, log.records().size)
        log.clear()
        assertTrue(log.records().isEmpty())
        assertTrue(store.readAll().isEmpty())
    }

    @Test fun fileStoreReloadsAndExportsJson() {
        val file = Files.createTempFile("decisions", ".jsonl").toFile()
        val now = System.currentTimeMillis()
        DecisionLog(FileDecisionStore(file)).record(rec(1, now).copy(slot = "ack.do", line = "On it.", writerRan = true))
        val reloaded = DecisionLog(FileDecisionStore(file))
        assertEquals("ack.do", reloaded.records().single().slot)
        val json = reloaded.exportJson()
        assertTrue(json.startsWith("[") && json.contains("\"writerRan\":true"))
        file.delete()
    }
}
