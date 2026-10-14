package com.folio.hermeslive.call

import com.folio.hermes.telemetry.Telemetry
import com.folio.hermes.telemetry.TelemetrySink
import com.folio.hermeslive.audio.AudioSource
import com.folio.hermeslive.call.ondemand.LinkAudio
import com.folio.hermeslive.call.ondemand.LinkPhase
import com.folio.hermeslive.call.ondemand.MicHandoff
import com.folio.hermeslive.call.ondemand.OnDemandController
import com.folio.hermeslive.call.ondemand.OnDemandSettings
import com.folio.hermeslive.call.ondemand.OnDemandUi
import com.folio.hermeslive.call.ondemand.OpenKind
import com.folio.hermeslive.call.ondemand.OpenReason
import com.folio.hermeslive.call.ondemand.OpenRequest
import com.folio.hermeslive.call.ondemand.RealtimeLink
import com.folio.hermeslive.data.LiveSettings
import com.folio.hermeslive.stt.VoiceDetector
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Turns the held audio (16 kHz mono PCM: the pre-roll plus what the phone heard while the session
 * opened) into text with on-device speech-to-text. Blocking; called off the main thread. Null:
 * nothing usable (no model on the phone, silence).
 */
fun interface PreRollTranscriber {
    fun transcribe(pcm: ShortArray): String?

    /** The call ended: free the model. */
    fun close() {}
}

/**
 * GPT-Live's on-demand session (the shared controller's host side, ondemand slice): a
 * [RealtimeLink] over [CallEngine]'s session control, the phone's own mic capture while the
 * session is closed (for the controller's voice detector and pre-roll), and the hand-over of the
 * mic to WebRTC once a session is ready ([LinkAudio.OwnsMic]).
 *
 * WebRTC sends only its live mic track; no API takes recorded audio. So the held audio is
 * transcribed on the phone ([transcriber]) and told to the voice as an instruction ("While the
 * line was paused the user said: …"). With no transcriber (Bill's speech models are not on the
 * phone) the voice is told the start may be missing.
 *
 * Built per call by [GptLiveOnDemandFactory]; only when on-demand sessions are on.
 */
