package com.folio.hermes.live

import com.folio.hermes.FakeHermesServer
import com.folio.hermes.FakeTokens
import com.folio.hermes.cascade.AudioClip
import com.folio.hermes.cascade.VoiceIdentity
import com.folio.hermes.openrouter.FailureKind
import com.folio.hermes.openrouter.OpenRouterResult
import com.folio.hermes.openrouter.SpeechFormat
import com.folio.hermes.telemetry.TelemetrySink
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesVoiceTest {
    private val hermes = FakeHermesServer()
    private val fake = FakeHermesVoice(hermes)
    private val provider = FakeProvider()
    private val tokens = FakeTokens()
    private var now = 1_000_000L
    private val events = CopyOnWriteArrayList<Pair<String, Map<String, Any?>>>()
    private val sink = TelemetrySink { name, attrs, _ -> events += name to attrs }
    private val decoded = AudioClip(ByteArray(8_820), SpeechFormat.Pcm(44_100, 1, 16))
    private val decodes = CopyOnWriteArrayList<String>()
    private val voice = HermesVoice(
        HermesVoiceApi(hermes.config(), tokens, nowMs = { now }, reachable = { true }),
        DirectVoiceWires(),
        { _, mime -> decodes += mime; decoded },
        sink,
        nowMs = { now },
    )

    @After fun tearDown() {
        hermes.close()
        provider.close()
    }

    @Test fun theConfigIsHeldInMemoryForThirtyMinutesUnlessForced() = runBlocking {
        assertTrue(voice.config() != null)
        now += 29 * 60_000L
        voice.config()
        assertEquals(1, fake.configCalls.get())
        voice.config(force = true)
        assertEquals(2, fake.configCalls.get())
        now += 30 * 60_000L
        voice.config()
        assertEquals(3, fake.configCalls.get())
        voice.forget()
        assertNull(voice.cached())
        assertEquals(HermesVoiceState.Unknown, voice.state.value)
    }

    @Test fun aFailedFetchIsNotRetriedWithinAMinuteUnlessForced() = runBlocking {
        hermes.routes.remove(HermesVoiceApi.PATH_CONFIG)
        assertNull(voice.config())
        assertEquals(HermesVoiceState.Unavailable("none"), voice.state.value)
        now += 30_000
        assertNull(voice.config())
        assertEquals(0, fake.configCalls.get())
        assertEquals(1, events.count { it.first == HermesVoice.EVENT_CONFIG })
    }

    @Test fun anUnreachableServerKeepsTheConfigItHad() = runBlocking {
        fake.configBody = FakeHermesVoice.directConfig(provider.base)
        voice.config()
        hermes.server.close()
        val kept = voice.config(force = true)
        assertTrue(kept?.tts is VoiceSide.Direct)
        assertTrue(voice.state.value is HermesVoiceState.Ready)
    }

    @Test fun signedOutForgetsNothingAndFetchesNothing() = runBlocking {
        tokens.signedIn = false
        assertNull(voice.config(force = true))
        assertEquals(OpenRouterResult.Failed(FailureKind.NoKey), voice.speak("Hi.", null))
        assertEquals(0, fake.audioRequests)
    }

    @Test fun relaySpeechIsDecodedAndCachedUnderTheRelayIdentity() = runBlocking {
        val r = voice.speak("Hello.", "ignored-on-relay") as OpenRouterResult.Ok
        assertEquals(decoded, r.value.clip)
        assertEquals(listOf("audio/mpeg"), decodes)
        assertEquals(VoiceIdentity("hermes:relay/-/-", "configured"), r.value.identity)
        val e = events.last { it.first == HermesVoice.EVENT_TTS }.second
        assertEquals("hermes_relay", e["voice_source"])
        assertEquals(true, e["ok"])
    }

    @Test fun relayWavNeedsNoDecoder() = runBlocking {
        fake.speakMime = "audio/wav"
        fake.speakBytes = DirectVoiceWiresTest.wavOf(ByteArray(480), 24_000)
        val r = voice.speak("Hello.", null) as OpenRouterResult.Ok
        assertEquals(24_000, r.value.clip.format.sampleRateHz)
        assertTrue(decodes.isEmpty())
    }

    @Test fun directSpeechGoesToTheProviderWithTheChosenVoice() = runBlocking {
        fake.configBody = FakeHermesVoice.directConfig(provider.base)
        val r = voice.speak("Hello.", "v-ada") as OpenRouterResult.Ok
        assertEquals("/v1/text-to-speech/v-ada", provider.requests.single().url.encodedPath)
        assertTrue(fake.speakBodies.isEmpty())
        assertTrue(decodes.isEmpty())
        assertEquals(SpeechFormat.Pcm(24_000, 1, 16), r.value.clip.format)
        assertEquals(VoiceIdentity("hermes:elevenlabs/eleven_flash_v2_5", "v-ada"), r.value.identity)
        assertEquals("hermes_direct", events.last { it.first == HermesVoice.EVENT_TTS }.second["voice_source"])
    }

    @Test fun aRefusedDirectKeyRefetchesTheConfigAndRetriesOnce() = runBlocking {
        fake.configBody = FakeHermesVoice.directConfig(provider.base, key = "old-key")
        voice.config()
        provider.code = 401
        val r = voice.speak("Hello.", null)
        assertEquals(OpenRouterResult.Failed(FailureKind.Refused, 401), r)
        assertEquals(2, fake.configCalls.get())
        assertEquals(2, provider.requests.size)
    }

    @Test fun identityChangesWithSourceProviderModelAndVoice() = runBlocking {
        fake.configBody = FakeHermesVoice.directConfig(provider.base)
        val a = voice.ttsIdentity(voice.config()!!, null)
        val b = voice.ttsIdentity(voice.config()!!, "v-ada")
        fake.configBody = FakeHermesVoice.directConfig(provider.base, ttsModel = "eleven_v3")
        val c = voice.ttsIdentity(voice.config(force = true)!!, null)
        fake.configBody = FakeHermesVoice.directConfig(provider.base, ttsWire = TtsWire.OPENAI, ttsProvider = "openai", ttsModel = "eleven_flash_v2_5")
        val d = voice.ttsIdentity(voice.config(force = true)!!, null)
        fake.configBody = FakeHermesVoice.relayConfig()
        val e = voice.ttsIdentity(voice.config(force = true)!!, null)
        assertEquals(5, setOf(a, b, c, d, e).size)
        assertNotEquals(a.model, e.model)
    }

    @Test fun relayTranscriptionSendsNoWordsAndDirectOpenAiDoes() = runBlocking {
        assertEquals(OpenRouterResult.Ok("hello from hermes"), voice.transcribe(byteArrayOf(1, 2), listOf("Kestrel"), 3_000))
        assertFalse(fake.transcribeBodies.single().contains("Kestrel"))
        fake.configBody = FakeHermesVoice.directConfig(provider.base)
        voice.config(force = true)
        assertEquals(OpenRouterResult.Ok("hello direct"), voice.transcribe(byteArrayOf(1, 2), listOf("Kestrel"), 3_000))
        assertTrue(provider.requests.single().body!!.utf8().contains("Vocabulary: Kestrel."))
        val e = events.filter { it.first == HermesVoice.EVENT_STT }.map { it.second["voice_source"] }
        assertEquals(listOf("hermes_relay", "hermes_direct"), e)
    }

    @Test fun aDisabledSttIsNotCalled() = runBlocking {
        fake.configBody = FakeHermesVoice.relayConfig(sttReason = "stt disabled")
        assertEquals(OpenRouterResult.Failed(FailureKind.NoKey), voice.transcribe(byteArrayOf(1), emptyList(), 3_000))
        assertTrue(fake.transcribeBodies.isEmpty())
    }

    @Test fun telemetryNeverCarriesTheKeyOrTheProviderUrl() = runBlocking {
        fake.configBody = FakeHermesVoice.directConfig(provider.base)
        voice.config()
        voice.speak("Secret words.", null)
        voice.transcribe(byteArrayOf(1), listOf("Kestrel"), 3_000)
        provider.code = 401
        voice.speak("Secret words.", null)
        val all = events.joinToString { "${it.first} ${it.second}" }
        assertTrue(events.isNotEmpty())
        listOf(SENTINEL_KEY, provider.base, "127.0.0.1", "Secret words", "Kestrel", "hello direct").forEach { assertFalse(it, all.contains(it)) }
    }

    /**
     * The relay adds a hop (phone → Hermes → provider → Hermes → phone, plus base64 and a decode).
     * Against the local fake the extra hop is only the loopback: this prints both round trips so
     * the report can say what the relay itself costs (no provider time in either).
     */
    @Test fun relayAndDirectRoundTripsAgainstTheFake() = runBlocking {
        fake.speakMime = "audio/pcm"
        fake.speakBytes = ByteArray(48_000)
        voice.speak("warm", null)
        val relay = (1..10).map { timed { voice.speak("Hello there, this is one sentence.", null) } }.sorted()
        fake.configBody = FakeHermesVoice.directConfig(provider.base)
        provider.ttsBody = ByteArray(48_000)
        voice.config(force = true)
        voice.speak("warm", null)
        val direct = (1..10).map { timed { voice.speak("Hello there, this is one sentence.", null) } }.sorted()
        println("HERMES_VOICE_RTT relay median=${relay[5]}ms p90=${relay[9]}ms direct median=${direct[5]}ms p90=${direct[9]}ms (1 s of 24 kHz audio, loopback fake)")
        assertTrue(relay.all { it < 5_000 } && direct.all { it < 5_000 })
    }

    private suspend fun timed(block: suspend () -> Unit): Long {
        val t = System.nanoTime()
        block()
        return (System.nanoTime() - t) / 1_000_000
    }
}
