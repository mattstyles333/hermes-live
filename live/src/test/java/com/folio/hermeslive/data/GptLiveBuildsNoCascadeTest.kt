package com.folio.hermeslive.data

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import com.folio.hermeslive.LiveApp
import com.folio.hermeslive.call.CallRequest
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * R-Q-1 on the real app graph: a GPT-Live call (the default engine) while signed out constructs
 * nothing cascade-related: no Bill engine, no cascade runtime (actor, gateway, OpenRouter), no
 * speech models opened, no cascade or model files. GPT-Live's parallel agents are on by default
 * but need a sign-in, so they build nothing here either (`GptLiveAgentsDefaultTest` covers the
 * signed-in side, where a GPT-Live call does use the task runtime).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class GptLiveBuildsNoCascadeTest {
    @Test fun aGptLiveCallBuildsNothingOfBill() {
        val app = ApplicationProvider.getApplicationContext<LiveApp>()
        val g = app.graph
        assertFalse(VoiceShell.of(app).gate.active)
        assertFalse("signed out: GPT-Live's agents stay shut", VoiceShell.of(app).gate.tasksActive)
        g.calls.start(CallRequest(entry = "test"))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("the GPT-Live engine took the call", g.calls.active.value === g.engine)
        assertNull("no Bill engine", g.calls.cascadeEngine)
        assertNull("no cascade runtime", VoiceShell.of(app).shell.existing())
        assertFalse("no cascade files", File(app.noBackupFilesDir, "cascade").exists())
        assertFalse("no model files", File(app.noBackupFilesDir, "stt").exists())
        g.calls.end()
        shadowOf(Looper.getMainLooper()).idle()
    }
}
