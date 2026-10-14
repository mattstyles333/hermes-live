package com.folio.hermeslive.call.ondemand

import com.folio.hermes.telemetry.TelemetrySink
import com.folio.hermeslive.call.GapSignals
import com.folio.hermeslive.stt.VoiceDetector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Settings › Voice › "Save cost: close the voice session while agents work", and its timings. */
data class OnDemandSettings(
    /** Off: the session opens at the start and stays open for the whole call (the old behaviour). */
    val enabled: Boolean = true,
    /** Everything idle (model, playback, the user, notices) this long: the session closes. */
    val idleCloseMs: Long = 8_000L,
    /** Audio kept while closed, so the words before the voice detector fired are not lost. */
    val preRollMs: Long = 1_500L,
    /** The most audio held while a session opens (beyond it, the oldest goes). */
    val maxPendingMs: Long = 20_000L,
    /** An open that has not become ready by then counts as failed. */
    val openTimeoutMs: Long = 15_000L,
    /** After a failed open, a result waits this long before trying again (speech retries at once). */
    val retryMs: Long = 3_000L,
)

/** What the call screen shows about the session. */
enum class SessionView(val label: String) {
    /** The paid session is open. */
    Connected("Connected"),

    /** Opening or resuming. */
    Connecting("Connecting…"),

    /** Closed: the phone listens for the user and waits for results, at no cloud cost. */
    Listening("Listening (on phone)"),
}

/** The on-demand state for the call screen (the orb dims and a small indicator shows while [view] is [SessionView.Listening]). */
data class OnDemandUi(
    val enabled: Boolean = false,
    val view: SessionView = SessionView.Connecting,
    /** Total time closed in this call (cost saved). */
    val closedMs: Long = 0L,
) {
    /** The orb dims and the indicator shows. */
    val dimmed: Boolean get() = enabled && view == SessionView.Listening
}

/**
 * Keeps a realtime voice call open while its paid session comes and goes (ondemand slice).
 *
 * - **Local listening.** While no session is open, the phone keeps listening: the on-device
 *   Silero voice detector runs on the call's mic (the shared AEC capture) and the last
 *   [OnDemandSettings.preRollMs] of audio is kept. Nothing goes to the cloud.
 * - **Close.** After [OnDemandSettings.idleCloseMs] of all of: the model idle, playback done, the
 *   user silent (the server's and the phone's detectors), and no notice waiting or being spoken.
 *   Tasks keep running.
 * - **Reopen.** When the user starts speaking: the session resumes and the pre-roll plus
 *   everything since is flushed in before live audio ([LinkAudio.Fed]), so no first words are
 *   lost. When a task notice waits: the session resumes and the link injects it.
 * - **Music.** [gap] adds the phone's detector and an imminent result to the host's
 *   [GapSignals], so [com.folio.hermeslive.call.GapMusicPolicy] lets music back while closed and
 *   tasks run, and takes it away the moment speech is detected or a result is about to be spoken.
 * - **Debounce.** One open at a time (more triggers while opening do nothing); no close until the
 *   session has been ready and idle for the whole [OnDemandSettings.idleCloseMs]; a failed open
 *   is retried by new speech at once, by a waiting result after [OnDemandSettings.retryMs].
 * - **Fallback.** No voice detector (it failed to load): the session never closes.
 * - **Telemetry:** `ondemand.open` / `ondemand.resume` (reason, ms to ready, ok), `ondemand.close`
 *   (reason, ms open). Never audio or text.
 *
 * Threads: [start], [tick], [stop], [setMuted] on the call's thread; [onMic] and [micClosed] on the
 * mic thread. [wake] asks the call's thread for a [tick] now (the mic thread heard speech).
 */
