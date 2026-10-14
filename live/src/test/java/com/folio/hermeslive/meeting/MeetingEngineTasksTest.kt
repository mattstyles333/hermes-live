package com.folio.hermeslive.meeting

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.folio.hermes.telemetry.TelemetrySink
import com.folio.hermeslive.cascade.TestRuntime
import com.folio.hermeslive.cascade.appRuntime
import com.folio.hermeslive.data.CascadeEnvironment
import com.folio.hermeslive.data.CascadeFactory
import com.folio.hermeslive.data.CascadeShell
import com.folio.hermeslive.data.CascadeStorage
import com.folio.hermeslive.data.CascadeDirs
import com.folio.hermeslive.data.KeyCheck
import com.folio.hermeslive.data.SpeechModels
import com.folio.hermeslive.data.TestShell
import com.folio.hermeslive.data.VoiceEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Meeting mode sends its notes through the shared task API: `MeetingHost`'s `tasks()` provider is
 * `shell.appRuntime()?.core?.tasks`. It is there with each engine's task path open: Grok with its
 * xAI key, Custom with its OpenRouter key, GPT-Live with parallel agents while signed in.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MeetingEngineTasksTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After fun tearDown() = scope.cancel()

    /** The process's runtime as `VoiceShell.shell` builds it (a real `CascadeAppRuntime`), gated by [t]'s real gate. */
    private fun provider(t: TestShell): () -> Any? {
        val real = TestRuntime(scope)
        val shell = CascadeShell(t.shell.gate, CascadeFactory { real.runtime }) {
            val tmp = kotlin.io.path.createTempDirectory("meeting").toFile()
            CascadeEnvironment({ com.folio.hermes.cascade.CascadeSettings() }, { null }, CascadeStorage(CascadeDirs(tmp, tmp)), SpeechModels.None, TelemetrySink.None)
        }
        return { shell.appRuntime()?.core?.tasks }
    }

    @Test fun grokWithItsKeyHasTheTaskApi() {
        val t = TestShell(app, "meeting-grok", scope)
        t.settings.update { it.copy(voiceEngine = VoiceEngine.Grok) }
        t.xaiKeys.save(FAKE_KEY, KeyCheck.Valid)
        assertNotNull(provider(t)())
    }

    @Test fun customWithItsKeyHasTheTaskApi() {
        val t = TestShell(app, "meeting-custom", scope)
        t.settings.update { it.copy(voiceEngine = VoiceEngine.Cascade) }
        t.keys.save(FAKE_KEY, KeyCheck.Valid)
        assertNotNull(provider(t)())
    }

    @Test fun gptLiveSignedInHasTheTaskApiThroughItsAgents() {
        val t = TestShell(app, "meeting-gpt", scope)
        t.signedIn = true
        assertNotNull(provider(t)())
    }

    @Test fun controlNoTaskPathNoTaskApi() {
        val grokNoKey = TestShell(app, "meeting-grok-nokey", scope).apply { settings.update { it.copy(voiceEngine = VoiceEngine.Grok) } }
        assertNull(provider(grokNoKey)())
        assertNull("GPT-Live signed out", provider(TestShell(app, "meeting-gpt-out", scope))())
    }

    private companion object {
        const val FAKE_KEY = "sk-test-not-a-real-key-000000000000"
    }
}
