package com.folio.hermeslive.cascade

import com.folio.hermes.ApprovalChoice
import com.folio.hermes.cascade.CascadeUiState
import com.folio.hermes.cascade.Event
import com.folio.hermes.cascade.SpeechKind
import com.folio.hermes.cascade.TaskUiStatus
import com.folio.hermes.cascade.TurnPhase
import com.folio.hermes.telemetry.Telemetry
import com.folio.hermeslive.audio.CascadeAudioFactory
import com.folio.hermeslive.audio.CascadeAudioIo
import com.folio.hermeslive.call.CallActivity
import com.folio.hermeslive.call.CallAudio
import com.folio.hermeslive.call.CallEffects
import com.folio.hermeslive.call.CallPhase
import com.folio.hermeslive.call.CallRequest
import com.folio.hermeslive.call.CallSummary
import com.folio.hermeslive.call.CallUiState
import com.folio.hermeslive.call.Earcon
import com.folio.hermeslive.call.FocusMode
import com.folio.hermeslive.call.GapMusicPolicy
import com.folio.hermeslive.call.GapSignals
import com.folio.hermeslive.call.RouteChoice
import com.folio.hermeslive.call.RouteKind
import com.folio.hermeslive.call.RouteOption
import com.folio.hermeslive.call.RouteSelector
import com.folio.hermeslive.call.VoiceCall
import com.folio.hermeslive.data.LiveSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Bill (the cascade, spec v2) behind the same [VoiceCall] port as GPT-Live: the call service,
 * CallStyle notification, tile, headset buttons and approvals notifications work unchanged, and
 * the call keeps today's audio rules: the routes and their fallback, music in the gaps
 * ([GapMusicPolicy]), the other-call pause, earcons, haptics and the earpiece proximity lock.
 *
 * A call: the [runtime] (built once while the gate is open: actor, store, gateway) gets this call's
 * mic and speaker ([CascadeAudioFactory]) and speech-to-text ([CallSpeechFactory], models loaded
 * off the main thread); the actor starts; the call screen draws the actor's state. Hang-up stops
 * Bill but not the tasks: [offCall] starts the off-call service while the call is still in the
 * foreground (R-CONV-3), then the mic and models are released.
 *
 * Runs on [scope]'s thread (Main), like [com.folio.hermeslive.call.CallEngine].
 */
