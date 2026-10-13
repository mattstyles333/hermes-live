package com.folio.hermeslive.webrtc

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import com.folio.hermes.telemetry.Telemetry
import com.folio.hermeslive.call.LiveTransport
import com.folio.hermeslive.call.RemoteLevelReader
import com.folio.hermeslive.call.StatsEntry
import com.folio.hermeslive.call.TransportEvents
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.log10
import kotlin.math.sqrt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.CandidatePairChangeEvent
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.IceCandidateErrorEvent
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * [LiveTransport] over libwebrtc, following the desktop client: mic track with echo
 * cancellation, noise suppression and AGC (platform AEC/NS when the phone has them), data channel
 * `oai-events` created before the offer, ICE gathering ≤10 s, the full local SDP (with its
 * candidates, byte-exact) to the exchange, the answer applied, remote audio played by the audio
 * device module as voice-communication audio. Stats are polled every 150 ms for Hermes's level;
 * mic samples give the local level (RMS dBFS over ~50 ms). All [TransportEvents] are posted to the main thread.
 */
class WebRtcTransport(context: Context, private val events: TransportEvents) : LiveTransport {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val levelReader = RemoteLevelReader()
    private val gathered = CompletableDeferred<Unit>()
    @Volatile private var closed = false

    private var adm: JavaAudioDeviceModule? = null
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var source: AudioSource? = null
    private var mic: AudioTrack? = null
    private var channel: DataChannel? = null
    private var disconnectTimer: Runnable? = null
    private var candidateErrors = 0

    // Mic level accumulation on the audio thread (posted every ~50 ms).
    private var micSquares = 0.0
    private var micSamples = 0
    private var micRate = 48_000

    override suspend fun start(exchange: suspend (String) -> String) {
        ensureInitialized(app)
        val module = JavaAudioDeviceModule.builder(app)
            .setUseHardwareAcousticEchoCanceler(JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported())
            .setUseHardwareNoiseSuppressor(JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported())
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setSamplesReadyCallback { samples -> onMicSamples(samples.data, samples.sampleRate, samples.channelCount) }
            .createAudioDeviceModule()
        adm = module
        val f = PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options())
            .setAudioDeviceModule(module)
            .createPeerConnectionFactory()
        factory = f

        val constraints = MediaConstraints().apply {
            mandatory += MediaConstraints.KeyValuePair("googEchoCancellation", "true")
            mandatory += MediaConstraints.KeyValuePair("googNoiseSuppression", "true")
            mandatory += MediaConstraints.KeyValuePair("googAutoGainControl", "true")
            mandatory += MediaConstraints.KeyValuePair("googHighpassFilter", "true")
        }
        val audioSource = f.createAudioSource(constraints)
        source = audioSource
        val track = f.createAudioTrack("hermes-live-mic", audioSource)
        mic = track

