package com.folio.hermeslive.data

import com.folio.hermes.cascade.BillVoice
import com.folio.hermes.cascade.MemoryClipStore
import com.folio.hermes.cascade.PhraseBank
import com.folio.hermes.cascade.PhraseRenderer
import com.folio.hermes.cascade.PhraseSlot
import com.folio.hermes.cascade.TtsCache
import com.folio.hermes.cascade.ports.MemoryStore
import com.folio.hermes.openrouter.OpenRouter
import com.folio.hermes.openrouter.OpenRouterConfig
import com.folio.hermes.telemetry.TelemetrySink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM: "Prepare Bill's voice" over a fake OpenRouter and fake models. */
class VoicePrepTest {
    private val server = FakeOpenRouterServer()
    private val bank = PhraseBank(3, emptyMap(), listOf(PhraseSlot("ack.ok", "ack", "use", listOf("On it.", "Got it.", "Sure."))))
    private var settings = LiveSettings(voiceEngine = VoiceEngine.Cascade)
    private var key = KeyStatus(true, KeyCheck.Valid)
    private val gate = CascadeGate({ settings }, { key })
    private val store = MemoryStore()
    private val cache = TtsCache(MemoryClipStore())
    private val marker = object : VoicePrep.PreparedMarker {
        override fun get(): String? = store.get("p")
        override fun set(fingerprint: String) = store.put("p", fingerprint)
    }

    private fun fingerprint() = VoicePrep.fingerprint(settings.billVoice, settings.ttsModel, bank.version)

    private fun prep(scope: TestScope, models: SpeechModels, renders: () -> RenderJob = ::render) =
        VoicePrep(scope, gate, models, { settings.highAccuracySpeech }, { bank }, renders, marker, ::fingerprint, TelemetrySink.None)

    private fun render(): RenderJob {
        val openRouter = OpenRouter(OpenRouterConfig(baseUrl = "https://openrouter.example/api/v1"), key = { FAKE_KEY }, client = server.client(), telemetry = TelemetrySink.None)
        val voice = BillVoice(openRouter.speech, cache, { settings.billVoice }, { settings.ttsModel })
        return RenderJob(PhraseRenderer(voice, 4, TelemetrySink.None), fingerprint())
    }

    private fun models() = FakeSpeechModels(
        listOf(
            SpeechModelStatus("zipformer", "Streaming", ModelRole.Streaming, ModelState.Missing, 0, 73_000_000),
            SpeechModelStatus("parakeet-110m", "Accurate", ModelRole.Accurate, ModelState.Paused, 10_000_000, 108_000_000),
            SpeechModelStatus("parakeet-0.6b", "High accuracy", ModelRole.HighAccuracy, ModelState.Missing, 0, 661_000_000),
            SpeechModelStatus("silero", "VAD", ModelRole.Vad, ModelState.Ready, 600_000, 600_000),
        ),
    )

    private fun TestScope.waitFor(p: VoicePrep) {
        val deadline = System.currentTimeMillis() + 10_000
        while (p.state.value.phase == PrepPhase.Running && System.currentTimeMillis() < deadline) {
            advanceUntilIdle()
            Thread.sleep(5)
        }
    }

    @Test fun rendersTheBankAndFetchesTheWantedModels() {
        val scope = TestScope(StandardTestDispatcher())
        val models = models()
        val p = prep(scope, models)
        scope.runCurrent()
        assertFalse(p.state.value.ready)
        assertEquals("bytes still to fetch, high accuracy excluded", 73_000_000L + 98_000_000L, p.state.value.bytesToFetch)
        assertTrue(p.start())
        assertFalse("one run at a time", p.start())
        scope.waitFor(p)
        val s = p.state.value
        assertEquals(PrepPhase.Done, s.phase)
        assertEquals(3, s.phrases.total)
        assertEquals(3, s.phrases.done)
        assertTrue(s.ready)
        assertEquals("high accuracy is off: two models fetched", 2, models.downloads.get())
        assertTrue(s.models.none { it.role == ModelRole.HighAccuracy })
        settings = settings.copy(highAccuracySpeech = true)
        p.refresh()
        assertEquals(661_000_000L, p.state.value.bytesToFetch)
        settings = settings.copy(highAccuracySpeech = false)
        p.refresh()
        assertEquals(3, server.requests.size)

        // Again: everything is cached, nothing is fetched.
        assertTrue(p.start())
        scope.waitFor(p)
        assertEquals(3, server.requests.size)
        assertEquals(3, p.state.value.phrases.cached)
    }

