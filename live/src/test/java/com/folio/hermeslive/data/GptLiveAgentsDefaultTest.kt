package com.folio.hermeslive.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.folio.hermeslive.call.GptLiveAgentsFactory
import com.folio.hermeslive.service.OffCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * GPT-Live's parallel agents are on by default (hub decision, 1.3.0 integration) but only while
 * signed in: signed out, the gate stays shut and nothing of the task machinery is built (R-Q-1);
 * switched off, a GPT-Live call is the single Hermes conversation again.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class GptLiveAgentsDefaultTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After fun tearDown() = scope.cancel()

    private fun agentsFactory(t: TestShell) = GptLiveAgentsFactory.gated(
        enabled = { t.shell.gate.gptLiveAgentsActive },
        signedIn = { t.signedIn },
        runtime = { t.shell.shell.runtime(); null },
    )

    @Test fun theSwitchIsOnWithNothingSaved() {
        assertTrue(GptLivePrefs.AGENTS_DEFAULT)
        assertTrue(GptLivePrefs.of(app).agents.value)
    }

    @Test fun signedOutWithTheDefaultsNothingOpens() {
        val t = TestShell(app, "agents-signed-out", scope)
        assertEquals(VoiceEngine.GptLive, t.settings.settings.value.voiceEngine)
        assertTrue("the switch is on", GptLivePrefs.of(app).agents.value)
        assertFalse(t.shell.gate.gptLiveAgentsActive)
        assertFalse("no task machinery", t.shell.gate.tasksActive)
        assertNull(agentsFactory(t).create())
        assertNull("no runtime", t.shell.shell.runtime())
        assertTrue("the factory was never called", t.host.cascadeFactory.built.isEmpty())
        assertFalse("no off-call service", OffCall.afterHangUp(app, tasksRunning = 2, gate = t.shell.gate))
    }

    @Test fun signedInWithTheDefaultsAgentsRunOnTheTaskRuntime() {
        val t = TestShell(app, "agents-signed-in", scope)
        t.signedIn = true
        assertTrue(t.shell.gate.gptLiveAgentsActive)
        assertTrue(t.shell.gate.tasksActive)
        agentsFactory(t).create()
        assertEquals("a GPT-Live call asks for the task runtime", 1, t.host.cascadeFactory.built.size)
        assertNotNull(t.shell.shell.existing())
    }

    @Test fun switchedOffOrSignedOutTheRuntimeIsReleased() {
        val t = TestShell(app, "agents-off", scope)
        t.signedIn = true
        assertNotNull(t.shell.shell.runtime())
        GptLivePrefs.of(app).setAgents(false)
        assertFalse(t.shell.gate.gptLiveAgentsActive)
        assertFalse(t.shell.gate.tasksActive)
        t.shell.onSettingsChanged()
        assertTrue("released", t.host.cascadeFactory.built.single().closed)
        assertNull("off: the call is the single conversation (no agents host)", agentsFactory(t).create())
        GptLivePrefs.of(app).setAgents(true)
        assertNotNull(t.shell.shell.runtime())
        t.signedIn = false
        t.shell.onSettingsChanged()
        assertTrue("signing out releases it too", t.host.cascadeFactory.built.last().closed)
    }

    @Test fun otherEnginesNeverTurnOnGptLiveAgents() {
        val t = TestShell(app, "agents-other", scope)
        t.signedIn = true
        t.settings.update { it.copy(voiceEngine = VoiceEngine.Cascade) }
        assertFalse(t.shell.gate.gptLiveAgentsActive)
        assertFalse("Custom without a key: nothing", t.shell.gate.tasksActive)
        t.settings.update { it.copy(voiceEngine = VoiceEngine.Grok) }
        assertFalse(t.shell.gate.gptLiveAgentsActive)
    }
}
