package com.folio.hermeslive.lab

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal RIFF/WAVE reader for TTS output synthesised into memory. */
object WavPcm {
    data class Pcm(val sampleRate: Int, val channels: Int, val samples: ShortArray)

    fun parse(bytes: ByteArray): Pcm {
        require(bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE") { "not a WAVE file" }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var rate = 0
        var channels = 0
        var bits = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val declared = buf.getInt(pos + 4).toLong() and 0xffffffffL
            val body = pos + 8
            if (id == "fmt ") {
                require(buf.getShort(body).toInt() == 1) { "not PCM" }
                channels = buf.getShort(body + 2).toInt()
                rate = buf.getInt(body + 4)
                bits = buf.getShort(body + 14).toInt()
            } else if (id == "data") {
                require(rate > 0 && bits == 16) { "need 16-bit PCM after fmt" }
                // Streaming writers leave the size 0 or 0xFFFFFFFF: take the rest of the bytes.
                val available = bytes.size - body
                val size = if (declared == 0L || declared > available) available else declared.toInt()
                val samples = ShortArray(size / 2)
                buf.position(body)
                buf.asShortBuffer().get(samples)
                return Pcm(rate, channels, samples)
            }
            if (declared > bytes.size) break
            pos = body + declared.toInt() + (declared.toInt() and 1)
        }
        error("no data chunk")
    }

    /** Mono 16-bit samples: channels averaged. */
    fun mono(pcm: Pcm): ShortArray {
        if (pcm.channels <= 1) return pcm.samples
        val n = pcm.samples.size / pcm.channels
        return ShortArray(n) { i ->
            var sum = 0
            for (c in 0 until pcm.channels) sum += pcm.samples[i * pcm.channels + c]
            (sum / pcm.channels).toShort()
        }
    }
}
