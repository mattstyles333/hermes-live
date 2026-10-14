package com.folio.hermeslive.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Spec v2 §14 item 10: choosing Bill with a usable key prepares his voice at once, on Wi-Fi only. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AutoPrepareTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After fun tearDown() = scope.cancel()

    private fun shell(name: String, wifi: Boolean): TestShell = TestShell(app, name, scope).also {
        it.network.metered = !wifi
        it.keys.save(FAKE_KEY, KeyCheck.Valid)
    }

    @Test fun enablingBillOnWifiPreparesHisVoice() {
        val t = shell("auto-wifi", wifi = true)
        VoiceSettingsController(t.shell, scope).update { it.copy(voiceEngine = VoiceEngine.Cascade) }
        assertTrue("prepare started", t.shell.prep.state.value.phase != PrepPhase.Idle)
        assertFalse("a second change does not start it again", t.shell.autoPrepare() && t.shell.prep.state.value.phase == PrepPhase.Running)
    }

    @Test fun notOnWifiItWaitsForTheUser() {
        val t = shell("auto-cell", wifi = false)
        VoiceSettingsController(t.shell, scope).update { it.copy(voiceEngine = VoiceEngine.Cascade) }
        assertTrue("nothing downloaded or rendered", t.server.requests.isEmpty())
        assertEquals(PrepPhase.Idle, t.shell.prep.state.value.phase)
        assertEquals(0, t.host.speechModels.downloads.get())
    }

    @Test fun gptLiveNeverPrepares() {
        val t = shell("auto-off", wifi = true)
        VoiceSettingsController(t.shell, scope).update { it.copy(pauseMs = 1_200) }
        assertFalse(t.shell.autoPrepare())
        assertTrue(t.server.requests.isEmpty())
    }
}
