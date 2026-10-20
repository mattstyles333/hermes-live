package com.folio.hermeslive.memo

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** When held memos are tried: at start once the account is read back, after a memo, when the network is back; only if one waits. */
class MemoTriggersTest {
    @Test fun startAfterTheAccountThenEachTimeTheNetworkIsBack() = runTest {
        val online = MutableStateFlow(false)
        val ready = CompletableDeferred<Unit>()
        val flushes = ArrayList<Boolean>()
        val triggers = MemoTriggers(backgroundScope, online, { ready.await() }, { true }, { flushes += it })
        triggers.start()
        runCurrent()
        assertEquals("not before the account is read back", emptyList<Boolean>(), flushes)
        ready.complete(Unit)
        runCurrent()
        assertEquals(listOf(true), flushes)
        online.value = true
        runCurrent()
        assertEquals(listOf(true, true), flushes)
        online.value = false
        runCurrent()
        online.value = true
        runCurrent()
        assertEquals(3, flushes.size)
        triggers.memoSaved()
        runCurrent()
        assertEquals("after a memo: what is due, no force", listOf(true, true, true, false), flushes)
    }

    @Test fun nothingWaitingNothingIsFlushed() = runTest {
        val online = MutableStateFlow(true)
        val flushes = ArrayList<Boolean>()
        MemoTriggers(backgroundScope, online, {}, { false }, { flushes += it }).start()
        runCurrent()
        online.value = false
        runCurrent()
        online.value = true
        runCurrent()
        assertEquals(emptyList<Boolean>(), flushes)
    }

    @Test fun theFirstNetworkReadingIsNotABackOnline() = runTest {
        val online = MutableStateFlow(true)
        val flushes = ArrayList<Boolean>()
        MemoTriggers(backgroundScope, online, {}, { true }, { flushes += it }).start()
        runCurrent()
        assertEquals("only the start flush", listOf(true), flushes)
    }
}
