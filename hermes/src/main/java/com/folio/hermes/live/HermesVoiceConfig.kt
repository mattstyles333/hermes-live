package com.folio.hermes.live

import java.net.InetAddress
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * One side (speech-to-text or text-to-speech) of `GET /api/audio/voice-config` (the deployed
 * server's `tools/voice_client_config.py`, read 2026-10-15). Either the server relays it
 * (`{"mode":"relay","reason"}`: `POST /api/audio/transcribe` / `/api/audio/speak`), or the phone
 * may call the provider itself ([Direct]) with the profile's own key.
 */
sealed interface VoiceSide {
    /** Usable at all (a disabled STT relays to nothing). */
    val available: Boolean

    /** `relay`, `direct`; never the key. */
    val mode: String

    /**
     * The server relays this side. [reason] is the server's (no secrets in it). [provider] and
     * [model] are known only when the phone turned a [Direct] it cannot reach into a relay.
     */
    data class Relay(val reason: String, val provider: String? = null, val model: String? = null) : VoiceSide {
        override val available: Boolean get() = reason != REASON_STT_DISABLED
        override val mode: String get() = "relay"
    }

    /**
     * Call [provider] directly on [wire]. [apiKey] is held in memory only: never persisted,
     * logged or sent anywhere but [baseUrl] (Hermes's rule). [toString] leaves it out.
     */
    class Direct(
        val wire: String,
        val provider: String,
        val baseUrl: String,
        internal val apiKey: String,
        val model: String?,
        /** TTS: the configured voice (OpenAI voice name, ElevenLabs voice id). */
        val voice: String? = null,
        /** TTS: OpenAI speed (null: the provider's default). */
        val speed: Double? = null,
        /** STT: the configured language code, or null. */
        val language: String? = null,
        /** STT: the server's own deadline for the provider, seconds. */
        val timeoutS: Double? = null,
        /** TTS (OpenAI-compatible): extra JSON body fields (`lang_code`, `consent_attestation`). */
        val extraBody: JsonObject = JsonObject(emptyMap()),
    ) : VoiceSide {
        override val available: Boolean get() = true
        override val mode: String get() = "direct"

        /** No key, no URL: names only. */
        override fun toString(): String = "Direct(wire=$wire, provider=$provider, model=$model, voice=$voice)"
    }

    companion object {
        const val REASON_STT_DISABLED = "stt disabled"
    }
}

/** The STT wires the phone speaks (`voice_client_config.py`); anything else relays. */
object SttWire {
    /** `POST {base_url}/audio/transcriptions`, multipart, Bearer. */
    const val OPENAI = "openai-multipart"

    /** `POST {base_url}/stt`, multipart, Bearer, `format=true`. */
    const val XAI = "xai-stt"

    /** `POST {base_url}/speech-to-text`, multipart, `xi-api-key`. */
    const val ELEVENLABS = "elevenlabs-stt"

    val ALL = setOf(OPENAI, XAI, ELEVENLABS)
}

/** The TTS wires the phone speaks; anything else relays. */
object TtsWire {
    /** `POST {base_url}/audio/speech`, JSON, Bearer: audio bytes. */
    const val OPENAI = "openai-speech"

    /** `POST {base_url}/text-to-speech/{voice_id}`, JSON, `xi-api-key`. */
    const val ELEVENLABS = "elevenlabs-tts"

    val ALL = setOf(OPENAI, ELEVENLABS)
}

/**
 * Both sides of the profile's voice config, held in memory only ([HermesVoice]). [toString] has
 * no key in it ([VoiceSide.Direct.toString]).
 */
data class HermesVoiceConfig(val stt: VoiceSide, val tts: VoiceSide, val fetchedAtMs: Long) {
    /** The non-secret view Settings shows. */
    fun view(): HermesVoiceView = HermesVoiceView(SideView.of(stt), SideView.of(tts))

    companion object {
        /**
         * Parse `{ok, stt, tts}`. A side the phone cannot use directly (unknown wire, no key, or a
         * [baseUrl][VoiceSide.Direct.baseUrl] only the server can reach: [phoneReachable]) becomes
         * a relay. Null when the body is not the shape.
         */
        fun parse(body: JsonObject, nowMs: Long, reachable: (String) -> Boolean = ::phoneReachable): HermesVoiceConfig? {
            if ((body["ok"] as? JsonPrimitive)?.booleanOrNull != true) return null
            val stt = side(body["stt"] as? JsonObject, SttWire.ALL, reachable) ?: return null
            val tts = side(body["tts"] as? JsonObject, TtsWire.ALL, reachable) ?: return null
            return HermesVoiceConfig(stt, tts, nowMs)
        }

        private fun side(o: JsonObject?, wires: Set<String>, reachable: (String) -> Boolean): VoiceSide? {
            o ?: return null
            fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.ifEmpty { null }
            fun d(k: String) = (o[k] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
            return when (s("mode")) {
                "relay" -> VoiceSide.Relay(s("reason") ?: "relay")
                "direct" -> {
                    val wire = s("wire")
                    val provider = s("provider") ?: "unknown"
                    val base = s("base_url")?.trimEnd('/')
                    val key = (o["api_key"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim().orEmpty()
                    val model = s("model")
                    when {
                        wire == null || wire !in wires -> VoiceSide.Relay("no phone wire", provider, model)
                        base == null || !reachable(base) -> VoiceSide.Relay("server-local endpoint", provider, model)
                        key.isEmpty() -> VoiceSide.Relay("no key for the phone", provider, model)
                        else -> VoiceSide.Direct(
                            wire = wire, provider = provider, baseUrl = base, apiKey = key, model = model,
                            voice = s("voice"), speed = d("speed"), language = s("language"), timeoutS = d("timeout_s"),
                            extraBody = o["extra_body"] as? JsonObject ?: JsonObject(emptyMap()),
                        )
                    }
                }
                else -> null
            }
        }

        /**
         * True when the phone can call [url] itself: https to a public host, or http on a tailnet
         * name (`*.ts.net`, the app's one cleartext rule). Loopback, private and link-local
         * addresses, `localhost`, `.local`, `.lan` and `.internal` are the server's own (a Kokoro or
         * whisper box beside it, with the "not-needed" key): those relay.
         */
        fun phoneReachable(url: String): Boolean {
            val u = url.toHttpUrlOrNull() ?: return false
            val host = u.host.lowercase()
            if (!u.isHttps) return host.endsWith(".ts.net")
            if (host == "localhost" || host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".internal") || host.endsWith(".localhost")) return false
            if (!looksLikeIp(host)) return true
            val addr = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
            if (addr.isLoopbackAddress || addr.isSiteLocalAddress || addr.isLinkLocalAddress || addr.isAnyLocalAddress) return false
            val b = addr.address
            // 100.64.0.0/10 (carrier-grade NAT, Tailscale) and fc00::/7 (unique local).
            if (b.size == 4 && b[0].toInt() and 0xFF == 100 && (b[1].toInt() and 0xC0) == 64) return false
            if (b.size == 16 && (b[0].toInt() and 0xFE) == 0xFC) return false
            return true
        }

        private fun looksLikeIp(host: String): Boolean = host.contains(':') || host.all { it.isDigit() || it == '.' }
    }
}

/** What Settings shows of one side: no key, no URL. */
data class SideView(
    /** `direct`, `relay`, or `unavailable`. */
    val mode: String,
    val provider: String?,
    val model: String?,
    val voice: String?,
    val wire: String?,
    val reason: String?,
) {
    val available: Boolean get() = mode != "unavailable"

    companion object {
        fun of(side: VoiceSide): SideView = when (side) {
            is VoiceSide.Direct -> SideView("direct", side.provider, side.model, side.voice, side.wire, null)
            is VoiceSide.Relay -> SideView(if (side.available) "relay" else "unavailable", side.provider, side.model, null, null, side.reason)
        }
    }
}

/** Both sides as Settings shows them. */
data class HermesVoiceView(val stt: SideView, val tts: SideView)
