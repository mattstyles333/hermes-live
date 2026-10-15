package com.folio.hermeslive.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import com.folio.hermes.cascade.AudioClip
import com.folio.hermes.live.AudioDecoder
import com.folio.hermes.openrouter.SpeechFormat
import java.io.ByteArrayOutputStream

/**
 * Decodes the relay's encoded clips (MP3 by default; Ogg/Opus, FLAC, AAC) to 16-bit PCM with
 * `MediaExtractor` + `MediaCodec`, from memory ([MediaDataSource]: nothing is written to disk).
 * Mono or stereo at the clip's own rate: `AudioTrackOut` plays any rate. Null on any failure (the
 * line is skipped, or the fallback voice speaks it). Only reached for Hermes's relay TTS; both
 * direct wires ask for PCM. Not proven on a phone yet (no emulator in this slice).
 */
class MediaCodecDecoder(private val timeoutUs: Long = 10_000L) : AudioDecoder {
    override fun decode(bytes: ByteArray, mime: String): AudioClip? = runCatching { decodeOrThrow(bytes) }.getOrNull()

    private fun decodeOrThrow(bytes: ByteArray): AudioClip? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(ByteSource(bytes))
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val c = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(format, null, null, 0)
            c.start()
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = false
            val out = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var idle = 0
            while (true) {
                if (!inputDone) {
                    val i = c.dequeueInputBuffer(timeoutUs)
                    if (i >= 0) {
                        val buf = c.getInputBuffer(i)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = c.dequeueOutputBuffer(info, timeoutUs)
                when {
                    o >= 0 -> {
                        idle = 0
                        val buf = c.getOutputBuffer(o)!!
                        val chunk = ByteArray(info.size)
                        buf.position(info.offset)
                        buf.get(chunk, 0, info.size)
                        out.write(if (floatPcm) floatToS16(chunk) else chunk)
                        c.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = c.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    else -> if (inputDone && ++idle > MAX_IDLE) break
                }
            }
            if (channels !in 1..2 || rate <= 0) return null
            val pcm = out.toByteArray()
            return if (pcm.isEmpty()) null else AudioClip(pcm, SpeechFormat.Pcm(rate, channels, 16))
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun floatToS16(bytes: ByteArray): ByteArray {
        val fb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
        val out = java.nio.ByteBuffer.allocate(fb.remaining() * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        while (fb.hasRemaining()) out.putShort((fb.get().coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        return out.array()
    }

    /** The clip in memory as a [MediaDataSource]. */
    private class ByteSource(private val data: ByteArray) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= data.size) return -1
            val n = minOf(size, data.size - position.toInt())
            System.arraycopy(data, position.toInt(), buffer, offset, n)
            return n
        }

        override fun getSize(): Long = data.size.toLong()
        override fun close() = Unit
    }

    private companion object {
        /** Output polls with nothing after the input ended (each [timeoutUs]): give up. */
        const val MAX_IDLE = 200
    }
}