class GptLiveOnDemand(
    private val engine: CallEngine,
    settings: OnDemandSettings,
    vadFactory: () -> VoiceDetector?,
    private val micFactory: () -> AudioSource?,
    private val transcriber: PreRollTranscriber?,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    telemetry: TelemetrySink = TelemetrySink.Default,
) {
    private val link = Link()
    private val handoff = Handoff()
    val controller = OnDemandController(
        link = link,
        settings = settings,
        vadFactory = vadFactory,
        // History is sent with the session request (transcript + open tasks); no separate note.
        context = { null },
        clock = clock,
        wake = { scope.launch { tick() } },
        mic = handoff,
        telemetry = telemetry,
    )

    /** For the call screen: connected / connecting / listening on the phone, and the dimmed orb. */
    val ui: StateFlow<OnDemandUi> get() = controller.ui

    /** The phone's own voice detector hears the user (also while the session is closed). */
    val phoneHearsSpeech: Boolean get() = controller.phoneHearsSpeech

    /** The session is closed on purpose (no cloud cost). */
    val closed: Boolean get() = link.phase == LinkPhase.Closed || link.phase == LinkPhase.Failed

    @Volatile private var micThread: Thread? = null
    private var started = false

    /** The call started (the engine is connecting): capture the mic until WebRTC takes it, and open. */
    fun start() {
        if (started) return
        started = true
        handoff.reclaim()
        controller.start()
    }

    fun tick() {
        if (started) controller.tick()
    }

    fun setMuted(muted: Boolean) = controller.setMuted(muted)

    fun gap(base: GapSignals): GapSignals = controller.gap(base)

    fun stop() {
        if (!started) return
        started = false
        controller.stop()
        handoff.release()
        transcriber?.let { t -> scope.launch { withContext(io) { runCatching { t.close() } } } }
    }

    // ── the engine's events ──

    /** `session.started` on the engine's (re)opened session. */
    fun sessionReady() {
        if (link.phase == LinkPhase.Opening || link.phase == LinkPhase.Closed) link.phase = LinkPhase.Ready
        tick()
    }

    /** An open (or reopen) failed; the call stays and the controller retries. */
    fun sessionFailed() {
        if (link.phase == LinkPhase.Opening) link.phase = LinkPhase.Failed
        tick()
    }

    // ── the link ──

    private inner class Link : RealtimeLink {
        override val audio = LinkAudio.OwnsMic

        @Volatile override var phase: LinkPhase = LinkPhase.Closed

        override var lastOpen: OpenKind? = null
            private set

        @Volatile private var reason = OpenReason.Start

        override fun open(request: OpenRequest): Boolean {
            reason = request.reason
            phase = LinkPhase.Opening
            val ok = when {
                engine.sessionOpen.value -> {
                    phase = LinkPhase.Ready
                    true
                }
                // The call's first open: the engine is already connecting.
                engine.connecting -> true
                else -> engine.reopenSession(null)
            }
            if (!ok) phase = LinkPhase.Failed
            // A GPT-Live session never resumes: each open is a new session with history.
            lastOpen = OpenKind.Fresh
            return ok
        }

        override fun close() {
            phase = LinkPhase.Closed
            engine.dropSession()
        }

        override fun sendAudio(pcm: ShortArray, count: Int) {
            if (count < MIN_PRE_ROLL_SAMPLES) return
            val held = pcm.copyOf(count)
            val asked = reason
            scope.launch {
                val text = transcriber?.let { t -> withContext(io) { runCatching { t.transcribe(held) }.getOrNull() } }?.trim()?.ifEmpty { null }
                Telemetry.event("ondemand.pre_roll", mapOf("engine" to "gpt-live", "ms" to count * 1000L / OnDemandController.RATE, "text" to (text != null), "stt" to (transcriber != null)))
                engine.tellPreRoll(text, missed = text == null && transcriber == null && asked == OpenReason.Speech)
            }
        }

        override fun isIdle(): Boolean = engine.voiceIdle()

        override fun hasPendingNotice(): Boolean = engine.pendingResults.value > 0

        override fun userSpeaking(): Boolean = engine.userSpeakingNow

        override fun speaking(): Boolean = engine.voiceSpeakingNow
    }

    // ── the phone's mic while WebRTC does not have it ──

    private inner class Handoff : MicHandoff {
        override fun release() {
            val t = micThread ?: return
            micThread = null
            t.interrupt()
        }

        override fun reclaim() {
            if (micThread != null || !started) return
            val source = runCatching { micFactory() }.getOrNull() ?: return
            micThread = Thread({
                val buf = ShortArray(source.sampleRate / 50)
                try {
                    source.open()
                    while (!Thread.currentThread().isInterrupted) {
                        val n = source.read(buf)
                        if (n < 0) break
                        if (n > 0) controller.onMic(buf, n)
                    }
                } catch (e: Exception) {
                    Telemetry.warn("audio.error", null, mapOf("engine" to "gpt-live", "where" to "ondemand_mic", "type" to e.javaClass.simpleName))
                } finally {
                    runCatching { source.close() }
                    controller.micClosed()
                }
            }, "gptlive-ondemand-mic").apply { isDaemon = true; start() }
        }
    }

    companion object {
        /** Less than this (0.3 s) is not worth transcribing. */
        const val MIN_PRE_ROLL_SAMPLES = OnDemandController.RATE * 3 / 10
    }
}

/** Builds a call's [GptLiveOnDemand] when on-demand sessions are on for GPT-Live; else null (the session stays open, as before). */
fun interface GptLiveOnDemandFactory {
    fun create(engine: CallEngine): GptLiveOnDemand?

    companion object {
        val Off = GptLiveOnDemandFactory { null }

        /**
         * GPT-Live's on-demand settings. Off on this branch: the hub's merge maps this onto the
         * ondemand slice's `LiveSettings.gptLiveSaveCost` / `onDemandSettings(engine)`.
         */
        fun settings(@Suppress("UNUSED_PARAMETER") live: LiveSettings): OnDemandSettings = OnDemandSettings(enabled = false)
    }
}
