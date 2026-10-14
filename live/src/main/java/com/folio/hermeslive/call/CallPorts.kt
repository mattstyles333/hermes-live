package com.folio.hermeslive.call

import com.folio.hermes.ApprovalChoice
import com.folio.hermes.ApprovalRequest
import com.folio.hermes.ClarifyRequest
import com.folio.hermes.live.DelegationBackend
import com.folio.hermes.live.LiveHistoryMessage
import com.folio.hermes.live.VoiceLiveStatus

/**
 * Callbacks from a [LiveTransport]. The transport delivers every one on the main thread (the
 * engine and the bridge are single-threaded).
 */
interface TransportEvents {
    /** One `oai-events` data-channel message. */
    fun onMessage(text: String)

    /** The peer connection or data channel dropped. */
    fun onLost(reason: String)

    /** Hermes's audio level (0..1), polled about every 150 ms. */
    fun onRemoteLevel(level: Double)

    /** The local mic level (dBFS) over [durationMs]. Arrives while muted too. */
    fun onMicLevel(levelDb: Double, durationMs: Long)
}

/** One WebRTC session with the live model. Implemented over libwebrtc; faked in tests. */
interface LiveTransport {
    /**
     * Mic track, data channel `oai-events` (created before the offer), offer, ICE gathering
     * (≤10 s), then [exchange] the full offer SDP for the answer and apply it. Throws on failure.
     */
    suspend fun start(exchange: suspend (offerSdp: String) -> String)

    /** Send one data-channel message; false when the channel is not open. */
    fun send(message: String): Boolean

    /** Enable or disable the mic track. */
    fun setMicEnabled(enabled: Boolean)

    /** Silence Hermes's playback (during another call). */
    fun setSpeakerMuted(muted: Boolean)

    /** Tear down. Safe to call twice. No [TransportEvents.onLost] after this. */
    fun close()
}

/** Makes a [LiveTransport] for one connection attempt. */
fun interface LiveTransportFactory {
    fun create(events: TransportEvents): LiveTransport
}

/** The Hermes side of one call: auth, voice session, delegation backend, request answers. */
interface HermesLink {
    /** The Delegation backend (a [com.folio.hermes.HermesConversation] with surface voice-live). */
    val backend: DelegationBackend

    /** Make sure the token lasts at least [minValidityMs] and start the gateway. Throws when signed out. */
    suspend fun prepare(minValidityMs: Long)

    /** `POST /api/audio/voice-live/session`: the answer SDP. */
    suspend fun createVoiceSession(offerSdp: String, history: List<LiveHistoryMessage>): String

    suspend fun answerApproval(request: ApprovalRequest, choice: ApprovalChoice)

    suspend fun answerClarify(request: ClarifyRequest, answers: Map<String, String?>)

    /** Stored Hermes session id now (to resume within 30 minutes). */
    val storedSessionId: String?

    /** Disconnect the gateway. */
    fun close()

    /**
     * Hermes looks reachable: the gateway is not backing off after a failure or stopped. GPT-Live's
     * on-demand link reopens for a waiting result only then ([com.folio.hermeslive.call.ondemand.RealtimeLink.canOpen]).
     */
    val reachable: Boolean get() = true
}

/** Makes the [HermesLink] for a call, resuming [storedSessionId] when given. */
fun interface HermesLinkFactory {
    fun create(storedSessionId: String?): HermesLink
}

/** Short sounds for call events. */
enum class Earcon { Connected, Muted, Unmuted, Ended, Warning }

/**
 * The Android audio side of a call (mode, focus, routes). The engine decides; this applies.
 * Implementations report device changes, unplugs and other calls to [CallAudio.Listener].
 */
interface CallAudio {
    interface Listener {
        fun onDevicesChanged(devices: List<RouteOption>)
        fun onBecomingNoisy()
        /** Another (cellular or VoIP) call took the audio, or let it go. */
        fun onOtherCall(active: Boolean)
    }

    /** Save the current mode and communication device, then switch to MODE_IN_COMMUNICATION. */
    fun begin(listener: Listener)

    /** Restore exactly what [begin] saved, drop focus, stop listening. */
    fun end()

    /** Communication devices available now. */
    fun devices(): List<RouteOption>

    /** Route the call to [route]. */
    fun select(route: RouteOption)

    /** Hold focus as [mode] says. */
    fun setFocus(mode: FocusMode)

    /** Re-assert MODE_IN_COMMUNICATION after another call gave the audio back. */
    fun reassert()

    /** Another app is playing media now (to show "music in the gap"). */
    fun isMusicActive(): Boolean
}

/** Earcons, haptics and the proximity screen-off lock. */
interface CallEffects {
    fun earcon(kind: Earcon)
    fun haptic(strong: Boolean = false)
    /** Screen off near the ear (only meaningful on the earpiece route). */
    fun proximity(enabled: Boolean)
}

/** Reads the voice-live status for the settings screen. */
fun interface VoiceStatusSource {
    suspend fun status(): VoiceLiveStatus?
}
