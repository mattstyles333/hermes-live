package com.folio.hermeslive.memo

import android.app.Application
import android.app.job.JobScheduler
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.folio.hermeslive.call.CallFailure
import com.folio.hermeslive.call.CallPhase
import com.folio.hermeslive.call.CallPlan
import com.folio.hermeslive.call.CallRequest
import com.folio.hermeslive.call.CallSummary
import com.folio.hermeslive.call.CallUiState
import com.folio.hermeslive.call.Engine
import com.folio.hermeslive.data.GptLivePrefs
import com.folio.hermeslive.data.SettingsStore
import com.folio.hermeslive.data.TestShell
import com.folio.hermeslive.graph
import com.folio.hermeslive.ui.CallActions
import com.folio.hermeslive.ui.CallScreen
import com.folio.hermeslive.ui.LiveTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * Settings › Voice › Offline memos is off by default, and off is today's behaviour exactly: no
 * recorder, no job, no file, no runtime for memos, the failure screen without the offer.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xxhdpi")
class MemoOffStateTest {
    @get:Rule val compose = createComposeRule()
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After fun tearDown() {
        scope.cancel()
        GptLivePrefs.of(app).setAgents(GptLivePrefs.AGENTS_DEFAULT)
    }

    @Test fun offByDefaultAndSaved() {
        assertFalse(graphSettings().offlineMemos)
        val prefs = app.getSharedPreferences("memo-settings", Context.MODE_PRIVATE)
        SettingsStore(prefs).update { it.copy(offlineMemos = true) }
        assertTrue(SettingsStore(prefs).settings.value.offlineMemos)
    }

    @Test fun offNothingIsBuiltScheduledOrWritten() {
        val g = app.graph
        assertNull("no memo host", MemoHost.ifEnabled(app))
        // Offline, a GPT-Live call is GPT-Live (it fails as today); a memo asked for is too.
        assertEquals(CallPlan.Ready(Engine.GptLive), g.calls.plan(CallRequest()))
        assertEquals(CallPlan.Ready(Engine.GptLive), g.calls.plan(CallRequest(memo = true)))
        assertFalse("no recorder", g.calls.active.value is MemoCall)
        val jobs = app.getSystemService(JobScheduler::class.java)
        assertTrue("no job", jobs.allPendingJobs.isEmpty())
        assertFalse("no memo file", File(app.noBackupFilesDir, "cascade/memo").exists())
        val failed = CallUiState(phase = CallPhase.Failed, error = "Could not connect to Hermes Live.", failure = CallFailure.Offline)
        assertEquals(MemoFallback.Action.None, MemoFallback.decide(failed, memosOn = false))
    }

    @Test fun theGateOpensForMemosOnlyWhenOnAndSignedIn() {
        GptLivePrefs.of(app).setAgents(false)
        val t = TestShell(app, "memo-gate", scope)
        t.signedIn = true
        assertFalse(t.shell.gate.tasksActive)
        assertFalse("Hermes's voice is not wanted", t.shell.hermesVoiceWanted(t.settings.settings.value))
        t.settings.update { it.copy(offlineMemos = true) }
        assertTrue("the task runtime builds for memos, no OpenRouter key needed", t.shell.gate.tasksActive)
        assertTrue("Hermes transcribes memos", t.shell.hermesVoiceWanted(t.settings.settings.value))
        assertTrue(t.shell.shell.runtime() != null)
        t.signedIn = false
        assertFalse(t.shell.gate.tasksActive)
        assertFalse(t.shell.hermesVoiceWanted(t.settings.settings.value))
        t.shell.dirs.root.deleteRecursively()
    }

    @Test fun theFailureScreenOffersTheMemoOnlyWhenOn() {
        var state by mutableStateOf(CallUiState(phase = CallPhase.Failed, error = "Could not connect to Hermes Live.", failure = CallFailure.HermesUnreachable))
        var on by mutableStateOf(false)
        val taps = ArrayList<String>()
        compose.setContent {
            LiveTheme { CallScreen(state, CallActions(onRecordMemo = if (on) ({ taps += "memo" }) else null)) }
        }
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithTag("record-memo").fetchSemanticsNodes().size)
        on = true
        compose.waitForIdle()
        compose.onNodeWithTag("record-memo").assertTextEquals("Hermes is unreachable · Record a memo").performClick()
        assertEquals(listOf("memo"), taps)
        state = state.copy(failure = CallFailure.Other)
        compose.waitForIdle()
        assertEquals("sign-in and the like: no offer", 0, compose.onAllNodesWithTag("record-memo").fetchSemanticsNodes().size)
        state = CallUiState(phase = CallPhase.Ended, summary = CallSummary(60_000, null, "The connection dropped."), failure = CallFailure.Offline)
        compose.waitForIdle()
        compose.onNodeWithTag("record-memo").assertTextEquals("Continue as memo")
    }

    @Test fun theFallbackStartsOnlyForAnOfflineStartAndOffersTheRest() {
        fun failed(f: CallFailure) = CallUiState(phase = CallPhase.Failed, failure = f)
        assertEquals(MemoFallback.Action.Start, MemoFallback.decide(failed(CallFailure.Offline), memosOn = true))
        assertEquals(MemoFallback.Action.Offer, MemoFallback.decide(failed(CallFailure.HermesUnreachable), memosOn = true))
        assertEquals(MemoFallback.Action.Offer, MemoFallback.decide(failed(CallFailure.VoiceUnavailable), memosOn = true))
        assertEquals(MemoFallback.Action.None, MemoFallback.decide(failed(CallFailure.Other), memosOn = true))
        assertEquals(MemoFallback.Action.None, MemoFallback.decide(CallUiState(phase = CallPhase.Failed), memosOn = true))
        val dropped = CallUiState(phase = CallPhase.Ended, summary = CallSummary(1, null, "x"), failure = CallFailure.Offline)
        assertEquals("a dropped call is offered, not taken over", MemoFallback.Action.Offer, MemoFallback.decide(dropped, memosOn = true))
        assertEquals("call_dropped", MemoFallback.request(dropped, "fallback").memoCause)
        assertEquals("offline", MemoFallback.request(failed(CallFailure.Offline), "fallback").memoCause)
        assertTrue(MemoFallback.request(failed(CallFailure.Offline), "fallback").memo)
    }

    private fun graphSettings() = app.graph.settings.settings.value
}