class CascadeCallEngine(
    private val scope: CoroutineScope,
    private val settings: () -> LiveSettings,
    private val runtime: () -> CascadeAppRuntime?,
    private val speech: CallSpeechFactory,
    private val audioFactory: () -> CascadeAudioFactory,
    private val audio: CallAudio,
    private val effects: CallEffects,
    /** Start the off-call service for this many running tasks; false if not started (none running, or refused). */
    private val offCall: (tasksRunning: Int) -> Boolean,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) : VoiceCall, CallAudio.Listener {
    private val _state = MutableStateFlow(CallUiState())
    override val state: StateFlow<CallUiState> = _state.asStateFlow()

    private var rt: CascadeAppRuntime? = null
    private var call: CallSpeech? = null
    private var source: MutableSource? = null
    private var audioIo: CascadeAudioIo? = null
    private var callSettings = LiveSettings()
    private var policy = GapMusicPolicy()
    private var devices: List<RouteOption> = emptyList()
    private var route = RouteChoice(null, manual = false)
    private var lastRouteKind: RouteKind? = null
    private var otherCall = false
    private var autoMuted = false
    private var liveAtMs: Long? = null
    private var startJob: Job? = null
    private var tickJob: Job? = null
    private var epoch = 0

    /** The last call's audio, held while its goodbye plays (at most [GOODBYE_CAP_MS]). */
    private var teardown: Teardown? = null

    /** The runtime of the current call (the call screen draws its actor). */
    val session: CascadeAppRuntime? get() = rt

    /** The mic level 0..1 (the listening well). */
    val micLevel: Float get() = call?.let { ((it.inputLevelDb + 60) / 50).toFloat().coerceIn(0f, 1f) } ?: 0f

    // ── commands ────────────────────────────────────────────────────────────────────────────

    override fun start(request: CallRequest) {
        // "Talk again" while the last goodbye plays: release the last call's audio first.
        teardown?.let { releaseNow(it, earcon = false) }
        if (_state.value.inCall) {
            if (request.muted && !_state.value.muted) toggleMute()
            // During a call a typed request could interleave with speech: Bill takes it by voice.
            if (request.prompt != null) _state.update { it.copy(notice = "Bill takes requests by voice. Say it, and he'll pass it on.") }
            return
        }
        callSettings = settings()
        val r = runtime()
        if (r == null) {
            _state.value = CallUiState(phase = CallPhase.Failed, error = "Bill can't start. Sign in to Hermes, and check the OpenRouter key in Settings › Voice.")
            return
        }
        rt = r
        val attempt = ++epoch
        policy = GapMusicPolicy(callSettings.gapSettings())
        otherCall = false
        autoMuted = false
        liveAtMs = null
        lastRouteKind = null
        _state.value = CallUiState(phase = CallPhase.Connecting, muted = request.muted, notice = "Getting Bill ready…")
        audio.begin(this)
        devices = audio.devices()
        route = RouteChoice(RouteSelector.auto(devices, callSettings.fallbackRoute), manual = false)
        applyRoute()
        audio.setFocus(FocusMode.Hold)
        Telemetry.event("call.start", mapOf("engine" to "cascade", "entry" to request.entry, "muted" to request.muted, "memo" to request.memo, "route" to route.route?.kind))
        val requestedAt = clock()
        startJob = scope.launch {
            try {
                val io = audioFactory().create(callSettings.echoCancel) { level -> rt?.core?.actor?.outputLevel(level) }
                val src = MutableSource(io.source).also { it.muted = request.muted }
                val stt = try {
                    withContext(this@CascadeCallEngine.io) {
                        speech.create(src, io.playback, r.settings, ::hotwords, callSettings, r.cloudStt, ::liveFallback).also { it.start() }
                    }
                } catch (e: Exception) {
                    io.release()
                    throw e
                }
                if (attempt != epoch || !_state.value.inCall) {
                    // Hung up while the models loaded: the mic is open now, so close it here.
                    withContext(this@CascadeCallEngine.io + NonCancellable) { runCatching { stt.release() } }
                    io.release()
                    return@launch
                }
                audioIo = io
                source = src
                call = stt
                r.startCall(stt, io.out)
                // Folio's PROMPT or shared text: the call's first turn, queued before any mic event.
                request.prompt?.trim()?.takeIf { it.isNotEmpty() }?.let { typedTurn(r, it) }
                liveAtMs = clock()
                _state.update { it.copy(phase = CallPhase.Live, startedAtMs = liveAtMs, notice = null) }
                Telemetry.event("call.connected", mapOf("engine" to "cascade", "connect_ms" to clock() - requestedAt, "route" to route.route?.kind))
                effect(Earcon.Connected)
                tickJob = scope.launch {
                    while (true) {
                        evaluate()
                        delay(TICK_MS)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempt != epoch) return@launch
                Telemetry.warn("call.failed", null, mapOf("engine" to "cascade", "type" to e.javaClass.simpleName))
                finish("failed", error = if (e is IllegalStateException && e.message?.contains("model", true) == true) {
                    "Bill's speech models are missing. Prepare Bill's voice in Settings › Voice."
                } else {
                    "The microphone or Bill's speech models could not start."
                })
            }
        }
    }

    /**
     * End the call (End button, notification, tile, headset). Bill says a short goodbye while the
     * end animation runs, unless someone was speaking; a second press cuts it short. Tasks keep running.
     */
    override fun end() {
        if (!_state.value.inCall) {
            teardown?.let { releaseNow(it, earcon = it.earcon) }
            return
        }
        finish("close_requested", goodbye = Goodbye.Say)
    }

    /** Bill said goodbye (`Effect.EndCall`): his line plays to the end while the call winds down. */
    fun goodbyeSaid() {
        if (!_state.value.inCall) return
        finish("close_requested", goodbye = Goodbye.Queued)
    }

    override fun toggleMute() {
        if (!_state.value.inCall) return
        val muted = !_state.value.muted
        if (!muted) autoMuted = false
        source?.muted = muted
        _state.update { it.copy(muted = muted) }
        effect(if (muted) Earcon.Muted else Earcon.Unmuted)
    }

    /** Hold-to-talk from the orb (R-STT-8): the pipeline opens or closes a held segment. */
    fun holdToTalk(pressed: Boolean) {
        call?.holdToTalk(pressed)
    }

    override fun selectRoute(option: RouteOption) {
        if (!_state.value.inCall) return
        route = RouteChoice(devices.firstOrNull { it.id == option.id } ?: option, manual = true)
        applyRoute()
    }

    /** The speaker button: speaker if not on it, else back to the automatic pick. */
    fun toggleSpeaker() {
        val speaker = devices.firstOrNull { it.kind == RouteKind.Speaker } ?: return
        if (route.route?.kind == RouteKind.Speaker) {
            route = RouteChoice(RouteSelector.auto(devices.filterNot { it.kind == RouteKind.Speaker }, callSettings.fallbackRoute) ?: speaker, manual = true)
        } else {
            selectRoute(speaker)
            return
        }
        applyRoute()
    }

    override fun submitText(text: String) {
        if (text.isNotBlank() && _state.value.inCall) _state.update { it.copy(notice = "Bill takes requests by voice. Say it, and he'll pass it on.") }
    }

    override fun answerApproval(id: String, choice: ApprovalChoice) {
        val r = runtimeNow() ?: return
        scope.launch {
            if (!r.answerApproval(id, choice)) _state.update { it.copy(notice = "Could not send your answer to Hermes.") }
            evaluate()
        }
    }

    override fun answerClarify(id: String, answers: Map<String, String?>) {
        val r = runtimeNow() ?: return
        scope.launch {
            if (!r.answerClarify(id, answers)) _state.update { it.copy(notice = "Could not send your answer to Hermes.") }
            evaluate()
        }
    }

    override fun dismissSummary() {
        if (_state.value.inCall) return
        _state.value = CallUiState()
    }

    // ── CallAudio.Listener ─────────────────────────────────────────────────────────────────

    override fun onDevicesChanged(devices: List<RouteOption>) {
        if (!_state.value.inCall) return
        val choice = RouteSelector.onDevicesChanged(this.devices, devices, route, callSettings.fallbackRoute)
        this.devices = devices
        val moved = choice.route?.id != route.route?.id
        route = choice
        if (moved) applyRoute() else _state.update { it.copy(routes = devices, route = route.route) }
    }

    override fun onBecomingNoisy() {
        if (!_state.value.inCall) return
        route = RouteSelector.onNoisy(devices, callSettings.fallbackRoute)
        applyRoute()
    }

    override fun onOtherCall(active: Boolean) {
        if (!_state.value.inCall || active == otherCall) return
        otherCall = active
        Telemetry.event("call.other_call", mapOf("active" to active, "engine" to "cascade"))
        if (active) {
            if (!_state.value.muted) {
                source?.muted = true
                autoMuted = true
                _state.update { it.copy(muted = true) }
            }
            rt?.audio?.stop()
            _state.update { it.copy(onOtherCall = true, notice = "Paused for your other call.") }
        } else {
            audio.reassert()
            applyRoute()
            if (autoMuted) {
                source?.muted = false
                _state.update { it.copy(muted = false) }
            }
            autoMuted = false
            _state.update { it.copy(onOtherCall = false, notice = "Back from your other call.") }
        }
    }

    // ── internals ──────────────────────────────────────────────────────────────────────────

    /** A typed request as one finished turn: Jev routes it like speech (the actor only knows turns). */
    private fun typedTurn(r: CascadeAppRuntime, text: String) {
        val actor = r.core.actor
        actor.send(Event.SpeechStarted)
        actor.send(Event.Final(text.take(MAX_TYPED_CHARS)))
        actor.send(Event.SpeechEnded)
        Telemetry.event("cascade.typed_turn", mapOf("chars" to text.length))
    }

    /**
     * The live-model guard swapped the live model for this call (any thread): say so once on the
     * call screen, and report it (ids, reason and RTF only).
     */
    private fun liveFallback(f: com.folio.hermeslive.stt.LiveFallback) {
        Telemetry.event("stt.live_fallback", mapOf("from" to f.from, "to" to f.to, "reason" to f.reason.wire, "rtf" to f.rtf, "swapped" to f.swapped))
        if (!f.swapped) return
        scope.launch {
            if (_state.value.inCall) _state.update { it.copy(notice = "Speech recognition switched to the faster model for this call: the chosen one can't keep up on this phone.") }
        }
    }

    /** The runtime of this call, or the process's (approvals from the no-call viewer). */
    private fun runtimeNow(): CascadeAppRuntime? = rt ?: runtime()?.also { rt = it }

    private fun hotwords(): List<String> {
        val s = settings()
        val r = rt ?: return s.vocabulary
        return (r.hotwords(s.vocabulary) + r.taskLabels()).distinctBy { it.lowercase() }
    }

    /** Music policy, orb activity and level, cards; every [TICK_MS]. */
    private fun evaluate() {
        val s = _state.value
        val r = rt ?: return
        if (!s.inCall) return
        val ui: CascadeUiState = r.core.actor.ui.value
        val now = clock()
        val userSpeaking = !s.muted && !otherCall && ui.turn.phase == TurnPhase.UserSpeaking
        val billSpeaking = ui.bill.speaking
        val working = ui.tasks.any { it.status == TaskUiStatus.Running || it.status == TaskUiStatus.Starting || it.status == TaskUiStatus.Queued }
        val headset = route.route?.kind?.isHeadset == true
        val decision = policy.update(GapSignals(userSpeaking, billSpeaking, working, headset, otherCall), now)
        if (decision.changed) audio.setFocus(decision.mode)
        val activity = when {
            billSpeaking -> CallActivity.HermesSpeaking
            userSpeaking -> CallActivity.UserSpeaking
            working || ui.turn.phase == TurnPhase.Deciding || ui.turn.phase == TurnPhase.Writing -> CallActivity.Thinking
            else -> CallActivity.Listening
        }
        val level = when (activity) {
            CallActivity.HermesSpeaking -> ui.bill.level
            CallActivity.UserSpeaking -> micLevel
            else -> 0f
        }
        val tool = ui.tasks.firstOrNull { it.status == TaskUiStatus.Running && it.currentTool != null }?.currentTool
        val music = policy.musicAllowed && !otherCall && audio.isMusicActive()
        val next = s.copy(activity = activity, level = level, musicInGap = music, tool = tool, approvals = r.approvals.value, clarify = r.clarify.value)
        if (next != s) _state.value = next
    }

    private fun applyRoute() {
        val r = route.route
        if (r?.kind != lastRouteKind && _state.value.inCall) {
            Telemetry.event("audio.route_change", mapOf("from" to lastRouteKind, "to" to r?.kind, "manual" to route.manual, "engine" to "cascade"))
            lastRouteKind = r?.kind
        }
        if (r != null) audio.select(r)
        effects.proximity(_state.value.inCall && r?.kind == RouteKind.Earpiece)
        _state.update { it.copy(route = r, routes = devices) }
        // A new input route: the pipeline relearns its noise floor and echo gain from the mic itself.
    }

    private fun effect(kind: Earcon) {
        if (callSettings.earcons) effects.earcon(kind)
        if (callSettings.haptics) effects.haptic(strong = kind == Earcon.Warning)
    }

    /** What the goodbye does at hang-up: nothing, wait for Bill's queued `end.bye*`, or say one (the End button). */
    enum class Goodbye { None, Queued, Say }

    /** One call's audio and speaker, released once (after the goodbye, or at once). */
    private inner class Teardown(val runtime: CascadeAppRuntime?, val io: CascadeAudioIo?, val earcon: Boolean) {
        var job: Job? = null
        private var done = false

        /** Bill stops, the speaker and the call audio mode go. Any thread; runs once. */
        fun release() {
            synchronized(this) {
                if (done) return
                done = true
            }
            runCatching { runtime?.endCall() }
            runCatching { io?.release?.invoke() }
            runCatching { audio.end() }
        }
    }

    /**
     * Hang-up. On this frame only what must happen now: the state flips to Ended (the end
     * animation starts), the off-call service starts while the call is still foreground
     * (R-CONV-3), and the mic stops listening. The models and the mic are released on [io]. Bill's
     * goodbye then plays to the end (at most [GOODBYE_CAP_MS]) while the animation runs; only then
     * do Bill, the speaker and the call audio mode go, also on [io].
     */
    private fun finish(reason: String, error: String? = null, goodbye: Goodbye = Goodbye.None) {
        if (!_state.value.inCall) return
        epoch++
        val r = rt
        // R-CONV-3: start the off-call service while this call is still in the foreground.
        val running = r?.tasksRunning?.value ?: 0
        val held = running > 0 && runCatching { offCall(running) }.getOrDefault(false)
        // Not startJob: a model load blocks and finishes anyway; the epoch check releases its result.
        tickJob?.cancel()
        val wasLive = liveAtMs != null
        val endedAt = clock()
        val summary = liveAtMs?.let {
            CallSummary(endedAt - it, null, if (running > 0) "Call ended. Bill's tasks carry on and you'll get a notification." else "Call ended.")
        }
        _state.value = CallUiState(phase = if (error != null && liveAtMs == null) CallPhase.Failed else CallPhase.Ended, summary = summary, error = error)
        effects.proximity(false)
        Telemetry.event(
            "call.end",
            mapOf("engine" to "cascade", "reason" to reason, "failed" to (error != null), "duration_s" to (liveAtMs?.let { (endedAt - it) / 1000 } ?: 0L), "tasks_running" to running, "off_call" to held),
        )
        // No more turns: the mic stops now; the models and the mic are released off the main thread.
        r?.stt?.detach()
        val stt = call
        val io = audioIo
        call = null
        source = null
        audioIo = null
        if (stt != null) {
            // Joins the capture thread and a running decode: never on the main thread.
            scope.launch(this.io) { runCatching { stt.release() } }
        }
        val actor = r?.core?.actor
        val bye = when {
            actor == null || !wasLive || error != null -> Goodbye.None
            // Bill's own goodbye is already on its way (End pressed just after it): wait for that one.
            goodbye == Goodbye.Say && Goodbyes.pending(actor.state.value) -> Goodbye.Queued
            goodbye == Goodbye.Say && !Goodbyes.canSay(actor.state.value) -> Goodbye.None
            else -> goodbye
        }
        val t = Teardown(r, io, earcon = wasLive || error == null)
        teardown = t
        t.job = scope.launch {
            try {
                if (actor != null && bye != Goodbye.None) {
                    if (bye == Goodbye.Say) actor.send(Event.Say(Goodbyes.slot(running > 0), SpeechKind.Reply))
                    val heard = Goodbyes.awaitPlayed(actor.state, GOODBYE_CAP_MS, waitForStart = bye == Goodbye.Say)
                    Telemetry.event("cascade.goodbye", mapOf("how" to bye.name.lowercase(), "played" to heard))
                }
            } finally {
                val released = teardown === t
                if (released) teardown = null
                withContext(this@CascadeCallEngine.io + NonCancellable) { t.release() }
                // Not after releaseNow: a new call may already be starting.
                if (released && t.earcon) effect(Earcon.Ended)
            }
        }
    }

    /** Release [t] now, on this thread (a new call needs the audio, or End was pressed again). */
    private fun releaseNow(t: Teardown, earcon: Boolean) {
        if (teardown === t) teardown = null
        t.job?.cancel()
        t.release()
        if (earcon) effect(Earcon.Ended)
    }

    companion object {
        const val TICK_MS = 150L

        /** The goodbye plays to the end, but the call's audio is released within this of the hang-up. */
        const val GOODBYE_CAP_MS = 2_000L
        const val MAX_TYPED_CHARS = 4_000
    }
}
