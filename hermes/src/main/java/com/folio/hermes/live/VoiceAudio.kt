package com.folio.hermes.live

import com.folio.hermes.cascade.AudioClip
import com.folio.hermes.openrouter.SpeechFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Turns an encoded clip (MP3, Ogg/Opus, FLAC, AAC) into 16-bit PCM for the player. `:live` decodes
 * with Android's `MediaExtractor` + `MediaCodec` in memory; the library has no codec of its own.
 * Null: could not decode.
 */
fun interface AudioDecoder {
    fun decode(bytes: ByteArray, mime: String): AudioClip?

    companion object {
        /** Decodes nothing (tests, and where only PCM and WAV arrive). */
        val None: AudioDecoder = AudioDecoder { _, _ -> null }
    }
}

/** Audio that came back from a TTS request: the bytes and what the server says they are. */
class SpokenBytes(val bytes: ByteArray, val contentType: String?)

/** Helpers for the audio Hermes and the direct wires return. Pure. */
object VoiceAudio {
    /**
     * The clip in [bytes]: a WAV is parsed, raw PCM (`audio/pcm`, `audio/l16`, or no type at all
     * from a request that asked for PCM) is taken at [pcmRate] unless the type names a `rate`,
     * anything else goes to [decoder]. Null when it cannot be played.
     */
    fun clip(bytes: ByteArray, contentType: String?, decoder: AudioDecoder, pcmRate: Int = SpeechFormat.DEFAULT_RATE, askedPcm: Boolean = false): AudioClip? {
        if (bytes.isEmpty()) return null
        if (isWav(bytes)) return parseWav(bytes)
        val type = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        val pcm = type == "audio/pcm" || type == "audio/l16" || type == "audio/raw" ||
            (askedPcm && (type.isEmpty() || type == "application/octet-stream"))
        if (pcm) {
            val format = SpeechFormat.fromContentType(contentType, SpeechFormat.Pcm(sampleRateHz = pcmRate)) as SpeechFormat.Pcm
            val whole = bytes.size - bytes.size % format.frameBytes
            return if (whole == 0) null else AudioClip(if (whole == bytes.size) bytes else bytes.copyOf(whole), format)
        }
        return decoder.decode(bytes, type.ifEmpty { "audio/mpeg" })
    }

    fun isWav(bytes: ByteArray): Boolean = bytes.size >= 12 &&
        bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
        bytes[8] == 'W'.code.toByte() && bytes[9] == 'A'.code.toByte() && bytes[10] == 'V'.code.toByte() && bytes[11] == 'E'.code.toByte()

    /** A 16-bit PCM WAV's samples, or null (another encoding, or a broken header). */
    fun parseWav(bytes: ByteArray): AudioClip? {
        if (!isWav(bytes)) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var rate = 0
        var channels = 0
        var bits = 0
        var pcmFormat = false
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = buf.getInt(pos + 4).toLong() and 0xFFFFFFFFL
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    if (body + 16 > bytes.size) return null
                    val tag = buf.getShort(body).toInt() and 0xFFFF
                    channels = buf.getShort(body + 2).toInt()
                    rate = buf.getInt(body + 4)
                    bits = buf.getShort(body + 14).toInt()
                    pcmFormat = tag == 1 || tag == 0xFFFE
                }
                "data" -> {
                    if (!pcmFormat || bits != 16 || rate <= 0 || channels !in 1..2) return null
                    // A streamed WAV may say 0 or 0xFFFFFFFF: take what is there.
                    val end = (body + size).coerceAtMost(bytes.size.toLong()).toInt()
                    val format = SpeechFormat.Pcm(rate, channels, 16)
                    val len = (end - body).let { it - it % format.frameBytes }
                    if (len <= 0) return null
                    return AudioClip(bytes.copyOfRange(body, body + len), format)
                }
            }
            if (size > bytes.size) return null
            pos = body + size.toInt() + (size.toInt() and 1)
        }
        return null
    }
}
