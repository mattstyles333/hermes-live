package com.folio.hermeslive.audio

import com.folio.hermes.cascade.ports.AudioOut

/**
 * One cascade call's audio: what the speech-to-text hears ([source]) and where Bill plays ([out],
 * with [playback] for the barge-in gate). [release] runs when the call ends.
 */
class CascadeAudioIo(
    val source: AudioSource,
    val out: AudioOut,
    val playback: PlaybackMonitor,
    val release: () -> Unit = {},
)

/**
 * Builds a call's [CascadeAudioIo]. The app's is [Device]: the mic and an `AudioTrack`. Debug
 * builds can swap in the corpus and a capturing output (`BuildHooks.cascadeAudio`, S-F2b's
 * end-to-end tests); release and staging builds have no such hook.
 */
fun interface CascadeAudioFactory {
    /** [echoCancel]: capture with the phone's call processing (R-SPK-5). [onLevel]: Bill's output level, 0..1. */
    fun create(echoCancel: Boolean, onLevel: (Float) -> Unit): CascadeAudioIo

    companion object {
        /**
         * The phone: `VOICE_COMMUNICATION` with the echo canceller when echo cancel is on (the
         * default), else `VOICE_RECOGNITION` (no call processing); Bill on `USAGE_VOICE_COMMUNICATION`.
         */
        val Device = CascadeAudioFactory { echoCancel, onLevel ->
            val out = AudioTrackOut(onLevel)
            CascadeAudioIo(
                source = MicAudioSource(if (echoCancel) CaptureSource.VOICE_COMMUNICATION else CaptureSource.VOICE_RECOGNITION),
                out = out,
                playback = out,
                release = out::release,
            )
        }
    }
}
