package com.folio.hermeslive.service

import android.Manifest
import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.folio.hermeslive.call.CallEngine
import com.folio.hermeslive.call.CallFailure
import com.folio.hermeslive.call.CallPhase
import com.folio.hermeslive.call.CallRequest
import com.folio.hermeslive.call.CallSwitch
import com.folio.hermeslive.call.CallUiState
import com.folio.hermeslive.call.MemoCause
import kotlinx.coroutines.flow.MutableStateFlow
import com.folio.hermeslive.call.FakeAudio
import com.folio.hermeslive.call.FakeEffects
import com.folio.hermeslive.call.FakeLink
import com.folio.hermeslive.call.FakeMemory
import com.folio.hermeslive.call.FakeTransport
import com.folio.hermeslive.data.LiveSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CallServiceTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val transports = mutableListOf<FakeTransport>()
    private val engine = CallEngine(
        scope = scope,
        settings = { LiveSettings() },
        links = { FakeLink() },
        transports = { events -> FakeTransport(events).also { transports += it } },
        audio = FakeAudio(),
        effects = FakeEffects(),
        memory = FakeMemory(),
    )

    @After fun tearDown() = scope.cancel()

    private fun startService(): CallService {
        val controller = Robolectric.buildService(CallService::class.java, Intent(app, CallService::class.java).setAction(CallService.ACTION_START))
        val service = controller.get()
        service.engineProvider = { engine }
        controller.create().startCommand(0, 1)
        shadowOf(Looper.getMainLooper()).idle()
        return service
    }

    @Test fun aStartedCallKeepsTheServiceInTheForeground() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val service = startService()
        assertEquals(CallPhase.Connecting, engine.state.value.phase)
        assertFalse("the service stopped itself as the call began", shadowOf(service).isStoppedBySelf)
        assertNotNull(shadowOf(service).lastForegroundNotification)
        assertFalse(shadowOf(service).isForegroundStopped)

        transports.single().started()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(CallPhase.Live, engine.state.value.phase)
        assertFalse(shadowOf(service).isStoppedBySelf)

        engine.end()
        transports.single().closedBy("client_closed", 3.0)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(CallPhase.Ended, engine.state.value.phase)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test fun withoutTheMicNothingStarts() {
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val service = startService()
        assertEquals(CallPhase.Idle, engine.state.value.phase)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(transports.isEmpty())
    }

    // ── offline-memo slice ─────────────────────────────────────────────────────────────────

    /** A call the test drives by hand. */
    private class ScriptCall : com.folio.hermeslive.call.VoiceCall {
        val starts = mutableListOf<CallRequest>()
        var ends = 0
        override val state = MutableStateFlow(CallUiState())
        override fun start(request: CallRequest) {
            starts += request
            state.value = CallUiState(phase = CallPhase.Live, memo = request.memo)
        }
        override fun end() {
            ends++
            state.value = CallUiState(phase = CallPhase.Ended, memo = state.value.memo)
        }
        override fun toggleMute() = Unit
        override fun selectRoute(option: com.folio.hermeslive.call.RouteOption) = Unit
        override fun submitText(text: String) = Unit
        override fun answerApproval(id: String, choice: com.folio.hermes.ApprovalChoice) = Unit
        override fun answerClarify(id: String, answers: Map<String, String?>) = Unit
        override fun dismissSummary() { state.value = CallUiState() }
    }

    private fun startIntent(request: CallRequest): Intent {
        CallService.start(app, request)
        return shadowOf(app).nextStartedService
    }

    @Test fun theMemoCauseAndThePromptReachTheCall() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val call = ScriptCall()
        val intent = startIntent(CallRequest(prompt = "buy milk", memo = true, entry = "fallback", memoCause = MemoCause.CallDropped))
        val controller = Robolectric.buildService(CallService::class.java, intent)
        controller.get().engineProvider = { call }
        controller.create().startCommand(0, 1)
        val got = call.starts.single()
        assertEquals(MemoCause.CallDropped, got.memoCause)
        assertTrue(got.memo)
        assertEquals("buy milk", got.prompt)
        assertEquals("fallback", got.entry)
    }

    /** Review round 1: the failed call's watcher must not stop the service (or end the memo) the activity just auto-started. */
    @Test fun anAutoStartedMemoOutlivesTheFailedCallsWatcher() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val gpt = ScriptCall()
        val memo = ScriptCall()
        val switch = CallSwitch({ gpt }, { error("no Bill") }, { false }, { true }, memo = { memo }, memoActive = { true }, online = { true })
        val controller = Robolectric.buildService(CallService::class.java, startIntent(CallRequest()))
        val service = controller.get()
        service.engineProvider = { switch }
        controller.create().startCommand(0, 1)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, gpt.starts.size)

        // GPT-Live fails offline (off the main thread, so the service's watcher runs later), and before
        // that watcher has run, the activity starts the memo.
        Thread { gpt.state.value = CallUiState(phase = CallPhase.Failed, failure = CallFailure.Offline, error = "x") }.apply { start(); join() }
        controller.withIntent(startIntent(CallRequest(memo = true, memoCause = MemoCause.Offline))).startCommand(0, 2)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(memo.state.value.inCall)
        assertEquals(0, memo.ends)
        assertFalse("the failed call did not stop the memo's service", shadowOf(service).isStoppedBySelf)

        // The memo ends: now the service stops, for its own start.
        switch.end()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertEquals(2, shadowOf(service).stopSelfResultId)
        controller.destroy()
        assertEquals(1, memo.ends)
    }

    @Test fun destroyedMidCallItEndsOnlyTheCallItStarted() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val first = ScriptCall()
        val other = ScriptCall()
        var current: com.folio.hermeslive.call.VoiceCall = first
        val controller = Robolectric.buildService(CallService::class.java, startIntent(CallRequest()))
        controller.get().engineProvider = { current }
        controller.create().startCommand(0, 1)
        shadowOf(Looper.getMainLooper()).idle()
        // Another call started elsewhere (not through this service): its destroy leaves that one alone.
        current = other
        other.start(CallRequest())
        first.end()
        shadowOf(Looper.getMainLooper()).idle()
        controller.destroy()
        assertEquals(0, other.ends)
    }
}
