package com.folio.hermeslive.service

import android.Manifest
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.folio.hermeslive.data.CascadeGate
import com.folio.hermeslive.data.KeyCheck
import com.folio.hermeslive.data.KeyStatus
import com.folio.hermeslive.data.LiveSettings
import com.folio.hermeslive.data.VoiceEngine
import com.folio.hermeslive.lab.SttLabActivity
import com.folio.hermeslive.probe.ProbeHolderService
import com.folio.hermeslive.probe.ProbeTargetService
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Spec v2 §14 item 14 on the emulator: after hang-up the off-call service starts while the call's
 * microphone service is still foreground, but the user may have turned the screen off long
 * before. Does Android allow that start from the background? And `OffCall.afterHangUp` must
 * handle a refusal without crashing (it returns false; result notifications remain).
 *
 * The probe: a microphone service started while an activity was visible (the call), the
 * activity closed, the screen off, 15 s past any grace period; then a specialUse service is
 * started the way `OffCall.afterHangUp` does. Records `<external files>/stt-bench/offcall.json`.
 * Runs only with `offcall=true` (about 25 s).
 */
@RunWith(AndroidJUnit4::class)
class OffCallBackgroundStartTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private fun shell(cmd: String) = instrumentation.uiAutomation.executeShellCommand(cmd).close()

    @Test fun offCallStartFromTheBackgroundWithTheScreenOff() {
        org.junit.Assume.assumeTrue("offcall=true runs it", InstrumentationRegistry.getArguments().getString("offcall") == "true")
        val pkg = context.packageName
        shell("pm grant $pkg ${Manifest.permission.RECORD_AUDIO}")
        shell("pm grant $pkg ${Manifest.permission.POST_NOTIFICATIONS}")
        shell("input keyevent KEYCODE_WAKEUP")
        val scenario = ActivityScenario.launch(SttLabActivity::class.java)
        scenario.onActivity { ContextCompat.startForegroundService(it, Intent(it, ProbeHolderService::class.java)) }
        Thread.sleep(1_000)
        scenario.close()
        shell("input keyevent KEYCODE_SLEEP")
        Thread.sleep(15_000)

        ProbeTargetService.outcome.set(null)
        val direct = runCatching {
            ContextCompat.startForegroundService(context, Intent(context, ProbeTargetService::class.java))
            "started"
        }.getOrElse { "refused_at_start:" + it.javaClass.simpleName }
        Thread.sleep(2_000)
        val inService = ProbeTargetService.outcome.get() ?: "never_ran"

        // The app's own path: refusal must come back as false, never a crash.
        val gate = CascadeGate({ LiveSettings(voiceEngine = VoiceEngine.Cascade) }, { KeyStatus(saved = true, check = KeyCheck.Valid) })
        val afterHangUp = runCatching { OffCall.afterHangUp(context, tasksRunning = 1, gate = gate) }
        Thread.sleep(1_000)
        context.startService(Intent(context, OffCallService::class.java).setAction(OffCallService.ACTION_STOP))
        context.startService(Intent(context, ProbeHolderService::class.java).setAction(ProbeHolderService.STOP))
        shell("input keyevent KEYCODE_WAKEUP")

        val row = JSONObject()
            .put("sdk", android.os.Build.VERSION.SDK_INT)
            .put("probe_start", direct)
            .put("probe_start_foreground", inService)
            .put("after_hang_up", afterHangUp.getOrNull())
            .put("after_hang_up_threw", afterHangUp.exceptionOrNull()?.javaClass?.simpleName)
        File(context.getExternalFilesDir(null), "stt-bench").apply { mkdirs() }.resolve("offcall.json").writeText(row.toString(2))
        android.util.Log.i("HermesProbe", "OFFCALL_PROBE $row")
        assertTrue("afterHangUp never throws", afterHangUp.isSuccess)
    }
}
