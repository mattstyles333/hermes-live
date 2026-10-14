package com.folio.hermeslive.cascade

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.folio.hermes.cascade.DecisionRecord
import com.folio.hermes.cascade.Rotation
import com.folio.hermes.cascade.store.StoreKeys
import com.folio.hermeslive.data.KeyCheck
import com.folio.hermeslive.data.LiveSettings
import com.folio.hermeslive.data.TestShell
import com.folio.hermeslive.data.VoiceEngine
import com.folio.hermeslive.data.VoiceShell
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Hub decision (2026-10-14): Bill's account-bound state never reaches another account's server.
 * A Hermes sign-out wipes every `cascade.*` store key, the store's logs and quarantine, and the
 * decision log; it keeps the OpenRouter key, the speech models, the TTS cache and the rotations.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CascadeSignOutTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher())
    private val t = TestShell(context, "signout", scope)
    private val shell: VoiceShell get() = t.shell

    @After fun tearDown() {
        scope.cancel()
        shell.dirs.root.deleteRecursively()
    }

    private val modelFile get() = File(shell.dirs.models, "zipformer/tokens.txt")
    private val ttsFile get() = File(shell.dirs.tts, "clip.pcm")

    /** A store and files as a few calls would leave them, plus the things that must stay. */
    private fun billHasHistory() {
        t.keys.save("sk-or-v1-" + "0".repeat(64), KeyCheck.Valid)
        t.settings.update { it.copy(voiceEngine = VoiceEngine.Cascade, decisionLog = true) }
        val store = shell.storage.store
        store.put(StoreKeys.CORE, """{"outbox":"a held memo"}""")
        store.put(StoreKeys.task("t1"), """{"storedSessionId":"s-old-account"}""")
        store.put(StoreKeys.conversation("c1"), """{"summary":"an old talk"}""")
        store.put(StoreKeys.CALL_START, "1")
        store.put(VoiceShell.PREPARED_KEY, "fingerprint")
        store.put(CascadeSignOut.OWNER_KEY, CascadeSignOut.owner("https://a.ts.net", "anna"))
        store.append("events", "one line")
        File(shell.dirs.store, "corrupt").apply { mkdirs() }.resolve("1-old.kv").writeText("old value")
        shell.decisionLog.record(DecisionRecord("u1", System.currentTimeMillis(), "done"))
        shell.storage.rotations.save("ack.do", Rotation(listOf("Got it.", "On it."), 1))
        modelFile.apply { parentFile!!.mkdirs() }.writeText("tokens")
        ttsFile.apply { parentFile!!.mkdirs() }.writeText("pcm")
        assertTrue(store.keys("cascade.").size >= 6)
        assertEquals(1, shell.decisionLog.records().size)
    }

    @Test fun signOutWipesBillsAccountStateAndClosesHisRuntime() {
        billHasHistory()
        val runtime = shell.shell.runtime()!!
        CascadeSignOut.apply(shell)

        assertTrue("the runtime is closed before the wipe", t.host.cascadeFactory.built.single().closed)
        assertTrue(runtime === t.host.cascadeFactory.built.single())
        val store = shell.storage.store
        assertEquals("no cascade.* key is left", emptyList<String>(), store.keys(""))
        assertTrue("logs gone", store.read("events").isEmpty())
        assertTrue("quarantine gone", File(shell.dirs.store, "corrupt").listFiles().orEmpty().isEmpty())
        assertTrue("decision log gone", shell.decisionLog.records().isEmpty())
        assertFalse(shell.dirs.decisions.exists())
    }

    @Test fun signOutKeepsTheKeyTheModelsTheVoiceCacheAndTheRotations() {
        billHasHistory()
        CascadeSignOut.apply(shell)

        assertFalse("the user's decision is pending", CascadeSignOut.WIPE_OPENROUTER_KEY)
        assertTrue("OpenRouter key kept", t.keys.status.value.saved)
        assertEquals("tokens", modelFile.readText())
        assertEquals("pcm", ttsFile.readText())
        assertEquals(Rotation(listOf("Got it.", "On it."), 1), shell.storage.rotations.load("ack.do"))
        assertEquals(VoiceEngine.Cascade, t.settings.settings.value.voiceEngine)
    }

    @Test fun anotherAccountSigningInWithoutASignOutGetsAWipedStore() {
        billHasHistory()
        assertFalse("same account: kept", CascadeSignOut.ownedBy(shell, "https://a.ts.net", "anna"))
        assertTrue(shell.storage.store.get(StoreKeys.task("t1")) != null)

        assertTrue("another user on the same server", CascadeSignOut.ownedBy(shell, "https://a.ts.net", "ben"))
        val store = shell.storage.store
        assertEquals(listOf(CascadeSignOut.OWNER_KEY), store.keys("cascade."))
        assertEquals(CascadeSignOut.owner("https://a.ts.net", "ben"), store.get(CascadeSignOut.OWNER_KEY))
        assertTrue(shell.decisionLog.records().isEmpty())
        assertTrue("key kept", t.keys.status.value.saved)
        assertEquals("pcm", ttsFile.readText())

        assertTrue("another server", CascadeSignOut.ownedBy(shell, "https://b.ts.net", "ben"))
        assertFalse("the owner is a hash, never the names", store.get(CascadeSignOut.OWNER_KEY)!!.contains("ben"))
    }

    @Test fun aStoreWithNoOwnerYetIsAdoptedNotWiped() {
        shell.storage.store.put(StoreKeys.task("t1"), "{}")
        assertFalse(CascadeSignOut.ownedBy(shell, "https://a.ts.net", "anna"))
        assertEquals("{}", shell.storage.store.get(StoreKeys.task("t1")))
        assertTrue(LiveSettings().voiceEngine == VoiceEngine.GptLive)
    }
}
