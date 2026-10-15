package com.folio.hermes.live

import com.folio.hermes.FakeHermesServer
import com.folio.hermes.FakeTokens
import com.folio.hermes.HermesJson
import com.folio.hermes.openrouter.FailureKind
import com.folio.hermes.openrouter.OpenRouterResult
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesVoiceApiTest {
    private val hermes = FakeHermesServer()
    private val voice = FakeHermesVoice(hermes)
    private val provider = FakeProvider()
    private val tokens = FakeTokens()
    private val api = HermesVoiceApi(hermes.config(), tokens, reachable = { true })

    @After fun tearDown() {
        hermes.close()
        provider.close()
    }

    @Test fun relayConfigIsReadWithTheBearer() = runBlocking {
        val cfg = (api.voiceConfig() as OpenRouterResult.Ok).value!!
        assertTrue(cfg.stt is VoiceSide.Relay)
        assertEquals("local provider", (cfg.stt as VoiceSide.Relay).reason)
        assertEquals("relay", cfg.view().tts.mode)
        assertTrue(cfg.stt.available)
    }

    @Test fun directConfigCarriesWireProviderModelAndVoiceButNeverPrintsTheKey() = runBlocking {
        voice.configBody = FakeHermesVoice.directConfig(provider.base)
        val cfg = (api.voiceConfig() as OpenRouterResult.Ok).value!!
        val tts = cfg.tts as VoiceSide.Direct
        assertEquals(TtsWire.ELEVENLABS, tts.wire)
        assertEquals("elevenlabs", tts.provider)
        assertEquals("eleven_flash_v2_5", tts.model)
        assertEquals("v-bill", tts.voice)
        assertEquals(provider.base, tts.baseUrl)
        val stt = cfg.stt as VoiceSide.Direct
        assertEquals(60.0, stt.timeoutS!!, 0.0)
        assertNull(stt.language)
        assertFalse(cfg.toString().contains(SENTINEL_KEY))
        assertFalse(cfg.view().toString().contains(SENTINEL_KEY))
        assertFalse(cfg.view().toString().contains(provider.base))
    }

    @Test fun anOlderServerWithoutTheRouteIsNoConfigNotAnError() = runBlocking {
        hermes.routes.remove(HermesVoiceApi.PATH_CONFIG)
        assertEquals(OpenRouterResult.Ok(null), api.voiceConfig())
    }

    @Test fun a401RefreshesTheTokenOnce() = runBlocking {
        hermes.acceptedTokens.clear()
        hermes.acceptedTokens += "at-2"
        assertTrue(api.voiceConfig() is OpenRouterResult.Ok)
        assertEquals(listOf("at-1"), tokens.refreshes)
    }

    @Test fun signedOutMakesNoRequest() = runBlocking {
        tokens.signedIn = false
        assertEquals(OpenRouterResult.Failed(FailureKind.NoKey), api.voiceConfig())
        assertEquals(OpenRouterResult.Failed(FailureKind.NoKey), api.speak("hi"))
        assertEquals(0, voice.audioRequests)
    }

    @Test fun transcribeSendsTheWavAsABase64DataUrl() = runBlocking {
        val wav = byteArrayOf(1, 2, 3, 4)
        assertEquals(OpenRouterResult.Ok("hello from hermes"), api.transcribe(wav, timeoutMs = 3_000))
        val body = HermesJson.parseToJsonElement(voice.transcribeBodies.single()).jsonObject
        assertEquals("audio/wav", body["mime_type"]!!.jsonPrimitive.content)
        assertEquals("data:audio/wav;base64," + Base64.getEncoder().encodeToString(wav), body["data_url"]!!.jsonPrimitive.content)
    }

    @Test fun noSpeechIsAnEmptyTranscript() = runBlocking {
        voice.transcript = ""
        assertEquals(OpenRouterResult.Ok(""), api.transcribe(byteArrayOf(1), timeoutMs = 3_000))
    }

    @Test fun speakSendsOnlyTheTextAndDecodesTheDataUrl() = runBlocking {
        val spoken = (api.speak("Hello there.") as OpenRouterResult.Ok).value
        assertArrayEquals(voice.speakBytes, spoken.bytes)
        assertEquals("audio/mpeg", spoken.contentType)
        assertEquals("""{"text":"Hello there."}""", voice.speakBodies.single())
    }

    @Test fun speakFailureIsAKindWithNoContent() = runBlocking {
        voice.speakCode = 400
        assertEquals(OpenRouterResult.Failed(FailureKind.Http, 400), api.speak("x"))
    }

    @Test fun elevenLabsVoicesKeepTheServerLabels() = runBlocking {
        val list = (api.elevenLabsVoices() as OpenRouterResult.Ok).value
        assertTrue(list.available)
        assertEquals(listOf("v-ada", "v-bill").sorted(), list.voices.map { it.voice_id }.sorted())
        assertEquals("Bill (premade)", list.voices.first { it.voice_id == "v-bill" }.label)
    }

    @Test fun parseTurnsWhatThePhoneCannotCallIntoARelay() {
        fun parse(json: String) = HermesVoiceConfig.parse(HermesJson.parseToJsonElement(json).jsonObject, 0L)!!
        // A server-local Kokoro box (private URL, the "not-needed" key) relays, keeping its names for Settings.
        val local = parse(FakeHermesVoice.directConfig("http://192.168.1.20:8880/v1", key = "not-needed"))
        assertEquals(VoiceSide.Relay("server-local endpoint", "elevenlabs", "eleven_flash_v2_5"), local.tts)
        val unknown = parse("""{"ok":true,"stt":{"mode":"direct","wire":"grpc","provider":"x","base_url":"https://api.example.com","api_key":"k"},"tts":{"mode":"relay","reason":"r"}}""")
        assertEquals("no phone wire", (unknown.stt as VoiceSide.Relay).reason)
        val noKey = parse("""{"ok":true,"stt":{"mode":"direct","wire":"xai-stt","provider":"xai","base_url":"https://api.example.com","api_key":""},"tts":{"mode":"relay","reason":"r"}}""")
        assertEquals("no key for the phone", (noKey.stt as VoiceSide.Relay).reason)
        val disabled = parse(FakeHermesVoice.relayConfig(sttReason = "stt disabled"))
        assertFalse(disabled.stt.available)
        assertEquals("unavailable", disabled.view().stt.mode)
        assertNull(HermesVoiceConfig.parse(HermesJson.parseToJsonElement("""{"ok":false}""").jsonObject, 0L))
    }

    @Test fun reachabilityRule() {
        val yes = listOf("https://api.openai.com/v1", "https://api.elevenlabs.io/v1", "http://box.tail1234.ts.net:8880/v1", "https://8.8.8.8/v1")
        val no = listOf(
            "http://api.openai.com/v1", "https://localhost:8000/v1", "https://kokoro.local/v1", "https://tts.lan/v1", "https://x.internal/v1",
            "https://127.0.0.1/v1", "https://10.0.0.5/v1", "https://172.16.3.4/v1", "https://192.168.0.2/v1", "https://169.254.1.1/v1",
            "https://100.101.102.103/v1", "https://[::1]/v1", "https://[fd00::1]/v1", "not a url",
        )
        yes.forEach { assertTrue(it, HermesVoiceConfig.phoneReachable(it)) }
        no.forEach { assertFalse(it, HermesVoiceConfig.phoneReachable(it)) }
    }
}
