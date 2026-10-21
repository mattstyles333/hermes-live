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
        var flushes = 0
        val triggers = MemoTriggers(backgroundScope, online, { ready.await() }, { true }, { flushes++ })
        triggers.start()
        runCurrent()
        assertEquals("not before the account is read back", 0, flushes)
        ready.complete(Unit)
        runCurrent()
        assertEquals(1, flushes)
        online.value = true
        runCurrent()
        assertEquals(2, flushes)
        online.value = false
        runCurrent()
        online.value = true
        runCurrent()
        assertEquals(3, flushes)
        triggers.memoSaved()
        runCurrent()
        assertEquals("after a memo", 4, flushes)
    }

    @Test fun nothingWaitingNothingIsFlushed() = runTest {
        val online = MutableStateFlow(true)
        var flushes = 0
        MemoTriggers(backgroundScope, online, {}, { false }, { flushes++ }).start()
        runCurrent()
        online.value = false
        runCurrent()
        online.value = true
        runCurrent()
        assertEquals(0, flushes)
    }

    @Test fun theFirstNetworkReadingIsNotABackOnline() = runTest {
        val online = MutableStateFlow(true)
        var flushes = 0
        MemoTriggers(backgroundScope, online, {}, { true }, { flushes++ }).start()
        runCurrent()
        assertEquals("only the start flush", 1, flushes)
    }
}
