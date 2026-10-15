package com.folio.hermeslive.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.folio.hermes.live.HermesVoiceState
import com.folio.hermes.openrouter.OpenRouterResult
import com.folio.hermes.telemetry.TelemetrySink
import com.folio.hermeslive.cascade.CascadeSignOut
import com.folio.hermeslive.stt.OpenRouterCloudStt
import com.folio.hermeslive.stt.SourcedCloudStt
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The Hermes voice slice on the real app shell (fake Hermes and provider on loopback): the off
 * state, the defaults in action, the fallbacks, the re-render on a voice change, and the provider
 * key never leaving memory.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class HermesVoiceShellTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val fake = FakeHermesVoiceServer()
    private val raw = CopyOnWriteArrayList<String>()
    private val rawSink = TelemetrySink { name, attrs, _ -> raw += "$name $attrs" }

    @After fun tearDown() {
        scope.cancel()
        fake.close()
    }

    private fun shell(name: String, custom: Boolean = true, signedIn: Boolean = true, key: Boolean = true): TestShell =
        TestShell(app, name, scope, sink = SafeTelemetry(rawSink)).also { t ->
            t.hermes = fake
            t.signedIn = signedIn
            t.network.metered = false
            if (key) t.keys.save(FAKE_KEY, KeyCheck.Valid)
            if (custom) t.settings.update { it.copy(voiceEngine = VoiceEngine.Cascade) }
        }

    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }

    private fun cloudStt(t: TestShell) = SourcedCloudStt(
        settings = { t.settings.settings.value },
        hermes = t.shell.hermesVoices,
        openRouter = OpenRouterCloudStt(com.folio.hermes.openrouter.OpenRouter(com.folio.hermes.openrouter.OpenRouterConfig(baseUrl = "https://openrouter.example/api/v1"), key = t.keys::key, client = t.server.client()).transcription) { true },
        openRouterUsable = { t.keys.status.value.usable },
        online = { true },
    )

    // ── off state ──

    @Test fun gptLiveSignedInFetchesNothing() {
        val t = shell("hv-gpt", custom = false)
        t.shell.hermesVoices.voice()
        await { t.shell.hermesVoices.refresh() }
        VoiceSettingsController(t.shell, scope).hermes().refresh()
        assertEquals(0, fake.audioRequests)
        assertNull(t.shell.hermesVoices.cached())
    }

    @Test fun signedOutCustomFetchesNothingAndOpenRouterSpeaks() {
        val t = shell("hv-out", signedIn = false)
        assertEquals(VoiceRoute.OpenRouter, t.shell.voiceRoute())
        val voice = t.shell.routedVoice(com.folio.hermes.openrouter.OpenRouter(com.folio.hermes.openrouter.OpenRouterConfig(baseUrl = "https://openrouter.example/api/v1"), key = t.keys::key, client = t.server.client()).speech)
        assertTrue(await { voice.billVoice(t.shell.storage.ttsCache(1_000_000)).clip("Got it.") } is OpenRouterResult.Ok)
        assertEquals(0, fake.audioRequests)
        assertTrue(t.server.requests.any { it.url.encodedPath.endsWith("/audio/speech") })
    }

    @Test fun anExistingOpenRouterUserFetchesNothingFromHermes() {
        val t = shell("hv-or")
        t.settings.update { it.copy(voiceFrom = SourceChoice.OpenRouter) }
        assertFalse(t.shell.hermesVoiceWanted(t.settings.settings.value))
        await { t.shell.hermesVoices.refresh() }
        assertEquals(0, fake.audioRequests)
    }

    @Test fun onDeviceSpeechSendsNoAudioAnywhere() {
        val t = shell("hv-device")
        fake.configBody = fake.direct()
        await { t.shell.hermesVoices.refresh() }
        assertNull("no cloud model: the second pass never uploads", t.settings.settings.value.cascadeSettings().cloudSttModel)
        assertEquals(SttRoute.OnDevice, cloudStt(t).route())
        assertEquals(0, fake.transcribeCalls.get())
        assertTrue(fake.providerRequests.isEmpty())
    }

    // ── defaults and routes ──

    @Test fun aNewUserGetsHermessVoiceWhenSignedIn() {
        val t = shell("hv-new")
        assertEquals(SourceChoice.Auto, t.settings.settings.value.voiceFrom)
        await { t.shell.hermesVoices.refresh() }
        assertEquals(VoiceRoute.HermesRelay, t.shell.voiceRoute())
        fake.configBody = fake.direct()
        await { t.shell.hermesVoices.refresh() }
        assertEquals(VoiceRoute.HermesDirect, t.shell.voiceRoute())
    }

    @Test fun cloudSpeechGoesToHermesThenFallsBackToOpenRouter() {
        val t = shell("hv-stt")
        t.settings.update { it.copy(speechRecognition = SpeechRecognition.Cloud) }
        val stt = cloudStt(t)
        await { t.shell.hermesVoices.refresh() }
        assertEquals(SttRoute.HermesRelay, stt.route())
        assertEquals("hello from hermes", await { stt.transcribe(byteArrayOf(1, 2), "assemblyai/universal-3-pro", listOf("Kestrel"), 4_000) })
        assertEquals(1, fake.transcribeCalls.get())
        assertFalse("no OpenRouter upload", t.server.requests.any { it.url.encodedPath.endsWith("/audio/transcriptions") })
        fake.hermesCode = 502
        await { stt.transcribe(byteArrayOf(1, 2), "assemblyai/universal-3-pro", emptyList(), 4_000) }
        assertTrue("OpenRouter took it", t.server.requests.any { it.url.encodedPath.endsWith("/audio/transcriptions") })
    }

    @Test fun hermesUnreachableSpeaksThroughOpenRouter() {
        val t = shell("hv-down")
        fake.configBody = fake.direct()
        await { t.shell.hermesVoices.refresh() }
        fake.providerCode = 503
        val voice = t.shell.routedVoice(com.folio.hermes.openrouter.OpenRouter(com.folio.hermes.openrouter.OpenRouterConfig(baseUrl = "https://openrouter.example/api/v1", retryBackoffMs = 1), key = t.keys::key, client = t.server.client()).speech)
        assertTrue(await { voice.billVoice(t.shell.storage.ttsCache(1_000_000)).clip("Got it.") } is OpenRouterResult.Ok)
        assertTrue(t.server.requests.any { it.url.encodedPath.endsWith("/audio/speech") })
    }

    @Test fun aHangingHermesNeverHoldsTheFirstLines() {
        val t = shell("hv-hang")
        fake.hang = java.util.concurrent.CountDownLatch(1)
        val appStart = CoroutineScope(Dispatchers.IO).launch { t.shell.hermesVoices.refresh() }
        assertTrue(fake.configAsked.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val voice = t.shell.routedVoice(com.folio.hermes.openrouter.OpenRouter(com.folio.hermes.openrouter.OpenRouterConfig(baseUrl = "https://openrouter.example/api/v1"), key = t.keys::key, client = t.server.client()).speech)
        assertEquals(VoiceRoute.OpenRouter, voice.route())
        val t0 = System.nanoTime()
        assertTrue(await { voice.billVoice(t.shell.storage.ttsCache(1_000_000)).clip("Got it.") } is OpenRouterResult.Ok)
        val waited = (System.nanoTime() - t0) / 1_000_000
        assertTrue("the first line waited $waited ms", waited < 2_000)
        assertTrue("OpenRouter spoke it", t.server.requests.any { it.url.encodedPath.endsWith("/audio/speech") })
        appStart.cancel()
    }

    // ── re-render on change ──

    @Test fun preparingRendersInHermessVoiceAndAgainWhenTheVoiceChanges() {
        val t = shell("hv-render")
        fake.configBody = fake.direct()
        await { t.shell.hermesVoices.refresh() }
        assertTrue(t.shell.prep.start())
        val first = await { t.shell.prep.state.first { it.phase == PrepPhase.Done } }
        assertTrue(first.ready)
        val voices = { fake.providerRequests.filter { it.url.encodedPath.contains("/text-to-speech/") }.map { it.url.encodedPath.substringAfterLast('/') } }
        assertEquals(setOf("v-bill"), voices().toSet())
        assertFalse("no OpenRouter speech", t.server.requests.any { it.url.encodedPath.endsWith("/audio/speech") })
        val before = voices().size
        val c = VoiceSettingsController(t.shell, scope).hermes()
        await { c.ui.first { it.tts?.mode == "direct" } }
        c.chooseVoice("v-ada")
        await { t.shell.prep.state.first { it.phase == PrepPhase.Done && it.ready && voices().size > before } }
        assertEquals(setOf("v-ada"), voices().drop(before).toSet())
    }

    @Test fun anOfflineStartDoesNotRerenderThroughOpenRouter() {
        val t = shell("hv-offline")
        t.shell.storage.store.put(VoiceShell.PREPARED_KEY, "v-bill|hermes:elevenlabs/eleven_flash_v2_5|" + com.folio.hermes.cascade.PhraseBank.load().version)
        fake.hermesCode = 503
        await { t.shell.hermesVoices.refresh() }
        assertTrue(t.shell.hermesVoices.state.value is HermesVoiceState.Unavailable)
        t.shell.onSettingsChanged()
        assertFalse(t.server.requests.any { it.url.encodedPath.endsWith("/audio/speech") })
        assertTrue("still counts as prepared", t.shell.prep.state.value.ready)
    }

    @Test fun anInstallThatPreparedCustomBeforeKeepsOpenRouterAfterSwitchingEngines() {
        val t = shell("hv-settle", custom = false)
        t.shell.storage.store.put(VoiceShell.PREPARED_KEY, "bill|elevenlabs/eleven-v4-turbo|3")
        assertEquals(SourceChoice.Auto, t.settings.settings.value.voiceFrom)
        t.shell.settleVoiceSource()
        assertEquals(SourceChoice.OpenRouter, t.settings.settings.value.voiceFrom)
        // Control: never prepared, never chose.
        val fresh = shell("hv-settle-new", custom = false)
        fresh.shell.settleVoiceSource()
        assertEquals(SourceChoice.Auto, fresh.settings.settings.value.voiceFrom)
        assertFalse("nothing created to find out", fresh.shell.dirs.root.exists())
    }

    // ── the key stays in memory ──

    @Test fun theProviderKeyIsNeverWrittenOrReported() {
        val t = shell("hv-key")
        t.settings.update { it.copy(speechRecognition = SpeechRecognition.Cloud) }
        fake.configBody = fake.direct()
        await { t.shell.hermesVoices.refresh() }
        assertTrue(t.shell.prep.start())
        await { t.shell.prep.state.first { it.phase == PrepPhase.Done } }
        await { cloudStt(t).transcribe(byteArrayOf(1, 2), "m/x", listOf("Kestrel"), 4_000) }
        await { t.shell.preview.hermes("v-ada") }
        fake.providerCode = 401
        await { t.shell.routedVoice(com.folio.hermes.openrouter.OpenRouter(com.folio.hermes.openrouter.OpenRouterConfig(baseUrl = "https://openrouter.example/api/v1"), key = t.keys::key, client = t.server.client()).speech).billVoice(t.shell.storage.ttsCache(1_000_000)).clip("Another line.") }
        assertTrue("the provider got the key", fake.providerRequests.any { it.headers["xi-api-key"] == LIVE_SENTINEL_KEY })

        val roots = listOf(app.filesDir, app.noBackupFilesDir, app.cacheDir, File(app.applicationInfo.dataDir, "shared_prefs"))
        val files = roots.flatMap { root -> root.walkTopDown().filter { it.isFile }.toList() }
        // The scan saw real data: the rendered clips and the cascade store are on disk.
        assertTrue("TTS clips on disk", t.shell.dirs.tts.listFiles().orEmpty().any { it.name.endsWith(".clip") })
        assertTrue(files.any { it.path.startsWith(t.shell.dirs.root.path) })
        val leaks = files.filter { f -> String(f.readBytes(), Charsets.ISO_8859_1).contains(LIVE_SENTINEL_KEY) }
        assertTrue("written to $leaks", leaks.isEmpty())
        // Preferences as the app reads them (Robolectric may hold them in memory, not in files).
        val prefs = listOf("hv-key-settings", "hv-key-key", "hv-key-xai").map { app.getSharedPreferences(it, android.content.Context.MODE_PRIVATE).all }
        assertTrue("the settings were read", prefs[0].containsKey("cascade_voice_from"))
        prefs.forEach { all -> all.values.forEach { v -> assertFalse("$v", v.toString().contains(LIVE_SENTINEL_KEY)) } }
        assertTrue(raw.isNotEmpty())
        raw.forEach { line ->
            assertFalse(line, line.contains(LIVE_SENTINEL_KEY))
            assertFalse(line, line.contains("fake-provider"))
        }
        assertTrue("voice_source reported", raw.any { it.startsWith("hermes_voice.tts") && it.contains("voice_source=hermes_direct") })

        // As the app signs out: the account goes first, then the cascade's account-bound state.
        t.signedIn = false
        CascadeSignOut.apply(t.shell)
        assertNull("sign-out drops it", t.shell.hermesVoices.cached())
        assertNull(t.shell.hermesVoices.voice())
        assertEquals(HermesVoiceState.Unknown, t.shell.hermesVoices.state.value)
    }
}
