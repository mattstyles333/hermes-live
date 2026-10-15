package com.folio.hermes.live

import com.folio.hermes.HermesJson
import com.folio.hermes.cascade.AudioClip
import com.folio.hermes.openrouter.FailureKind
import com.folio.hermes.openrouter.OpenRouterResult
import com.folio.hermes.openrouter.SpeechFormat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each of the five direct wires against a MockWebServer standing in for the provider. */
class DirectVoiceWiresTest {
    private val provider = FakeProvider()
    private val wires = DirectVoiceWires()
    private val wav = byteArrayOf(82, 73, 70, 70, 1, 2, 3)

    @After fun tearDown() = provider.close()

    private fun side(wire: String, providerName: String, model: String? = "m-1", voice: String? = null, language: String? = null) =
        VoiceSide.Direct(wire, providerName, provider.base, SENTINEL_KEY, model, voice = voice, language = language,
            extraBody = buildJsonObject { if (wire == TtsWire.OPENAI) put("lang_code", "en") })

    private fun RecordedRequest.form(): String = body!!.utf8()

    /** A multipart text field's value, or null. */
    private fun RecordedRequest.field(name: String): String? =
        Regex("name=\"" + Regex.escape(name) + "\"\r\n(?:[^\r\n]+\r\n)*\r\n([^\r]*)\r\n").find(form())?.groupValues?.get(1)

    @Test fun openAiMultipart() = runBlocking {
        val r = wires.transcribe(side(SttWire.OPENAI, "openai", "whisper-1", language = "en"), wav, listOf("Kestrel", "Folio"), 3_000)
        assertEquals(OpenRouterResult.Ok("hello direct"), r)
        val req = provider.requests.single()
        assertEquals("/v1/audio/transcriptions", req.url.encodedPath)
        assertEquals("Bearer $SENTINEL_KEY", req.headers["Authorization"])
        val form = req.form()
        assertTrue(form.contains("name=\"file\"; filename=\"speech.wav\""))
        assertEquals("whisper-1", req.field("model"))
        assertEquals("en", req.field("language"))
        assertEquals("Vocabulary: Kestrel, Folio.", req.field("prompt"))
    }

    @Test fun openAiMultipartOnAProviderWithoutPromptSendsNoWords() = runBlocking {
        wires.transcribe(side(SttWire.OPENAI, "mistral"), wav, listOf("Kestrel"), 3_000)
        assertFalse(provider.requests.single().form().contains("Vocabulary"))
    }

    @Test fun xaiStt() = runBlocking {
        assertEquals(OpenRouterResult.Ok("hello direct"), wires.transcribe(side(SttWire.XAI, "xai", model = null), wav, listOf("Kestrel"), 3_000))
        val req = provider.requests.single()
        assertEquals("/v1/stt", req.url.encodedPath)
        assertEquals("Bearer $SENTINEL_KEY", req.headers["Authorization"])
        val form = req.form()
        assertEquals("true", req.field("format"))
        assertFalse(form.contains("Kestrel"))
    }

    @Test fun elevenLabsStt() = runBlocking {
        assertEquals(OpenRouterResult.Ok("hello direct"), wires.transcribe(side(SttWire.ELEVENLABS, "elevenlabs", "scribe_v2", language = "en"), wav, emptyList(), 3_000))
        val req = provider.requests.single()
        assertEquals("/v1/speech-to-text", req.url.encodedPath)
        assertEquals(SENTINEL_KEY, req.headers["xi-api-key"])
        assertNull(req.headers["Authorization"])
        val form = req.form()
        assertEquals("scribe_v2", req.field("model_id"))
        assertEquals("en", req.field("language_code"))
    }

