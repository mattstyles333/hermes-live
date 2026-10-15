package com.folio.hermeslive.data

import com.folio.hermes.AccessTokenProvider
import com.folio.hermes.HermesAuthException
import com.folio.hermes.HermesConfig
import com.folio.hermes.HermesEndpoint
import com.folio.hermes.cascade.AudioClip
import com.folio.hermes.live.AudioDecoder
import com.folio.hermes.openrouter.SpeechFormat
import java.io.Closeable
import java.net.InetAddress
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Dns
import okio.Buffer

/** A made-up provider key the tests look for everywhere it must never be. */
const val LIVE_SENTINEL_KEY = "sk-sentinel-live-voice-9999"

/** `*.ts.net` names resolve to loopback (the fakes); anything else is refused (no test leaves the machine). */
object TsNetLoopback : Dns {
    override fun lookup(hostname: String): List<InetAddress> =
        if (hostname.endsWith(".ts.net")) listOf(InetAddress.getLoopbackAddress()) else throw java.net.UnknownHostException(hostname)
}

/** The relay's MP3s "decode" to a fixed clip (the real decoder is Android's MediaCodec). */
object FakeDecoder : AudioDecoder {
    override fun decode(bytes: ByteArray, mime: String): AudioClip = AudioClip(ByteArray(4_410), SpeechFormat.Pcm(44_100, 1, 16))
}

/**
 * Hermes's voice routes (shapes from the deployed `audio.py`) and a provider standing in for
 * OpenAI/ElevenLabs, on two MockWebServers reached as `fake-hermes.ts.net` / `fake-provider.ts.net`
 * (so the app's own endpoint and reachability rules apply unchanged). Never a real server.
 */
class FakeHermesVoiceServer : Closeable {
    val hermes = MockWebServer()
    val provider = MockWebServer()
    @Volatile var configBody: String = relay()
    @Volatile var transcript = "hello from hermes"
    @Volatile var providerCode = 200
    @Volatile var hermesCode = 200
    val configCalls = AtomicInteger()
    val transcribeCalls = AtomicInteger()
    val speakCalls = AtomicInteger()
    val voicesCalls = AtomicInteger()
    val providerRequests = CopyOnWriteArrayList<RecordedRequest>()
    val tokens = object : AccessTokenProvider {
        @Volatile var signedIn = true
        override suspend fun accessToken(): String = if (signedIn) "at-1" else throw HermesAuthException.SignInRequired()
        override suspend fun refreshAfterUnauthorized(rejected: String): String = throw HermesAuthException.SignInRequired()
    }

    /** Every request to Hermes's `/api/audio/…` routes so far. */
    val audioRequests: Int get() = configCalls.get() + transcribeCalls.get() + speakCalls.get() + voicesCalls.get()

    init {
        hermes.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.headers["Authorization"] != "Bearer at-1") return json(401, """{"detail":"Unauthorized"}""")
                if (hermesCode != 200) return json(hermesCode, """{"detail":"down"}""")
                return when (request.url.encodedPath) {
                    "/api/audio/voice-config" -> { configCalls.incrementAndGet(); json(200, configBody) }
                    "/api/audio/transcribe" -> { transcribeCalls.incrementAndGet(); json(200, """{"ok":true,"transcript":"$transcript","provider":"openai"}""") }
                    "/api/audio/speak" -> {
                        speakCalls.incrementAndGet()
                        json(200, """{"ok":true,"data_url":"data:audio/mpeg;base64,${Base64.getEncoder().encodeToString(ByteArray(64) { 1 })}","mime_type":"audio/mpeg","provider":"edge"}""")
                    }
                    "/api/audio/elevenlabs/voices" -> {
                        voicesCalls.incrementAndGet()
                        json(200, """{"available":true,"voices":[{"voice_id":"v-ada","name":"Ada","label":"Ada (cloned)"},{"voice_id":"v-bill","name":"Bill","label":"Bill (premade)"}]}""")
                    }
                    else -> json(404, """{"detail":"Not Found"}""")
                }
            }
        }
        provider.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                providerRequests += request
                if (providerCode != 200) return json(providerCode, """{"error":"nope"}""")
                val path = request.url.encodedPath
                return if (path.contains("/text-to-speech/") || path.endsWith("/audio/speech")) {
                    MockResponse.Builder().code(200).addHeader("Content-Type", "audio/pcm").body(Buffer().write(ByteArray(4_800))).build()
                } else {
                    json(200, """{"text":"hello direct"}""")
                }
            }
        }
        hermes.start()
        provider.start()
    }

    val hermesUrl: String get() = "http://fake-hermes.ts.net:${hermes.port}"
    val providerBase: String get() = "http://fake-provider.ts.net:${provider.port}/v1"

    fun account(): Pair<HermesConfig, AccessTokenProvider> =
        HermesConfig((HermesEndpoint.parse(hermesUrl) as HermesEndpoint.Parsed.Valid).endpoint) to tokens

    fun relay(stt: String = "local provider", tts: String = "provider 'edge' has no client wire") =
        """{"ok":true,"stt":{"mode":"relay","reason":"$stt"},"tts":{"mode":"relay","reason":"$tts"}}"""

    fun direct(ttsWire: String = "elevenlabs-tts", ttsProvider: String = "elevenlabs", ttsModel: String = "eleven_flash_v2_5", voice: String = "v-bill") =
        """{"ok":true,
        "stt":{"mode":"direct","wire":"openai-multipart","provider":"openai","base_url":"$providerBase","api_key":"$LIVE_SENTINEL_KEY","model":"whisper-1","language":null,"timeout_s":60.0},
        "tts":{"mode":"direct","wire":"$ttsWire","provider":"$ttsProvider","base_url":"$providerBase","api_key":"$LIVE_SENTINEL_KEY","model":"$ttsModel","voice":"$voice","speed":null}}"""

    override fun close() {
        hermes.close()
        provider.close()
    }

    private fun json(code: Int, body: String) = MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()
}