        val config = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        }
        val pc = f.createPeerConnection(config, observer) ?: error("Could not create the peer connection")
        peer = pc
        pc.addTrack(track, listOf("hermes-live"))

        // Register the data channel before the offer so its m-line is negotiated.
        val dc = pc.createDataChannel("oai-events", DataChannel.Init()) ?: error("Could not open the event channel")
        channel = dc
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit

            override fun onStateChange() {
                if (dc.state() == DataChannel.State.CLOSED) {
                    if (!closed) Telemetry.warn("webrtc.datachannel_closed")
                    lost("data channel closed")
                }
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                if (buffer.binary) return
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                val text = String(bytes, Charsets.UTF_8)
                main.post { if (!closed) events.onMessage(text) }
            }
        })

        val offer = sdp { observer -> pc.createOffer(observer, MediaConstraints()) }
        setSdp { observer -> pc.setLocalDescription(observer, offer) }
        // Trickle is fine after the timeout: the vendor answers with the candidates it has.
        if (withTimeoutOrNull(ICE_GATHER_TIMEOUT_MS) { gathered.await() } == null) {
            Telemetry.event("webrtc.ice_gather_timeout", mapOf("timeout_ms" to ICE_GATHER_TIMEOUT_MS))
        }
        check(!closed) { "closed" }
        val local = pc.localDescription?.description ?: error("Missing local SDP offer")
        val answer = exchange(local)
        check(!closed) { "closed" }
        setSdp { observer -> pc.setRemoteDescription(observer, SessionDescription(SessionDescription.Type.ANSWER, answer)) }
        pollStats()
    }

    override fun send(message: String): Boolean {
        val dc = channel ?: return false
        if (closed || dc.state() != DataChannel.State.OPEN) return false
        return dc.send(DataChannel.Buffer(ByteBuffer.wrap(message.toByteArray(Charsets.UTF_8)), false))
    }

    override fun setMicEnabled(enabled: Boolean) {
        mic?.setEnabled(enabled)
    }

    override fun setSpeakerMuted(muted: Boolean) {
        adm?.setSpeakerMute(muted)
    }

    override fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        disconnectTimer?.let(main::removeCallbacks)
        runCatching { channel?.unregisterObserver() }
        runCatching { channel?.close() }
        runCatching { channel?.dispose() }
        runCatching { peer?.close() }
        runCatching { peer?.dispose() }
        runCatching { source?.dispose() }
        runCatching { factory?.dispose() }
        runCatching { adm?.release() }
        channel = null
        peer = null
        source = null
        mic = null
        factory = null
        adm = null
    }

    private fun lost(reason: String) {
        main.post { if (!closed) events.onLost(reason) }
    }

    private fun pollStats() {
        scope.launch {
            while (isActive) {
                delay(STATS_INTERVAL_MS)
                val pc = peer ?: break
                runCatching {
                    pc.getStats { report ->
                        val entries = report.statsMap.values.map { StatsEntry(it.type, it.members) }
                        val level = synchronized(levelReader) { levelReader.read(entries) } ?: return@getStats
                        main.post { if (!closed) events.onRemoteLevel(level) }
                    }
                }
            }
        }
    }

    private fun onMicSamples(data: ByteArray, sampleRate: Int, channels: Int) {
        if (closed) return
        micRate = sampleRate * channels.coerceAtLeast(1)
        var i = 0
        while (i + 1 < data.size) {
            val s = ((data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xFF)).toShort().toDouble() / 32768.0
            micSquares += s * s
            micSamples++
            i += 2
        }
        if (micSamples * 1000L / micRate >= MIC_WINDOW_MS) {
            val rms = sqrt(micSquares / micSamples)
            val db = if (rms <= 1e-6) -120.0 else 20 * log10(rms)
            val ms = micSamples * 1000L / micRate
            micSquares = 0.0
            micSamples = 0
            main.post { if (!closed) events.onMicLevel(db, ms) }
        }
    }

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            if (state == PeerConnection.IceConnectionState.FAILED && !closed) Telemetry.warn("webrtc.ice_failed")
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            if (state == PeerConnection.IceGatheringState.COMPLETE) gathered.complete(Unit)
        }
        override fun onIceCandidate(candidate: IceCandidate) = Unit
        override fun onIceCandidateError(event: IceCandidateErrorEvent) {
            // STUN/TURN errors are often harmless; the first few say why a connection never formed.
            if (candidateErrors++ < 3) Telemetry.event("webrtc.ice_candidate_error", mapOf("error_code" to event.errorCode))
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(dataChannel: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
            // Remote audio plays through the audio device module once the track is enabled.
            receiver.track()?.setEnabled(true)
        }
        override fun onTrack(transceiver: RtpTransceiver) {
            transceiver.receiver?.track()?.setEnabled(true)
        }
        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            main.post {
                if (closed) return@post
                when (state) {
                    PeerConnection.PeerConnectionState.FAILED -> {
                        Telemetry.warn("webrtc.connection_failed")
                        events.onLost("connection failed")
                    }
                    PeerConnection.PeerConnectionState.DISCONNECTED -> {
                        Telemetry.event("webrtc.disconnected")
                        // Mobile networks blip; give ICE a few seconds to recover before calling it lost.
                        val timer = Runnable {
                            if (!closed) {
                                Telemetry.warn("webrtc.lost", null, mapOf("grace_ms" to DISCONNECT_GRACE_MS))
                                events.onLost("connection lost")
                            }
                        }
                        disconnectTimer = timer
                        main.postDelayed(timer, DISCONNECT_GRACE_MS)
                    }
                    PeerConnection.PeerConnectionState.CONNECTED -> disconnectTimer?.let(main::removeCallbacks)
                    else -> Unit
                }
            }
        }
    }

    private suspend fun sdp(call: (SdpObserver) -> Unit): SessionDescription = suspendCancellableCoroutine { cont ->
        call(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) { if (cont.isActive) cont.resume(description) }
            override fun onSetSuccess() = Unit
            override fun onCreateFailure(error: String?) { if (cont.isActive) cont.resumeWithException(IllegalStateException("createOffer: $error")) }
            override fun onSetFailure(error: String?) = Unit
        })
    }

    private suspend fun setSdp(call: (SdpObserver) -> Unit): Unit = suspendCancellableCoroutine { cont ->
        call(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) = Unit
            override fun onSetSuccess() { if (cont.isActive) cont.resume(Unit) }
            override fun onCreateFailure(error: String?) = Unit
            override fun onSetFailure(error: String?) { if (cont.isActive) cont.resumeWithException(IllegalStateException("setDescription: $error")) }
        })
    }

    companion object {
        const val ICE_GATHER_TIMEOUT_MS = 10_000L
        const val STATS_INTERVAL_MS = 150L
        const val MIC_WINDOW_MS = 50L
        const val DISCONNECT_GRACE_MS = 5_000L

        @Volatile private var initialized = false

        private fun ensureInitialized(context: Context) {
            if (initialized) return
            synchronized(this) {
                if (initialized) return
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions(),
                )
                initialized = true
            }
        }
    }
}
