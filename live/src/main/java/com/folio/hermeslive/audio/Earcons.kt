package com.folio.hermeslive.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.VibratorManager
import com.folio.hermeslive.call.CallEffects
import com.folio.hermeslive.call.Earcon
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** Synthesises the earcons: soft sine notes with short fades, quiet by design. Pure. */
object EarconSynth {
    const val SAMPLE_RATE = 24_000

    /** Notes (Hz) and per-note length (ms) for each earcon. */
    fun notes(kind: Earcon): Pair<List<Double>, Int> = when (kind) {
        Earcon.Connected -> listOf(659.25, 880.0) to 110
        Earcon.Muted -> listOf(783.99, 587.33) to 70
        Earcon.Unmuted -> listOf(587.33, 783.99) to 70
        Earcon.Ended -> listOf(880.0, 659.25, 523.25) to 110
        Earcon.Warning -> listOf(740.0, 740.0) to 90
    }

    fun render(kind: Earcon, amplitude: Double = 0.16): ShortArray {
        val (freqs, ms) = notes(kind)
        val perNote = SAMPLE_RATE * ms / 1000
        val gap = SAMPLE_RATE * 25 / 1000
        val fade = SAMPLE_RATE * 12 / 1000
        val out = ShortArray(freqs.size * (perNote + gap))
        var at = 0
        for (f in freqs) {
            for (i in 0 until perNote) {
                val env = min(1.0, min(i.toDouble() / fade, (perNote - i).toDouble() / (fade * 3)))
                val v = sin(2 * PI * f * i / SAMPLE_RATE) * amplitude * env
                out[at + i] = (v * Short.MAX_VALUE).toInt().toShort()
            }
            at += perNote + gap
        }
        return out
    }
}

/**
 * [CallEffects] on the phone: earcons on the call's signalling stream (they follow the call's
 * route), light predefined haptics, and the proximity lock that turns the screen off at the ear.
 */
class AndroidCallEffects(context: Context) : CallEffects {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val vibrator = app.getSystemService(VibratorManager::class.java)?.defaultVibrator
    private val power = app.getSystemService(PowerManager::class.java)
    private val proximityLock: PowerManager.WakeLock? =
        if (power?.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) == true) {
            power.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "HermesLive:proximity").apply { setReferenceCounted(false) }
        } else {
            null
        }

    override fun earcon(kind: Earcon) {
        val pcm = EarconSynth.render(kind)
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(EarconSynth.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
        }.getOrNull() ?: return
        runCatching {
            track.write(pcm, 0, pcm.size)
            track.play()
        }
        val ms = pcm.size * 1000L / EarconSynth.SAMPLE_RATE + 200
        main.postDelayed({ runCatching { track.release() } }, ms)
    }

    override fun haptic(strong: Boolean) {
        val effect = VibrationEffect.createPredefined(if (strong) VibrationEffect.EFFECT_DOUBLE_CLICK else VibrationEffect.EFFECT_TICK)
        runCatching { vibrator?.vibrate(effect) }
    }

    override fun proximity(enabled: Boolean) {
        val lock = proximityLock ?: return
        if (enabled && !lock.isHeld) {
            lock.acquire(4 * 60 * 60_000L)
        } else if (!enabled && lock.isHeld) {
            lock.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
        }
    }
}
