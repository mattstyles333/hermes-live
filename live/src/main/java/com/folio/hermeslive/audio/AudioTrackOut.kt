package com.folio.hermeslive.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.folio.hermes.cascade.AudioClip
import com.folio.hermes.cascade.ports.AudioOut
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlin.coroutines.coroutineContext

/**
 * Bill's voice out (R-SPK-5): an [AudioTrack] with `USAGE_VOICE_COMMUNICATION` (so the platform
 * echo canceller has the reference), low-latency streaming mode, PCM s16le at the clip's own
 * rate (OpenRouter TTS: 24 kHz mono). [stop] is instant: pause + flush, and the running [play]
 * returns the milliseconds actually heard (`played_ms`, from the playback head).
 *
 * It is also the [PlaybackMonitor] the barge-in gate reads: [levelDb] is the level of the
 * 20 ms chunk at the playback head (what the speaker emits now, not what was last written).
 * [onLevel] gets the same level as 0..1 for the orb (`CascadeActor.outputLevel`).
 *
 * One clip at a time (the cascade's `SpeechPlayer` plays in order).
 */
class AudioTrackOut(
    private val onLevel: (Float) -> Unit = {},
) : AudioOut, PlaybackMonitor {
    private val lock = Any()
    private var track: AudioTrack? = null
    private var trackRate = 0
    private var trackChannels = 0

    @Volatile private var current: Playing? = null

    @Volatile private var lastPlayedMs = 0L

    /** One clip in flight: its chunk levels and where its frames start on the head counter. */
    private class Playing(val track: AudioTrack, val rate: Int, val levels: DoubleArray, val chunkFrames: Int, val frames: Long, base: Long) {
        @Volatile var stopped = false

        /** Head position at the clip's start; 0 once the platform resets the head after the flush. */
        @Volatile var base: Long = base
    }

    override val playing: Boolean get() = current?.let { !it.stopped && headFrames(it) < it.frames } ?: false

    override fun levelDb(): Double {
        val p = current ?: return Dbfs.FLOOR
        if (p.stopped) return Dbfs.FLOOR
        val head = headFrames(p)
        if (head >= p.frames) return Dbfs.FLOOR
        return p.levels.getOrElse((head / p.chunkFrames).toInt()) { Dbfs.FLOOR }
    }

    override fun playedMs(): Long = current?.let { headFrames(it) * 1000 / it.rate } ?: lastPlayedMs

    override suspend fun play(clip: AudioClip): Long = withContext(Dispatchers.IO) {
        val format = clip.format
        val rate = format.sampleRateHz
        val channels = format.channels
        val frameBytes = format.frameBytes
        if (rate <= 0 || frameBytes <= 0 || clip.bytes.size < frameBytes) return@withContext 0L
        val t = trackFor(rate, channels)
        val totalFrames = (clip.bytes.size / frameBytes).toLong()
        val chunkFrames = rate / 50
        val chunkBytes = chunkFrames * frameBytes
        val levels = DoubleArray(((clip.bytes.size + chunkBytes - 1) / chunkBytes)) { i ->
            Dbfs.ofPcm16(clip.bytes, i * chunkBytes, minOf(clip.bytes.size, (i + 1) * chunkBytes))
        }
        val p = synchronized(lock) {
            t.pause()
            t.flush()
            Playing(t, rate, levels, chunkFrames, totalFrames, t.playbackHeadPosition.toLong() and 0xffffffffL).also { current = it }
        }
        var written = 0
        val usable = clip.bytes.size - clip.bytes.size % frameBytes
        try {
            t.play()
            while (written < usable && !p.stopped && coroutineContext.isActive) {
                val n = t.write(clip.bytes, written, minOf(chunkBytes * 2, usable - written), AudioTrack.WRITE_NON_BLOCKING)
                if (n < 0) break
                written += n
                onLevel(Dbfs.toUnit(levelDb()))
                if (n == 0) delay(5)
            }
            // Wait for the head to reach the end (the buffer drains), or a stop.
            val deadline = System.nanoTime() + (format.durationMs(clip.bytes.size.toLong()) + 1_000) * 1_000_000
            while (!p.stopped && coroutineContext.isActive && headFrames(p) < totalFrames && System.nanoTime() < deadline) {
                onLevel(Dbfs.toUnit(levelDb()))
                delay(10)
            }
        } finally {
            withContext(NonCancellable) {
                val heard = minOf(headFrames(p), totalFrames)
                halt(p)
                lastPlayedMs = heard * 1000 / rate
                onLevel(0f)
            }
        }
        lastPlayedMs
    }

    override fun stop() {
        current?.let(::halt)
    }

    /** Release the track (call end). */
    fun release() {
        stop()
        synchronized(lock) {
            track?.release()
            track = null
            trackRate = 0
        }
    }

    private fun halt(p: Playing) {
        synchronized(lock) {
            if (p.stopped) return
            p.stopped = true
            lastPlayedMs = minOf(headFrames(p), p.frames) * 1000 / p.rate
            runCatching {
                p.track.pause()
                p.track.flush()
            }
            if (current === p) current = null
        }
    }

    private fun headFrames(p: Playing): Long {
        val raw = p.track.playbackHeadPosition.toLong() and 0xffffffffL
        if (raw < p.base) p.base = 0
        return raw - p.base
    }

    private fun trackFor(rate: Int, channels: Int): AudioTrack = synchronized(lock) {
        track?.takeIf { trackRate == rate && trackChannels == channels }?.let { return it }
        track?.release()
        val mask = if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val min = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setChannelMask(mask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setBufferSizeInBytes(maxOf(min, rate * channels * 2 / 10))
            .build()
            .also {
                track = it
                trackRate = rate
                trackChannels = channels
            }
    }
}
