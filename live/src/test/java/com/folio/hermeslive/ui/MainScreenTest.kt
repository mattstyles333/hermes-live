package com.folio.hermeslive.ui

import com.folio.hermeslive.call.CallPhase
import com.folio.hermeslive.call.CallSummary
import com.folio.hermeslive.call.CallUiState
import com.folio.hermeslive.ui.cascade.CallEnd
import com.folio.hermeslive.ui.cascade.EndReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The activity's screen choice: Bill's screen no longer cuts to home the moment his call ends. */
class MainScreenTest {
    @Test fun billsScreenStaysThroughItsEnd() {
        assertEquals(MainScreen.Bill, mainScreen(billActive = true, inCall = true, hasSession = true, outro = false, viewing = false))
        assertEquals(MainScreen.Bill, mainScreen(billActive = true, inCall = true, hasSession = true, outro = true, viewing = false))
        assertEquals("the call ended: the end transition", MainScreen.BillEnded, mainScreen(true, inCall = false, hasSession = true, outro = true, viewing = false))
        assertEquals("closed: home", MainScreen.Home, mainScreen(true, inCall = false, hasSession = true, outro = false, viewing = false))
        assertEquals("the end wins over a viewer", MainScreen.BillEnded, mainScreen(true, inCall = false, hasSession = true, outro = true, viewing = true))
    }

    @Test fun gptLiveAndTheViewerAreUnchanged() {
        assertEquals(MainScreen.Home, mainScreen(billActive = false, inCall = true, hasSession = false, outro = true, viewing = false))
        assertEquals(MainScreen.Home, mainScreen(billActive = false, inCall = false, hasSession = false, outro = true, viewing = false))
        assertEquals(MainScreen.BillViewer, mainScreen(billActive = false, inCall = false, hasSession = false, outro = false, viewing = true))
        assertEquals("no runtime: home", MainScreen.Home, mainScreen(true, inCall = true, hasSession = false, outro = true, viewing = false))
    }

    @Test fun howBillsCallEnded() {
        assertNull(callEndOf(CallUiState(phase = CallPhase.Live)))
        assertNull(callEndOf(CallUiState(phase = CallPhase.Ending)))
        assertNull("dismissed", callEndOf(CallUiState()))
        assertEquals(
            CallEnd(EndReason.HungUp, 252_000),
            callEndOf(CallUiState(phase = CallPhase.Ended, summary = CallSummary(252_000, null, "Call ended."))),
        )
        val error = "The microphone or Bill's speech models could not start."
        assertEquals(CallEnd(EndReason.Error, null, error), callEndOf(CallUiState(phase = CallPhase.Failed, error = error)))
    }
}
