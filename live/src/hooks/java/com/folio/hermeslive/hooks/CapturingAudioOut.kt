package com.folio.hermeslive.hooks

import com.folio.hermes.cascade.AudioClip
import com.folio.hermes.cascade.ports.AudioOut
import com.folio.hermeslive.audio.Dbfs
import com.folio.hermeslive.audio.PlaybackMonitor
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

/**
 * Debug and staging only (`src/hooks`): Bill's voice into memory instead of the speaker, for the
 * emulator end-to-end tests. It plays in real time (so cut-ins, the pause gate and the music
 * policy behave as on a phone), is the [PlaybackMonitor] the barge-in gate reads, and records
 * one [Played] per clip with its timings on `System.nanoTime()` milliseconds (the actor's clock).
 *
 * [onLevel] gets the output level 0..1 like `AudioTrackOut`. [keepAudio]: keep each clip's PCM
 * in [Played.bytes] (off by default; tests that only time Bill need none).
 */
class CapturingAudioOut(
    private val onLevel: (Float) -> Unit = {},
    private val keepAudio: Boolean = false,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) : AudioOut, PlaybackMonitor {
    /** One clip: when it started and ended, its length, how much was heard, and whether a cut-in stopped it. */
    data class Played(
        val startedAtMs: Long,
        val endedAtMs: Long,
        val durationMs: Long,
        val playedMs: Long,
        val sampleRateHz: Int,
        val stopped: Boolean,
        val bytes: ByteArray?,
    )

    private val lock = Any()
    private val log = ArrayList<Played>()

    @Volatile private var current: Playing? = null

    @Volatile private var lastPlayedMs = 0L

    private class Playing(val clip: AudioClip, val startedAt: Long, val durationMs: Long, val levels: DoubleArray) {
        @Volatile var stopped = false
    }

    /** Every clip played so far, oldest first. */
    val played: List<Played> get() = synchronized(lock) { log.toList() }

    /** Forget [played]. */
    fun clear() = synchronized(lock) { log.clear() }

    override val playing: Boolean get() = current?.let { !it.stopped && nowMs() - it.startedAt < it.durationMs } ?: false

    override fun levelDb(): Double {
        val p = current ?: return Dbfs.FLOOR
        if (p.stopped) return Dbfs.FLOOR
        val at = nowMs() - p.startedAt
        if (at >= p.durationMs) return Dbfs.FLOOR
        return p.levels.getOrElse((at / CHUNK_MS).toInt()) { Dbfs.FLOOR }
    }

    override fun playedMs(): Long = current?.let { (nowMs() - it.startedAt).coerceIn(0, it.durationMs) } ?: lastPlayedMs

    override suspend fun play(clip: AudioClip): Long {
        val f = clip.format
        if (f.sampleRateHz <= 0 || f.frameBytes <= 0 || clip.bytes.size < f.frameBytes) return 0L
        val duration = clip.durationMs
        val chunkBytes = (f.sampleRateHz * CHUNK_MS / 1000).toInt() * f.frameBytes
        val levels = DoubleArray(((clip.bytes.size + chunkBytes - 1) / chunkBytes)) { i ->
            Dbfs.ofPcm16(clip.bytes, i * chunkBytes, minOf(clip.bytes.size, (i + 1) * chunkBytes))
        }
        val p = Playing(clip, nowMs(), duration, levels)
        current = p
        try {
            while (!p.stopped && coroutineContext.isActive && nowMs() - p.startedAt < duration) {
                onLevel(Dbfs.toUnit(levelDb()))
                delay(TICK_MS)
            }
        } finally {
            val ended = nowMs()
            val heard = (ended - p.startedAt).coerceIn(0, duration)
            lastPlayedMs = heard
            synchronized(lock) {
                log += Played(p.startedAt, ended, duration, heard, f.sampleRateHz, p.stopped || heard < duration, if (keepAudio) clip.bytes.copyOf() else null)
            }
            if (current === p) current = null
            onLevel(0f)
        }
        return lastPlayedMs
    }

    override fun stop() {
        current?.stopped = true
    }

    private companion object {
        const val CHUNK_MS = 20L
        const val TICK_MS = 10L
    }
}
