package com.folio.hermeslive.smoke

import android.Manifest
import android.view.WindowInsets
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.folio.hermeslive.LiveActivity
import com.folio.hermeslive.audio.CascadeAudioFactory
import com.folio.hermeslive.audio.CascadeAudioIo
import com.folio.hermeslive.call.CallPhase
import com.folio.hermeslive.call.CallRequest
import com.folio.hermeslive.data.KeyCheck
import com.folio.hermeslive.data.SignInResult
import com.folio.hermeslive.data.VoiceEngine
import com.folio.hermeslive.data.VoiceShell
import com.folio.hermeslive.graph
import com.folio.hermeslive.hooks.BuildHooks
import com.folio.hermeslive.hooks.CapturingAudioOut
import com.folio.hermeslive.service.CallService
import com.folio.hermeslive.stt.CorpusAudioSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Bill's end-call transition on the device, for a screen recording (opt-in: `-e endcall true`).
 * The dry-run setup of [BillSmokeTest] (the fake Hermes, a placeholder key, `openrouter.ai`
 * refused, silence in, a capturing out): a Bill call starts, then the End button is tapped
 * (`input tap`) and the screen is left up for its end and the crossfade home, while
 * `screenrecord` writes `/sdcard/Download/bill-endcall.mp4` (8 s).
 *
 * `am instrument -w -e endcall true -e class com.folio.hermeslive.smoke.EndCallDemoTest com.folio.hermeslive.test/androidx.test.runner.AndroidJUnitRunner`
 */
@RunWith(AndroidJUnit4::class)
class EndCallDemoTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val args get() = InstrumentationRegistry.getArguments()

    private fun shell(cmd: String) = instrumentation.uiAutomation.executeShellCommand(cmd).close()

    private fun waitFor(what: String, timeoutMs: Long, check: () -> Boolean) {
        val t0 = System.nanoTime()
        while (!check()) {
            if (System.nanoTime() - t0 > timeoutMs * 1_000_000) throw AssertionError("timed out: $what")
            Thread.sleep(25)
        }
    }

    @Test fun endABillCall() {
        assumeTrue("opt-in: -e endcall true", args.getString("endcall") == "true")
        BuildHooks.hosts = mapOf("openrouter.ai" to "127.0.0.1")
        val g = context.graph
        val shell = VoiceShell.of(context)
        try {
            shell("pm grant ${context.packageName} ${Manifest.permission.RECORD_AUDIO}")
            shell("pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
            shell("input keyevent KEYCODE_WAKEUP")
            shell("wm dismiss-keyguard")
            runBlocking { g.ready.await() }
            val signIn = runBlocking { g.account.signIn(args.getString("hermesUrl") ?: "http://10.0.2.2:18119", "fake", "fake-password") }
            assertTrue("signed in to the fake Hermes: $signIn", signIn is SignInResult.Ok)
            shell.shell.release()
            shell.keys.save(DRY_KEY, KeyCheck.Valid)
            g.settings.update { it.copy(voiceEngine = VoiceEngine.Cascade, startOnOpen = false) }
            assertTrue("gate open", shell.gate.active)
            BuildHooks.cascadeAudio = CascadeAudioFactory { _, _ -> CapturingAudioOut().let { out -> CascadeAudioIo(CorpusAudioSource(pace = 1.0), out, out) } }

            val scenario = ActivityScenario.launch(LiveActivity::class.java)
            scenario.onActivity { CallService.start(it, CallRequest(entry = "endcall-demo")) }
            waitFor("call live", 30_000) { g.calls.state.value.phase == CallPhase.Live }
            Thread.sleep(1_500)
            instrumentation.uiAutomation.executeShellCommand("screenrecord --time-limit 8 /sdcard/Download/bill-endcall.mp4")
            Thread.sleep(1_500)
            // A real tap on the End button: centred, 22 dp + half its 58 dp above the bottom inset.
            var tap = ""
            scenario.onActivity { act ->
                val decor = act.window.decorView
                val bottom = decor.rootWindowInsets.getInsets(WindowInsets.Type.systemBars()).bottom
                val dp = act.resources.displayMetrics.density
                tap = "input tap ${decor.width / 2} ${(decor.height - bottom - 51 * dp).toInt()}"
            }
            shell(tap)
            waitFor("call ended", 5_000) { !g.calls.state.value.inCall }
            Thread.sleep(6_000)
            scenario.close()
        } finally {
            runCatching { instrumentation.runOnMainSync { g.calls.end() } }
            BuildHooks.reset()
            shell.keys.remove()
            g.settings.update { it.copy(voiceEngine = VoiceEngine.GptLive, startOnOpen = true) }
            shell.onSettingsChanged()
            runBlocking { g.account.signOut() }
        }
    }

    private companion object {
        /** A placeholder OpenRouter key (no request leaves: `openrouter.ai` is refused). */
        const val DRY_KEY = "sk-or-v1-dryrun000000000000000000000000000000000000000000000000000000"
    }
}
