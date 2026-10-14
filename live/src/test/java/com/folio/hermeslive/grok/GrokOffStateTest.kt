package com.folio.hermeslive.grok

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.folio.hermes.ApprovalChoice
import com.folio.hermeslive.call.CallPhase
import com.folio.hermeslive.call.CallPlan
import com.folio.hermeslive.call.CallRequest
import com.folio.hermeslive.call.CallSwitch
import com.folio.hermeslive.call.CallUiState
import com.folio.hermeslive.call.Engine
import com.folio.hermeslive.call.RouteOption
import com.folio.hermeslive.call.VoiceCall
import com.folio.hermeslive.data.KeyCheck
import com.folio.hermeslive.data.TestShell
import com.folio.hermeslive.data.VoiceEngine
import com.folio.hermeslive.data.VoiceSettingsController
import com.folio.hermeslive.service.OffCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The grok-voice off state: with the engine not Grok, or no usable xAI key, nothing Grok-related
 * is built: no Grok engine (so no WebSocket), no task runtime for it, no off-call service for it.
 * The last tests are the controls: Grok with a key does build them, and switching away closes them.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class GrokOffStateTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After fun tearDown() = scope.cancel()

    private class FakeCall : VoiceCall {
        val starts = mutableListOf<CallRequest>()
        override val state = MutableStateFlow(CallUiState())
        override fun start(request: CallRequest) {
            starts += request
            state.value = CallUiState(phase = CallPhase.Live)
        }
        override fun end() { state.value = CallUiState(phase = CallPhase.Ended) }
        override fun toggleMute() = Unit
        override fun selectRoute(option: RouteOption) = Unit
        override fun submitText(text: String) = Unit
        override fun answerApproval(id: String, choice: ApprovalChoice) = Unit
        override fun answerClarify(id: String, answers: Map<String, String?>) = Unit
        override fun dismissSummary() { state.value = CallUiState() }
    }

    private class Switch(t: TestShell) {
        val gpt = FakeCall()
        val bills = mutableListOf<FakeCall>()
        val groks = mutableListOf<FakeCall>()
        val switch = CallSwitch(
            gptLive = { gpt },
            cascade = { FakeCall().also { bills += it } },
            cascadeActive = { t.shell.gate.active },
            modelsReady = { true },
            grok = { FakeCall().also { groks += it } },
            grokActive = { t.shell.gate.grokActive },
            billAvailable = { t.shell.gate.billAvailable },
        )
    }

    private fun nothingGrok(t: TestShell) {
        val s = Switch(t)
        s.switch.start(CallRequest())
        assertTrue("the Grok engine (and its WebSocket) is never built", s.groks.isEmpty())
        assertNull(s.switch.grokEngine)
        assertFalse(t.shell.gate.grokActive)
    }

    @Test fun gptLiveWithAnXaiKeyBuildsNoGrok() {
        val t = TestShell(app, "grok-off-gpt", scope)
        t.xaiKeys.save(FAKE_XAI_KEY, KeyCheck.Valid)
        nothingGrok(t)
        assertNull("no task runtime", t.shell.shell.runtime())
        assertTrue(t.host.cascadeFactory.built.isEmpty())
        assertFalse("no off-call service", OffCall.afterHangUp(app, tasksRunning = 2, gate = t.shell.gate))
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun grokWithoutAKeyBuildsNothing() {
        val t = TestShell(app, "grok-off-nokey", scope)
        t.settings.update { it.copy(voiceEngine = VoiceEngine.Grok) }
        nothingGrok(t)
        assertEquals("GPT-Live takes the call", CallPlan.Ready(Engine.GptLive), Switch(t).switch.plan())
        assertNull(t.shell.shell.runtime())
        assertTrue(t.host.cascadeFactory.built.isEmpty())
        assertFalse(OffCall.afterHangUp(app, tasksRunning = 2, gate = t.shell.gate))
        assertFalse("Bill's preparation is not Grok's", t.shell.prep.start())
    }

    @Test fun grokWithARefusedKeyBuildsNothing() {
        val t = TestShell(app, "grok-off-refused", scope)
        t.settings.update { it.copy(voiceEngine = VoiceEngine.Grok) }
        t.xaiKeys.save(FAKE_XAI_KEY, KeyCheck.Refused)
        nothingGrok(t)
        assertNull(t.shell.shell.runtime())
    }

    @Test fun billWithOnlyAnXaiKeyStaysOffToo() {
        val t = TestShell(app, "grok-off-bill", scope)
        t.settings.update { it.copy(voiceEngine = VoiceEngine.Cascade) }
        t.xaiKeys.save(FAKE_XAI_KEY, KeyCheck.Valid)
        nothingGrok(t)
        assertNull(t.shell.shell.runtime())
    }

    @Test fun controlGrokWithAKeyBuildsItAndSwitchingAwayClosesIt() {
        val t = TestShell(app, "grok-on", scope)
        t.settings.update { it.copy(voiceEngine = VoiceEngine.Grok) }
        t.xaiKeys.save(FAKE_XAI_KEY, KeyCheck.Valid)
        val s = Switch(t)
        assertEquals(CallPlan.Ready(Engine.Grok), s.switch.plan())
        s.switch.start(CallRequest())
        assertEquals(1, s.groks.single().starts.size)
        assertTrue("never Bill", s.bills.isEmpty())
        assertNotNull("Grok's tasks need the runtime", t.shell.shell.runtime())
        assertTrue(OffCall.afterHangUp(app, tasksRunning = 1, gate = t.shell.gate))
        assertFalse("Bill's voice is never prepared for Grok", t.shell.prep.start())
        VoiceSettingsController(t.shell, scope).update { it.copy(voiceEngine = VoiceEngine.GptLive) }
        assertTrue("switching away closes it", t.host.cascadeFactory.built.single().closed)
        assertNull(t.shell.shell.runtime())
    }

    @Test fun removingTheXaiKeyClosesIt() {
        val t = TestShell(app, "grok-remove", scope)
        t.settings.update { it.copy(voiceEngine = VoiceEngine.Grok) }
        t.xaiKeys.save(FAKE_XAI_KEY, KeyCheck.Valid)
        assertNotNull(t.shell.shell.runtime())
        VoiceSettingsController(t.shell, scope).removeXaiKey()
        assertTrue(t.host.cascadeFactory.built.single().closed)
        assertNull(t.shell.shell.runtime())
        assertFalse(t.xaiKeys.status.value.saved)
    }

    @Test fun offlineGrokCanHandOverToBillOnlyWhenBillCanRun() {
        val t = TestShell(app, "grok-bill", scope)
        t.settings.update { it.copy(voiceEngine = VoiceEngine.Grok) }
        t.xaiKeys.save(FAKE_XAI_KEY, KeyCheck.Valid)
        assertEquals("no OpenRouter key: Grok again", CallPlan.Ready(Engine.Grok), Switch(t).switch.plan(CallRequest(forceBill = true, memo = true)))
        t.keys.save("sk-or-v1-0000000000000000000000000000000000000000", KeyCheck.Valid)
        assertEquals(CallPlan.Ready(Engine.Cascade), Switch(t).switch.plan(CallRequest(forceBill = true, memo = true)))
        assertEquals("a normal call is still Grok's", CallPlan.Ready(Engine.Grok), Switch(t).switch.plan(CallRequest()))
    }

    private companion object {
        /** Not a key: the right shape only. */
        const val FAKE_XAI_KEY = "xai-0000000000000000000000000000000000000000"
    }
}