class OnDemandController(
    private val link: RealtimeLink,
    private val settings: OnDemandSettings,
    /** Built on the mic thread at its first frame; null (or a throw) means no local listening, so never close. */
    private val vadFactory: () -> VoiceDetector?,
    /** The context note for a fresh session (the link calls it at most once per open). */
    private val context: () -> String?,
    private val clock: () -> Long,
    private val wake: () -> Unit = {},
    private val mic: MicHandoff = MicHandoff.None,
    private val telemetry: TelemetrySink = TelemetrySink.Default,
    /** The voice detector's frame (Silero: 512 samples at 16 kHz). */
    vadFrame: Int = 512,
) {
    private enum class Phase { Closed, Opening, Open }

    private val lock = Any()
    private val preRoll = PcmRing((settings.preRollMs * RATE / 1000).toInt())
    private val pending = PcmQueue((settings.maxPendingMs * RATE / 1000).toInt())
    private val framer = VadFramer(vadFrame)

    // Guarded by [lock].
    private var phase = Phase.Closed
    private var reason = OpenReason.Start
    private var requestedAt = 0L
    private var speechWanted = false

    /** Set by [flushLocked] (either thread); the call's thread does the bookkeeping at its next [tick]. */
    private var flushedAt: Long? = null
    private var flushDropped = 0L

    // Call thread only.
    private var started = false
    private var openedAt = 0L
    private var closedAt: Long? = null
    private var closedTotal = 0L
    private var idleSince: Long? = null
    private var failedAt: Long? = null
    private var everOpened = false

    // Mic thread only.
    private var vad: VoiceDetector? = null
    private var vadTried = false

    @Volatile private var vadOk = true

    @Volatile private var localSpeaking = false

    @Volatile private var muted = false

    /** The voice detector's state is stale (the mic was handed to the link): reset it at the next frame. */
    @Volatile private var vadResetDue = false

    private val _ui = MutableStateFlow(OnDemandUi(enabled = settings.enabled))
    val ui: StateFlow<OnDemandUi> = _ui.asStateFlow()

    /** The session may close now (on, and the phone can listen). */
    val closes: Boolean get() = settings.enabled && vadOk

    /** The phone's voice detector hears speech (also while closed). */
    val phoneHearsSpeech: Boolean get() = localSpeaking

    /** Open the session for the call's start. */
    fun start() {
        if (started) return
        started = true
        requestOpen(OpenReason.Start)
    }

    fun setMuted(on: Boolean) {
        muted = on
        if (on) {
            localSpeaking = false
            synchronized(lock) { preRoll.clear() }
        }
    }

    /** Hang-up: the session closes for good. */
    fun stop() {
        if (!started) return
        opened()
        started = false
        val was = synchronized(lock) {
            val p = phase
            phase = Phase.Closed
            pending.clear()
            preRoll.clear()
            p
        }
        if (was != Phase.Closed) {
            link.close()
            report("ondemand.close", mapOf("reason" to CloseReason.HangUp.wire, "ms" to clock() - openedAt))
        }
        closedAt?.let { closedTotal += clock() - it }
        closedAt = null
    }

    // ── mic thread ──────────────────────────────────────────────────────────────────────────

    /** 16 kHz mono PCM from the call's mic ([count] samples of [pcm]). */
    fun onMic(pcm: ShortArray, count: Int) {
        if (count <= 0) return
        if (muted) return
        val speech = detect(pcm, count)
        val startedSpeaking = speech && !localSpeaking
        localSpeaking = speech
        var poke = false
        synchronized(lock) {
            when (phase) {
                // OwnsMic: the link has its own capture once open; the host's frames until the handoff are dropped.
                Phase.Open -> if (link.audio == LinkAudio.Fed) link.sendAudio(pcm, count)
                Phase.Opening -> {
                    pending.append(pcm, count)
                    if (link.phase == LinkPhase.Ready) flushLocked()
                }
                Phase.Closed -> {
                    preRoll.append(pcm, count)
                    if (startedSpeaking && started) {
                        speechWanted = true
                        poke = true
                    }
                }
            }
        }
        if (poke) wake()
    }

    /** The mic loop ended: release the voice detector on its own thread. */
    fun micClosed() {
        runCatching { vad?.release() }
        vad = null
        vadTried = false
        framer.reset()
        localSpeaking = false
    }

    private fun detect(pcm: ShortArray, count: Int): Boolean {
        // Save cost off: the session never closes, so the phone does not listen (no detector is built).
        if (!settings.enabled) return false
        if (vadResetDue) {
            vadResetDue = false
            framer.reset()
            runCatching { vad?.reset() }
        }
        if (!vadTried) {
            vadTried = true
            vad = runCatching { vadFactory() }.getOrNull()
            if (vad == null) {
                vadOk = false
                report("ondemand.vad", mapOf("ok" to false), warn = true)
            }
        }
        val v = vad ?: return false
        var speaking = localSpeaking
        try {
            framer.push(pcm, count) { frame -> speaking = v.accept(frame) }
        } catch (e: Exception) {
            vadOk = false
            return false
        }
        return speaking
    }

    // ── call thread ─────────────────────────────────────────────────────────────────────────

    /** Re-evaluate: open for speech or a waiting notice, finish an open, close when idle. Call every ~150 ms and on [wake]. */
    fun tick() {
        if (!started) return
        val now = clock()
        val wantSpeech = synchronized(lock) {
            // A muted user feeds no frames: the call's thread finishes the open then.
            if (phase == Phase.Opening && link.phase == LinkPhase.Ready) flushLocked()
            speechWanted.also { speechWanted = false }
        }
        opened()
        when (synchronized(lock) { phase }) {
            Phase.Closed -> when {
                wantSpeech -> requestOpen(OpenReason.Speech)
                link.hasPendingNotice() && failedAt?.let { now - it >= settings.retryMs } != false && link.canOpen() -> requestOpen(OpenReason.Result)
                else -> Unit
            }
            Phase.Opening -> when (link.phase) {
                LinkPhase.Ready -> Unit
                LinkPhase.Failed -> openFailed(now, "failed")
                LinkPhase.Opening, LinkPhase.Closed -> if (now - requestedAt >= settings.openTimeoutMs) openFailed(now, "timeout")
            }
            Phase.Open -> closeIfIdle(now)
        }
        publish(now)
    }

    /** The host's music signals plus what only the controller knows (the phone hears speech; a result is about to be spoken). */
    fun gap(base: GapSignals): GapSignals {
        val opening = synchronized(lock) { phase == Phase.Opening && reason == OpenReason.Result }
        val resultSoon = opening || link.hasPendingNotice()
        return base.copy(
            userSpeaking = base.userSpeaking || localSpeaking,
            remoteSpeaking = base.remoteSpeaking || resultSoon,
        )
    }

    private fun requestOpen(why: OpenReason) {
        val now = clock()
        synchronized(lock) {
            if (phase != Phase.Closed) return
            phase = Phase.Opening
            reason = why
            requestedAt = now
            pending.clear()
            // Speech: the pre-roll goes in first, then what the mic hears while the session opens.
            if (why == OpenReason.Speech) pending.append(preRoll.snapshot())
            preRoll.clear()
        }
        idleSince = null
        closedAt?.let { closedTotal += now - it }
        closedAt = null
        val ok = runCatching { link.open(OpenRequest(why, context)) }.getOrDefault(false)
        if (!ok) openFailed(now, "refused")
        publish(now)
    }

    /**
     * Opening → open, under the lock (either thread): the held audio goes in first as one call, so
     * no live frame overtakes it. The bookkeeping waits for [opened] on the call's thread.
     */
    private fun flushLocked() {
        val held = pending.drain()
        if (held.isNotEmpty()) link.sendAudio(held, held.size)
        flushDropped = pending.dropped
        pending.clear()
        phase = Phase.Open
        flushedAt = clock()
    }

    /** Call thread: an open finished (flushed): times, telemetry, and the mic handoff for a link that owns it. */
    private fun opened() {
        val (at, dropped, why, asked) = synchronized(lock) {
            val a = flushedAt ?: return
            flushedAt = null
            Opened(a, flushDropped, reason, requestedAt)
        }
        openedAt = at
        failedAt = null
        everOpened = true
        val kind = link.lastOpen ?: OpenKind.Fresh
        report(
            if (kind == OpenKind.Resumed) "ondemand.resume" else "ondemand.open",
            mapOf("reason" to why.wire, "ms" to at - asked, "ok" to true, "dropped_ms" to dropped * 1000 / RATE),
        )
        if (link.audio == LinkAudio.OwnsMic) {
            runCatching { mic.release() }
            localSpeaking = false
            vadResetDue = true
        }
    }

    private data class Opened(val at: Long, val dropped: Long, val reason: OpenReason, val requestedAt: Long)

    private fun openFailed(now: Long, code: String) {
        val r = synchronized(lock) {
            if (phase != Phase.Opening) return
            phase = Phase.Closed
            pending.clear()
            reason
        }
        failedAt = now
        closedAt = now
        runCatching { link.close() }
        report("ondemand.open", mapOf("reason" to r.wire, "ms" to now - requestedAt, "ok" to false, "code" to code), warn = true)
    }

    private fun closeIfIdle(now: Long) {
        if (!closes) {
            idleSince = null
            return
        }
        val idle = link.isIdle() && !link.speaking() && !link.userSpeaking() && !localSpeaking && !link.hasPendingNotice() &&
            link.phase == LinkPhase.Ready
        if (!idle) {
            idleSince = null
            return
        }
        val since = idleSince ?: now.also { idleSince = it }
        if (now - since < settings.idleCloseMs) return
        synchronized(lock) {
            phase = Phase.Closed
            preRoll.clear()
        }
        idleSince = null
        closedAt = now
        link.close()
        if (link.audio == LinkAudio.OwnsMic) {
            vadResetDue = true
            runCatching { mic.reclaim() }
        }
        report("ondemand.close", mapOf("reason" to CloseReason.Idle.wire, "ms" to now - openedAt))
    }

    private fun publish(now: Long) {
        val p = synchronized(lock) { phase }
        val view = when (p) {
            Phase.Open -> SessionView.Connected
            Phase.Opening -> SessionView.Connecting
            // Never open yet (the first open failed): the engine shows why; the phone is not "listening" for a session.
            Phase.Closed -> if (everOpened) SessionView.Listening else SessionView.Connecting
        }
        val closed = closedTotal + (closedAt?.let { now - it } ?: 0L)
        val next = OnDemandUi(enabled = settings.enabled, view = view, closedMs = closed)
        if (next != _ui.value) _ui.value = next
    }

    private fun report(name: String, attrs: Map<String, Any?>, warn: Boolean = false) {
        runCatching { telemetry.event(name, attrs, warn) }
    }

    companion object {
        /** The mic's rate (the speech pipeline's 16 kHz). */
        const val RATE = 16_000
    }
}
