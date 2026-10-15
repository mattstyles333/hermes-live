package com.folio.hermes.live

import com.folio.hermes.FakeHermesServer
import com.folio.hermes.json
import java.io.Closeable
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer

/** A made-up provider key: tests scan for it everywhere it must never be. */
const val SENTINEL_KEY = "sk-sentinel-hermes-voice-0000"

/**
 * Hermes's voice routes on [FakeHermesServer] (shapes from the deployed `audio.py`): `voice-config`
 * in relay or direct mode, `transcribe`, `speak` (an MP3-typed data URL by default) and the
 * ElevenLabs voices. Bearer-checked like the ticket route. No real server is ever called.
 */
class FakeHermesVoice(val hermes: FakeHermesServer) {
    @Volatile var configBody: String = relayConfig()
    @Volatile var configCode: Int = 200
    @Volatile var transcript: String = "hello from hermes"
    @Volatile var speakMime: String = "audio/mpeg"
    @Volatile var speakBytes: ByteArray = ByteArray(480) { (it % 7).toByte() }
    @Volatile var speakCode: Int = 200
    val configCalls = AtomicInteger()
    val transcribeBodies = CopyOnWriteArrayList<String>()
    val speakBodies = CopyOnWriteArrayList<String>()
    val voicesCalls = AtomicInteger()

    init {
        hermes.routes[HermesVoiceApi.PATH_CONFIG] = { r -> authed(r) { configCalls.incrementAndGet(); json(configCode, configBody) } }
        hermes.routes[HermesVoiceApi.PATH_TRANSCRIBE] = { r ->
            authed(r) {
                transcribeBodies += r.body!!.utf8()
                json(200, """{"ok":true,"transcript":"$transcript","provider":"openai"}""")
            }
        }
        hermes.routes[HermesVoiceApi.PATH_SPEAK] = { r ->
            authed(r) {
                speakBodies += r.body!!.utf8()
                if (speakCode != 200) {
                    json(speakCode, """{"detail":"Speech synthesis failed"}""")
                } else {
                    val b64 = Base64.getEncoder().encodeToString(speakBytes)
                    json(200, """{"ok":true,"data_url":"data:$speakMime;base64,$b64","mime_type":"$speakMime","provider":"edge"}""")
                }
            }
        }
        hermes.routes[HermesVoiceApi.PATH_VOICES] = { r ->
            authed(r) {
                voicesCalls.incrementAndGet()
                json(200, """{"available":true,"voices":[{"voice_id":"v-bill","name":"Bill","label":"Bill (premade)"},{"voice_id":"v-ada","name":"Ada","label":"Ada (cloned)"}]}""")
            }
        }
    }

    /** Every request to `/api/audio/…` so far. */
    val audioRequests: Int get() = configCalls.get() + transcribeBodies.size + speakBodies.size + voicesCalls.get()

    private fun authed(r: RecordedRequest, ok: () -> MockResponse): MockResponse {
        val bearer = r.headers["Authorization"]?.removePrefix("Bearer ")
        return if (bearer != null && bearer in hermes.acceptedTokens) ok() else json(401, """{"detail":"Unauthorized"}""")
    }

    companion object {
        fun relayConfig(sttReason: String = "local provider", ttsReason: String = "provider 'edge' has no client wire") =
            """{"ok":true,"stt":{"mode":"relay","reason":"$sttReason"},"tts":{"mode":"relay","reason":"$ttsReason"}}"""

        /** Direct on both sides against [provider] (a MockWebServer standing in for OpenAI or ElevenLabs). */
        fun directConfig(
            providerBase: String,
            sttWire: String = SttWire.OPENAI,
            ttsWire: String = TtsWire.ELEVENLABS,
            sttProvider: String = "openai",
            ttsProvider: String = "elevenlabs",
            key: String = SENTINEL_KEY,
            ttsModel: String = "eleven_flash_v2_5",
            ttsVoice: String = "v-bill",
        ): String = """{"ok":true,
            "stt":{"mode":"direct","wire":"$sttWire","provider":"$sttProvider","base_url":"$providerBase","api_key":"$key","model":"whisper-1","language":null,"timeout_s":60.0},
            "tts":{"mode":"direct","wire":"$ttsWire","provider":"$ttsProvider","base_url":"$providerBase","api_key":"$key","model":"$ttsModel","voice":"$ttsVoice","speed":null,"min_len":20}}"""
    }
}

/**
 * A stand-in for the providers' own APIs (OpenAI, xAI, ElevenLabs, DeepInfra): records each
 * request and answers in the provider's shape. PCM for TTS unless [ttsContentType] says otherwise.
 */
class FakeProvider : Closeable {
    val server = MockWebServer()
    val requests = CopyOnWriteArrayList<RecordedRequest>()
    @Volatile var code = 200
    @Volatile var text = "hello direct"
    @Volatile var ttsBody: ByteArray = ByteArray(4_800) { (it % 11).toByte() }
    @Volatile var ttsContentType = "audio/pcm"

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                if (code != 200) return json(code, """{"error":{"message":"nope"}}""")
                val path = request.url.encodedPath
                return when {
                    path.endsWith("/audio/transcriptions") || path.endsWith("/stt") || path.endsWith("/speech-to-text") ->
                        json(200, """{"text":"$text","language":"en"}""")
                    path.endsWith("/audio/speech") || path.contains("/text-to-speech/") ->
                        MockResponse.Builder().code(200).addHeader("Content-Type", ttsContentType).body(Buffer().write(ttsBody)).build()
                    else -> json(404, """{"detail":"Not Found"}""")
                }
            }
        }
        server.start()
    }

    /** `http://127.0.0.1:<port>/v1` (no trailing slash, as the server sends). */
    val base: String get() = server.url("/v1").toString().trimEnd('/')

    override fun close() = server.close()
}
