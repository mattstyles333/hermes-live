package com.folio.hermeslive.data

import com.folio.hermeslive.stt.SttCatalog

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM: "Prepare Bill's voice" over a fake OpenRouter and fake models. */
class VoicePrepTest {
    private val server = FakeOpenRouterServer()
    private val bank = PhraseBank(3, emptyMap(), listOf(PhraseSlot("ack.ok", "ack", "use", listOf("On it.", "Got it.", "Sure."))))
    private var settings = LiveSettings(voiceEngine = VoiceEngine.Cascade, liveModel = com.folio.hermeslive.stt.SttCatalog.zipformer.id, accurateModel = com.folio.hermeslive.stt.SttCatalog.parakeet110m.id)
    private var key = KeyStatus(true, KeyCheck.Valid)
    private val gate = CascadeGate({ settings }, { key })
    private val store = MemoryStore()
    private val cache = TtsCache(MemoryClipStore())
    private val marker = object : VoicePrep.PreparedMarker {
        override fun get(): String? = store.get("p")
        override fun set(fingerprint: String) = store.put("p", fingerprint)
    }

    private fun fingerprint() = VoicePrep.fingerprint(settings.billVoice, settings.ttsModel, bank.version)

    private fun prep(scope: CoroutineScope, models: SpeechModels, renders: () -> RenderJob = ::render) =
        VoicePrep(scope, gate, models, { SpeechModelChoice.wanted(settings).map { it.id }.toSet() }, { bank }, renders, marker, ::fingerprint, TelemetrySink.None)

    private fun render(): RenderJob {
        val openRouter = OpenRouter(OpenRouterConfig(baseUrl = "https://openrouter.example/api/v1"), key = { FAKE_KEY }, client = server.client(), telemetry = TelemetrySink.None)
        val voice = BillVoice(openRouter.speech, cache, { settings.billVoice }, { settings.ttsModel })
        return RenderJob(PhraseRenderer(voice, 4, TelemetrySink.None), fingerprint())
    }

    private fun models() = FakeSpeechModels(
        listOf(
            SpeechModelStatus(SttCatalog.zipformer.id, "Streaming", ModelRole.Streaming, ModelState.Missing, 0, 73_000_000),
            SpeechModelStatus(SttCatalog.parakeet110m.id, "Accurate", ModelRole.Accurate, ModelState.Paused, 10_000_000, 108_000_000),
            SpeechModelStatus(SttCatalog.parakeet06b.id, "High accuracy", ModelRole.Accurate, ModelState.Missing, 0, 661_000_000),
            SpeechModelStatus("silero", "VAD", ModelRole.Vad, ModelState.Ready, 600_000, 600_000),
        ),
    )

    /*
     * VoicePrep runs on runTest's backgroundScope (the test dispatcher; cancelled at the end, so its
     * endless models collector does not hold the test open). The render's HTTP calls finish on
     * OkHttp's threads; runTest runs every queued task and, when idle, waits for the next dispatch
     * from those threads, so awaiting the state is event-driven. No withTimeout here: on virtual
     * time it would fire at once; runTest's own timeout bounds the wait.
     */
    private suspend fun VoicePrep.settled(): PrepState = state.first { it.phase != PrepPhase.Running }

    @Test fun rendersTheBankAndFetchesTheWantedModels() = runTest {
        val models = models()
        val p = prep(backgroundScope, models)
        runCurrent()
        assertFalse(p.state.value.ready)
        assertEquals("bytes still to fetch, high accuracy excluded", 73_000_000L + 98_000_000L, p.state.value.bytesToFetch)
        assertTrue(p.start())
        assertFalse("one run at a time", p.start())
        val s = p.settled()
        assertEquals(PrepPhase.Done, s.phase)
        assertEquals(3, s.phrases.total)
        assertEquals(3, s.phrases.done)
        assertTrue(s.ready)
        assertEquals("high accuracy is off: two models fetched", 2, models.downloads.get())
        assertTrue(s.models.none { it.id == SttCatalog.parakeet06b.id })
        settings = settings.copy(accurateModel = SttCatalog.parakeet06b.id)
        p.refresh()
        assertEquals(661_000_000L, p.state.value.bytesToFetch)
        settings = settings.copy(accurateModel = SttCatalog.parakeet110m.id)
        p.refresh()
        assertEquals(3, server.requests.size)

        // Again: everything is cached, nothing is fetched.
        assertTrue(p.start())
        p.settled()
        assertEquals(3, server.requests.size)
        assertEquals(3, p.state.value.phrases.cached)
    }

    @Test fun aNewVoiceNeedsPreparingAgain() = runTest {
        val p = prep(backgroundScope, SpeechModels.None)
        p.start()
        p.settled()
        assertTrue(p.state.value.ready)
        settings = settings.copy(billVoice = "george")
        p.refresh()
        assertFalse(p.state.value.ready)
    }

    @Test fun cancelStopsAndStartResumes() = runTest {
        val gateOpen = CompletableDeferred<Unit>()
        val slow = object : SpeechModels {
            private val s = MutableStateFlow(listOf(SpeechModelStatus(SttCatalog.zipformer.id, "Streaming", ModelRole.Streaming, ModelState.Missing)))
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
        val p = prep(backgroundScope, slow)
        assertTrue(p.start())
        runCurrent()
        assertEquals(PrepPhase.Running, p.state.value.phase)
        p.cancel()
        assertEquals(PrepPhase.Cancelled, p.settled().phase)
        assertTrue("resume", p.start())
        assertEquals(PrepPhase.Done, p.settled().phase)
        assertEquals(2, slow.calls)
    }

    @Test fun offStateBuildsNoRender() = runTest {
        var built = 0
        settings = settings.copy(voiceEngine = VoiceEngine.GptLive)
        val p = prep(backgroundScope, models()) { built++; render() }
        assertFalse(p.start())
        key = KeyStatus()
        settings = settings.copy(voiceEngine = VoiceEngine.Cascade)
        assertFalse(p.start())
        advanceUntilIdle()
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
