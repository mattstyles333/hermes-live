package com.folio.hermeslive.ui

import com.folio.hermes.cascade.CascadeUiState
import com.folio.hermes.cascade.TurnPhase
import com.folio.hermes.live.Speaker
import com.folio.hermes.live.VoiceLiveStatus
import com.folio.hermeslive.call.CallActivity
import com.folio.hermeslive.call.CallPhase
import com.folio.hermeslive.call.CallUiState
import com.folio.hermeslive.call.Caption
import com.folio.hermeslive.call.GptLiveScreen
import com.folio.hermeslive.call.ondemand.OnDemandUi
import com.folio.hermeslive.call.ondemand.SessionView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** GPT-Live's screen mapping, session pill and voice line (pure). */
class GptLiveUiTest {
    @Test fun theAgentsScreenTakesTheTurnFromGptLiveAndTheTrayFromTheTasks() {
        val call = CallUiState(phase = CallPhase.Live, activity = CallActivity.HermesSpeaking, level = 0.7f, captions = listOf(Caption(Speaker.User, "Check stock"), Caption(Speaker.Assistant, "On it. Checking Leeds now")))
        val s = GptLiveScreen.state(CascadeUiState(), call)
        assertEquals(TurnPhase.BillSpeaking, s.turn.phase)
        assertTrue(s.bill.speaking)
        assertEquals(0.7f, s.bill.level)
        assertEquals("Checking Leeds now", s.bill.text)
        assertTrue(s.connection.online)
        assertNull(s.resume)
        val listening = GptLiveScreen.state(CascadeUiState(), call.copy(activity = CallActivity.UserSpeaking, captions = listOf(Caption(Speaker.User, "And Manchester"))))
        assertEquals("And Manchester", listening.turn.partial)
        assertFalse(listening.bill.speaking)
    }

    @Test fun thePillShowsOnlyWhileTheSessionIsNotConnected() {
        assertNull("off", sessionLabel(OnDemandUi(enabled = false, view = SessionView.Listening)))
        assertNull(sessionLabel(OnDemandUi(enabled = true, view = SessionView.Connected)))
        assertEquals("Listening (on phone)", sessionLabel(OnDemandUi(enabled = true, view = SessionView.Listening)))
        assertEquals("Connecting…", sessionLabel(OnDemandUi(enabled = true, view = SessionView.Connecting)))
        assertTrue(OnDemandUi(enabled = true, view = SessionView.Listening).dimmed)
    }

    @Test fun theVoiceIsTheServersReadOnly() {
        assertEquals("Unknown (tap to check)", gptLiveVoiceLine(null))
        assertEquals("Marin", gptLiveVoiceLine(VoiceLiveStatus(ok = true, available = true, voice = "marin")))
        assertEquals("The server's default", gptLiveVoiceLine(VoiceLiveStatus(ok = true, available = true, voice = "")))
    }
}