    @Test fun aNewVoiceNeedsPreparingAgain() {
        val scope = TestScope(StandardTestDispatcher())
        val p = prep(scope, SpeechModels.None)
        p.start()
        scope.waitFor(p)
        assertTrue(p.state.value.ready)
        settings = settings.copy(billVoice = "george")
        p.refresh()
        assertFalse(p.state.value.ready)
    }

    @Test fun cancelStopsAndStartResumes() {
        val scope = TestScope(StandardTestDispatcher())
        val gateOpen = CompletableDeferred<Unit>()
        val slow = object : SpeechModels {
            private val s = MutableStateFlow(listOf(SpeechModelStatus("zipformer", "Streaming", ModelRole.Streaming, ModelState.Missing)))
            override val status: StateFlow<List<SpeechModelStatus>> = s.asStateFlow()
            var calls = 0
            override suspend fun download(id: String): Boolean {
                calls++
                if (calls == 1) gateOpen.await() // the first attempt hangs until cancelled
                s.value = listOf(s.value.single().copy(state = ModelState.Ready))
                return true
            }
            override fun delete(id: String) = Unit
        }
        val p = prep(scope, slow)
        assertTrue(p.start())
        scope.runCurrent()
        assertEquals(PrepPhase.Running, p.state.value.phase)
        p.cancel()
        scope.waitFor(p)
        assertEquals(PrepPhase.Cancelled, p.state.value.phase)
        assertTrue("resume", p.start())
        scope.waitFor(p)
        assertEquals(PrepPhase.Done, p.state.value.phase)
        assertEquals(2, slow.calls)
    }

    @Test fun offStateBuildsNoRender() {
        val scope = TestScope(StandardTestDispatcher())
        var built = 0
        settings = settings.copy(voiceEngine = VoiceEngine.GptLive)
        val p = prep(scope, models()) { built++; render() }
        assertFalse(p.start())
        key = KeyStatus()
        settings = settings.copy(voiceEngine = VoiceEngine.Cascade)
        assertFalse(p.start())
        scope.advanceUntilIdle()
        assertEquals(0, built)
        assertTrue(server.requests.isEmpty())
    }

    @Test fun engineStatusSaysWhatIsMissing() {
        val prepared = PrepState(ready = true)
        assertEquals(EngineState.GptLive, EngineStatus.of(LiveSettings(), KeyStatus(true, KeyCheck.Valid), prepared).state)
        val cascade = LiveSettings(voiceEngine = VoiceEngine.Cascade)
        assertEquals(EngineState.NeedsKey, EngineStatus.of(cascade, KeyStatus(), prepared).state)
        assertEquals(EngineState.KeyRefused, EngineStatus.of(cascade, KeyStatus(true, KeyCheck.Refused), prepared).state)
        assertEquals(EngineState.NeedsVoice, EngineStatus.of(cascade, KeyStatus(true, KeyCheck.Valid), PrepState()).state)
        val missingModel = PrepState(ready = true, models = models().status.value)
        assertEquals(EngineState.NeedsVoice, EngineStatus.of(cascade, KeyStatus(true, KeyCheck.Valid), missingModel).state)
        val ready = EngineStatus.of(cascade, KeyStatus(true, KeyCheck.Unchecked), prepared)
        assertEquals(EngineState.Ready, ready.state)
        assertTrue(ready.text.contains("hasn't been checked"))
        assertTrue(EngineStatus(EngineState.NeedsVoice, bytesToFetch = 180_000_000).text.contains("172 MB"))
    }

    @Test fun vocabularyLearnsCorrectionsAndFeedsHotwords() {
        var now = 1L
        val v = VocabularyStore({ store }, { now++ })
        v.learn("jeff", "Jev")
        v.learn("Jeff", "Jev")
        v.learn("in vintry", "InvenTree")
        v.learn("same", "same")
        v.learn(" ", "x")
        val list = v.corrections()
        assertEquals(listOf("in vintry", "Jeff"), list.map { it.heard })
        assertEquals(2, list.single { it.meant == "Jev" }.count)
        assertEquals(listOf("Spex4Less", "inventree", "Jev"), v.hotwords(listOf("Spex4Less", "inventree")))
        v.forget("JEFF")
        assertEquals(listOf("in vintry"), v.corrections().map { it.heard })
        v.clear()
        assertTrue(v.corrections().isEmpty())
    }
}
