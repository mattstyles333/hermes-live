package com.folio.hermeslive.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.folio.hermeslive.service.MemoEntry
import com.folio.hermeslive.service.OffCall
import com.folio.hermeslive.service.OffCallService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * R-Q-1: with the engine on `gpt-live`, or with no usable key, nothing cascade-related is built:
 * no speech-to-text or runtime (the factory), no OpenRouter client, no Hermes task sessions or
 * gateway hold, no off-call service, no model download, no cascade files. The last test is the
 * control: with `cascade` and a key, the same calls do build it (so the others are not vacuous).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CascadeOffStateTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After fun tearDown() = scope.cancel()

    private fun everythingThatCouldBuildTheCascade(t: TestShell) {
        val shell = t.shell
        assertNull("no runtime", shell.shell.runtime())
        assertFalse("off-call refused", OffCall.afterHangUp(app, tasksRunning = 3, gate = shell.gate))
        assertFalse("prepare refused", shell.prep.start())
        VoiceSettingsController(shell, scope).apply {
            refresh()
            prepare()
        }
        assertEquals("offline memos off by default: no memo", MemoEntry.Mode.Call, MemoEntry.mode(t.settings.settings.value.offlineMemos, online = false))

        assertTrue("the factory (STT, OpenRouter, sessions) was never called", t.host.cascadeFactory.built.isEmpty())
        assertEquals("no OpenRouter client", 0, t.openRouters.get())
        assertTrue("no OpenRouter request", t.server.requests.isEmpty())
        assertEquals("no model download", 0, t.host.speechModels.downloads.get())
        assertEquals("no gateway hold", 0, t.host.offCallHooks.held.get())
        assertNull("no service started", shadowOf(app).nextStartedService)
        assertFalse("no cascade files", shell.dirs.root.exists())
    }

    @Test fun gptLiveWithAKeyBuildsNothing() {
        val t = TestShell(app, "off-gpt", scope)
        t.keys.save(FAKE_KEY, KeyCheck.Valid)
        assertEquals(VoiceEngine.GptLive, t.settings.settings.value.voiceEngine)
        everythingThatCouldBuildTheCascade(t)
    }

    @Test fun cascadeWithoutAKeyBuildsNothing() {
        val t = TestShell(app, "off-nokey", scope)
        t.settings.update { it.copy(voiceEngine = VoiceEngine.Cascade) }
        everythingThatCouldBuildTheCascade(t)
    }

    @Test fun cascadeWithARefusedKeyBuildsNothing() {
        val t = TestShell(app, "off-refused", scope)
        t.settings.update { it.copy(voiceEngine = VoiceEngine.Cascade) }
        t.keys.save(FAKE_KEY, KeyCheck.Refused)
        everythingThatCouldBuildTheCascade(t)
    }

    @Test fun controlCascadeWithAKeyBuildsItAndSwitchingBackClosesIt() {
        val t = TestShell(app, "on", scope)
        t.settings.update { it.copy(voiceEngine = VoiceEngine.Cascade) }
        t.keys.save(FAKE_KEY, KeyCheck.Valid)
        val shell = t.shell
        assertTrue(shell.gate.active)
        val runtime = shell.shell.runtime()
        assertNotNull(runtime)
        assertSame("built once", runtime, shell.shell.runtime())
        assertEquals(1, t.host.cascadeFactory.built.size)

        assertFalse("no tasks: no off-call service", OffCall.afterHangUp(app, tasksRunning = 0, gate = shell.gate))
        assertNull(shadowOf(app).nextStartedService)
        assertTrue(OffCall.afterHangUp(app, tasksRunning = 2, gate = shell.gate))
        assertEquals(OffCallService::class.java.name, shadowOf(app).nextStartedService.component!!.className)

        VoiceSettingsController(shell, scope).update { it.copy(voiceEngine = VoiceEngine.GptLive) }
        assertTrue("switching back closes the cascade", t.host.cascadeFactory.built.single().closed)
        assertNull(shell.shell.existing())
        assertNull(shell.shell.runtime())
        assertEquals(1, t.host.cascadeFactory.built.size)
    }

    @Test fun removingTheKeyClosesTheCascade() {
        val t = TestShell(app, "remove", scope)
        t.settings.update { it.copy(voiceEngine = VoiceEngine.Cascade) }
        t.keys.save(FAKE_KEY, KeyCheck.Valid)
        assertNotNull(t.shell.shell.runtime())
        VoiceSettingsController(t.shell, scope).removeKey()
        assertTrue(t.host.cascadeFactory.built.single().closed)
        assertNull(t.shell.shell.runtime())
    }
}
