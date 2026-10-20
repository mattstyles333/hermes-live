package com.folio.hermeslive.memo

import com.folio.hermeslive.call.CallFailure
import com.folio.hermeslive.call.CallPhase
import com.folio.hermeslive.call.CallUiState
import com.folio.hermeslive.call.MemoCause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The activity's offline auto-start: once per failed call, never for the memo's own end. */
class MemoAutoStartTest {
    private val failed = CallUiState(phase = CallPhase.Failed, error = "x", failure = CallFailure.Offline)

    @Test fun anOfflineFailureStartsOneMemo() {
        val auto = MemoAutoStart()
        assertNull(auto.onState(CallUiState(phase = CallPhase.Connecting), memosOn = true))
        val memo = auto.onState(failed, memosOn = true)!!
        assertEquals(MemoCause.Offline, memo.memoCause)
        assertEquals("fallback", memo.entry)
        assertNull("the same failure seen again (a recomposition) starts nothing", auto.onState(failed, memosOn = true))
        assertNull(auto.onState(failed.copy(level = 0.1f), memosOn = true))
    }

    @Test fun theMemoEndingNeverStartsAnother() {
        val auto = MemoAutoStart()
        auto.onState(failed, memosOn = true)
        assertNull(auto.onState(CallUiState(phase = CallPhase.Live, memo = true), memosOn = true))
        assertNull(auto.onState(CallUiState(phase = CallPhase.Failed, memo = true, failure = CallFailure.Offline, error = "mic"), memosOn = true))
        assertNull(auto.onState(CallUiState(phase = CallPhase.Ended, memo = true), memosOn = true))
    }

    @Test fun aLaterCallFailingOfflineStartsAgainAndOffStartsNothing() {
        val auto = MemoAutoStart()
        auto.onState(failed, memosOn = true)
        auto.onState(CallUiState(phase = CallPhase.Connecting), memosOn = true)
        assertEquals(MemoCause.Offline, auto.onState(failed, memosOn = true)!!.memoCause)
        assertNull(MemoAutoStart().onState(failed, memosOn = false))
    }
}
