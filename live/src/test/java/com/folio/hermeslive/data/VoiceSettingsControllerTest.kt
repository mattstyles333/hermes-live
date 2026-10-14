package com.folio.hermeslive.data

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.folio.hermes.cascade.DecisionRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class VoiceSettingsControllerTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After fun tearDown() = scope.cancel()

    /**
     * Waits for the key check to finish with the outcome [done] describes. The controller sets
     * `keyBusy = false` and the final message in one update, after it saved the key, cleared the
     * clipboard and told the shell, so the message is the sync point (no sleep, no poll).
     */
    private fun VoiceSettingsController.settle(done: (VoiceUi) -> Boolean) {
        val ui = runBlocking { withTimeoutOrNull(5_000) { ui.first { !it.keyBusy && done(it) } } }
        assertNotNull("the key check finished (last: ${this.ui.value.keyMessage})", ui)
    }

    @Test fun aValidKeyIsCheckedThenSavedAndTheClipboardCleared() {
        val t = TestShell(app, "ctl-valid", scope)
        val c = VoiceSettingsController(t.shell, scope)
        var cleared = false
        c.pasteKey(" $FAKE_KEY ") { cleared = true }
        c.settle { it.keyMessage == "Saved and checked." }
        assertEquals(KeyStatus(true, KeyCheck.Valid, "fake"), t.keys.status.value)
        assertEquals(FAKE_KEY, t.keys.key())
        assertTrue(cleared)
        assertEquals("Saved and checked.", c.ui.value.keyMessage)
        assertEquals("/api/v1/key", t.server.requests.single().url.encodedPath)
    }

    @Test fun aRefusedKeyIsNotSaved() {
        val t = TestShell(app, "ctl-refused", scope)
        t.server.keyStatus = 401
        val c = VoiceSettingsController(t.shell, scope)
        var cleared = false
        c.pasteKey(FAKE_KEY) { cleared = true }
        c.settle { it.keyMessage?.contains("refused") == true }
        assertFalse(t.keys.status.value.saved)
        assertFalse(cleared)
        assertTrue(c.ui.value.keyMessage!!.contains("refused"))
    }

    @Test fun anUnreachableCheckSavesTheKeyUnchecked() {
        val t = TestShell(app, "ctl-unreachable", scope)
        t.server.keyStatus = 503
        val c = VoiceSettingsController(t.shell, scope)
        c.pasteKey(FAKE_KEY)
        c.settle { it.keyMessage?.startsWith("Saved. OpenRouter couldn't be reached") == true }
        assertEquals(KeyCheck.Unchecked, t.keys.status.value.check)
        assertTrue(t.keys.hasKey())
        t.server.keyStatus = 200
        c.recheckKey()
        c.settle { it.keyMessage == "OpenRouter accepts the key." }
        assertEquals(KeyCheck.Valid, t.keys.status.value.check)
        c.removeKey()
        assertFalse(t.keys.status.value.saved)
    }

    @Test fun clipboardTextThatIsNotAKeyIsExplainedAndNeverSent() {
        val t = TestShell(app, "ctl-bad", scope)
        val c = VoiceSettingsController(t.shell, scope)
        c.pasteKey("hello there, this is not a key")
        assertTrue(c.ui.value.keyMessage!!.contains("spaces"))
        c.pasteKey(null)
        assertTrue(c.ui.value.keyMessage!!.contains("empty"))
        assertTrue(t.server.requests.isEmpty())
    }

    @Test fun theDecisionLogIsSharedAsOneJsonFileAndCleared() {
        val t = TestShell(app, "ctl-log", scope)
        val c = VoiceSettingsController(t.shell, scope)
        assertNull("nothing to share yet", runBlocking { c.shareDecisionLog() })
        t.settings.update { it.copy(voiceEngine = VoiceEngine.Cascade) }
        t.shell.decisionLog.record(DecisionRecord("turn-1", System.currentTimeMillis(), "done", route = "reply"))
        val chooser = runBlocking { c.shareDecisionLog() }
        assertNotNull(chooser)
        val send = chooser!!.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("application/json", send.type)
        val uri = send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)!!
        assertEquals(DecisionLogShare.URI, uri)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val exported = DecisionLogShare.file(t.shell.dirs)
        assertTrue(exported.readText().contains("turn-1"))

        // The provider serves exactly that file, read-only.
        val provider = Robolectric.setupContentProvider(DecisionLogProvider::class.java, DecisionLogShare.AUTHORITY)
        assertEquals("application/json", provider.getType(uri))

        c.clearDecisionLog()
        // Set after the log and the exported file are gone.
        assertNotNull(runBlocking { withTimeoutOrNull(5_000) { c.ui.first { it.message == "Decision log cleared." } } })
        assertFalse(exported.exists())
        assertTrue(t.shell.decisionLog.records().isEmpty())
    }

    @Test fun theDecisionLogSwitchStopsRecording() {
        val t = TestShell(app, "ctl-log-off", scope)
        t.settings.update { it.copy(decisionLog = false) }
        t.shell.decisionLog.record(DecisionRecord("turn-1", System.currentTimeMillis(), "done"))
        assertTrue(t.shell.decisionLog.records().isEmpty())
    }
}