    @Test fun openAiSpeechAsksForPcm() = runBlocking {
        val r = wires.speak(side(TtsWire.OPENAI, "openai", "gpt-4o-mini-tts", voice = "alloy"), "Hello.", "coral", 3_000)
        val spoken = (r as OpenRouterResult.Ok).value
        assertArrayEquals(provider.ttsBody, spoken.bytes)
        val req = provider.requests.single()
        assertEquals("/v1/audio/speech", req.url.encodedPath)
        assertEquals("Bearer $SENTINEL_KEY", req.headers["Authorization"])
        val body = HermesJson.parseToJsonElement(req.body!!.utf8()).jsonObject
        assertEquals("gpt-4o-mini-tts", body["model"]!!.jsonPrimitive.content)
        assertEquals("Hello.", body["input"]!!.jsonPrimitive.content)
        assertEquals("coral", body["voice"]!!.jsonPrimitive.content)
        assertEquals("pcm", body["response_format"]!!.jsonPrimitive.content)
        assertEquals("en", body["lang_code"]!!.jsonPrimitive.content)
        val clip = VoiceAudio.clip(spoken.bytes, spoken.contentType, AudioDecoder.None, askedPcm = true)!!
        assertEquals(SpeechFormat.Pcm(24_000, 1, 16), clip.format)
    }

    @Test fun elevenLabsTtsAsksForPcm24k() = runBlocking {
        val r = wires.speak(side(TtsWire.ELEVENLABS, "elevenlabs", "eleven_flash_v2_5", voice = "v-bill"), "Hi.", null, 3_000)
        assertTrue(r is OpenRouterResult.Ok)
        val req = provider.requests.single()
        assertEquals("/v1/text-to-speech/v-bill", req.url.encodedPath)
        assertEquals("pcm_24000", req.url.queryParameter("output_format"))
        assertEquals(SENTINEL_KEY, req.headers["xi-api-key"])
        val body = HermesJson.parseToJsonElement(req.body!!.utf8()).jsonObject
        assertEquals("Hi.", body["text"]!!.jsonPrimitive.content)
        assertEquals("eleven_flash_v2_5", body["model_id"]!!.jsonPrimitive.content)
    }

    @Test fun aServerThatAnswersWavIsSniffed() = runBlocking {
        val pcm = ByteArray(960) { it.toByte() }
        provider.ttsBody = wavOf(pcm, 22_050)
        provider.ttsContentType = "audio/wav"
        val spoken = (wires.speak(side(TtsWire.OPENAI, "deepinfra", "hexgrad/Kokoro-82M", voice = "af_bella"), "Hi.", null, 3_000) as OpenRouterResult.Ok).value
        val clip = VoiceAudio.clip(spoken.bytes, spoken.contentType, AudioDecoder.None, askedPcm = true)!!
        assertEquals(22_050, clip.format.sampleRateHz)
        assertArrayEquals(pcm, clip.bytes)
    }

    @Test fun mp3GoesToTheDecoder() {
        var asked: String? = null
        val decoded = AudioClip(ByteArray(4), SpeechFormat.Pcm(44_100, 2, 16))
        val clip = VoiceAudio.clip(ByteArray(10) { 1 }, "audio/mpeg", { _, mime -> asked = mime; decoded })
        assertEquals("audio/mpeg", asked)
        assertEquals(decoded, clip)
        assertNull(VoiceAudio.clip(ByteArray(10) { 1 }, "audio/ogg", AudioDecoder.None))
    }

    @Test fun refusedKeyAndOfflineAreKinds() = runBlocking {
        provider.code = 401
        assertEquals(OpenRouterResult.Failed(FailureKind.Refused, 401), wires.speak(side(TtsWire.ELEVENLABS, "elevenlabs", voice = "v"), "x", null, 3_000))
        provider.close()
        val r = wires.transcribe(side(SttWire.XAI, "xai"), wav, emptyList(), 3_000)
        assertEquals(FailureKind.Offline, (r as OpenRouterResult.Failed).kind)
    }

    @Test fun anElevenLabsTtsWithNoVoiceIsNotSent() = runBlocking {
        assertEquals(OpenRouterResult.Failed(FailureKind.BadBody), wires.speak(side(TtsWire.ELEVENLABS, "elevenlabs", voice = null), "x", null, 3_000))
        assertTrue(provider.requests.isEmpty())
    }

    companion object {
        fun wavOf(pcm: ByteArray, rate: Int): ByteArray {
            val b = java.nio.ByteBuffer.allocate(44 + pcm.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
            b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
            b.put("data".toByteArray()).putInt(pcm.size).put(pcm)
            return b.array()
        }
    }
}
