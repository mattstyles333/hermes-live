package com.folio.hermeslive.grok

import com.folio.hermes.ApprovalChoice
import com.folio.hermes.cascade.TaskUiStatus
import com.folio.hermes.realtime.GrokConfig
import com.folio.hermes.realtime.GrokPricing
import com.folio.hermes.realtime.GrokRealtime
import com.folio.hermes.realtime.GrokSession
import com.folio.hermes.realtime.GrokTools
import com.folio.hermes.realtime.GrokUi
import com.folio.hermes.realtime.LinkState
import com.folio.hermes.realtime.SessionSpec
import com.folio.hermes.telemetry.Telemetry
import com.folio.hermes.telemetry.TelemetrySink
import com.folio.hermeslive.audio.Dbfs
import com.folio.hermeslive.call.CallActivity
import com.folio.hermeslive.call.CallLimits
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
import com.folio.hermeslive.call.ondemand.OnDemandController
import com.folio.hermeslive.call.ondemand.OnDemandSettings
import com.folio.hermeslive.call.ondemand.OnDemandUi
import com.folio.hermeslive.call.ondemand.SessionView
import com.folio.hermeslive.cascade.CascadeAppRuntime
import com.folio.hermeslive.data.LiveSettings
import com.folio.hermeslive.data.VoiceEngine
import com.folio.hermeslive.stt.VoiceDetector
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/** The phone's network as a Grok call needs it. */
interface GrokNetwork {
    fun online(): Boolean
    val changes: Flow<Boolean>
}

/**
 * Grok Voice (xAI speech to speech) behind the same [VoiceCall] port as GPT-Live and Bill, so the
 * call service, CallStyle notification, tile, headset buttons and approval notifications work
 * unchanged, with Bill's audio rules: routes and their fallback, music in the gaps
 * ([GapMusicPolicy]), the other-call pause, earcons, haptics and the earpiece proximity lock.
 *
 * A call: Bill's process runtime holds the Hermes side (actor, durable store, gateway; read back
 * with no Bill call, nothing of Bill spoken), Grok drives its tasks through the shared task API
 * ([GrokSession]), the mic (16 kHz, `VOICE_COMMUNICATION` + AEC) is resampled to 24 kHz for the
 * socket, and Grok's voice streams to a 24 kHz [StreamOut]. Hang-up closes the socket; the tasks
 * carry on (the off-call service, as for Bill).
 *
 * Offline: a dropped socket with the phone offline says so, offers the Custom engine's offline memo
 * mode when it can run ([billMemo]), and reconnects (resuming the conversation) when the network is back.
 *
 * Save cost (ondemand slice, Settings › Voice, on by default): an [OnDemandController] parks the
 * socket after 8 s of all-idle while the call stays up; the phone listens ([vad], Silero) with a
 * 1.5 s pre-roll and resumes the session when the user speaks or a task notice waits. Off, or with
 * no voice detector, the socket stays open for the whole call as before.
 *
 * Idle hang-up and call cap (1.3.0 integration; [CallLimits], the GPT-Live settings): after
 * Settings › Hang up when quiet (3 min) with nobody speaking, Grok silent and no notice waiting,
 * the call hangs up, with a visual warning and an earcon 30 s before (no spoken one: a parked
 * session would have to reopen, at a cost, to say it). Running tasks do not keep the call up; the
 * off-call service takes them over at hang-up. The call cap (30 min) warns 1 min before.
 *
 * Runs on [scope]'s thread (Main), like the other engines.
 */
class GrokCallEngine(
    private val scope: CoroutineScope,
    private val settings: () -> LiveSettings,
    private val runtime: () -> CascadeAppRuntime?,
    private val key: () -> String?,
    private val client: OkHttpClient,
    private val audioFactory: () -> GrokAudioFactory,
    private val audio: CallAudio,
    private val effects: CallEffects,
    private val offCall: (tasksRunning: Int) -> Boolean,
    private val network: GrokNetwork,
    /** Bill's offline memo mode can take over (his models are on the phone and he can run). */
    private val billMemo: () -> Boolean = { false },
    /** A Grok call is live (true) or over: the app quiets done/failed notifications meanwhile (Grok says them). */
    private val onLive: (Boolean) -> Unit = {},
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val telemetry: TelemetrySink = TelemetrySink.Default,
    /** Tests: the socket's address. */
    private val baseConfig: GrokConfig = GrokConfig(),
    /** The phone's voice detector for on-demand sessions (Silero on the mic thread); null: the session never closes. */
    private val vad: () -> VoiceDetector? = { null },
    /** Tests: shorter on-demand timings. */
    private val onDemandTimings: (OnDemandSettings) -> OnDemandSettings = { it },
) : VoiceCall, CallAudio.Listener {
    private val _state = MutableStateFlow(CallUiState())
    override val state: StateFlow<CallUiState> = _state.asStateFlow()

    private var rt: CascadeAppRuntime? = null
    private var hold: AutoCloseable? = null
    private var grok: GrokSession? = null
    private var io_: GrokAudioIo? = null
    private var mic: Thread? = null
    private var callSettings = LiveSettings()
    private var policy = GapMusicPolicy()
    private var devices: List<RouteOption> = emptyList()
    private var route = RouteChoice(null, manual = false)
    private var otherCall = false
    private var autoMuted = false
    private var liveAtMs: Long? = null
    private var jobs = mutableListOf<Job>()
    private var onDemand: OnDemandController? = null
    private var limits: CallLimits? = null
    private val _session = MutableStateFlow(OnDemandUi())

    /** The on-demand session state (the orb dims and an indicator shows while "Listening (on phone)"). */
    val sessionUi: StateFlow<OnDemandUi> = _session.asStateFlow()

    @Volatile private var micMuted = false

    @Volatile private var micDb = Dbfs.FLOOR

    /** The runtime of the current call (the call screen draws its tasks). */
    val session: CascadeAppRuntime? get() = rt

    /** Grok's own state (link, speaking, caption, tool chips), for the call screen. */
    val grokUi: StateFlow<GrokUi>? get() = grok?.ui

    /** The mic level 0..1. */
    val micLevel: Float get() = Dbfs.toUnit(micDb)

    /** Grok's output level 0..1. */
    val outputLevel: Float get() = io_?.out?.level() ?: 0f

    // ── commands ────────────────────────────────────────────────────────────────────────────

    override fun start(request: CallRequest) {
        if (_state.value.inCall) {
            if (request.muted && !_state.value.muted) toggleMute()
            if (request.prompt != null) _state.update { it.copy(notice = "Grok takes requests by voice. Say it.") }
            return
        }
        callSettings = settings()
        val r = runtime()
        if (r == null) {
            _state.value = CallUiState(phase = CallPhase.Failed, error = "Grok Voice can't start. Sign in to Hermes, and check the xAI key in Settings › Voice.")
            return
        }
        rt = r
        policy = GapMusicPolicy(callSettings.gapSettings())
        otherCall = false
        autoMuted = false
        liveAtMs = null
        micMuted = request.muted
        _state.value = CallUiState(phase = CallPhase.Connecting, muted = request.muted, notice = "Connecting to Grok Voice…")
        audio.begin(this)
        devices = audio.devices()
        route = RouteChoice(RouteSelector.auto(devices, callSettings.fallbackRoute), manual = false)
        applyRoute()
        audio.setFocus(FocusMode.Hold)
        Telemetry.event("call.start", mapOf("engine" to "grok", "entry" to request.entry, "muted" to request.muted, "route" to route.route?.kind))
        // Hermes side: the stored tasks read back (no Bill call), the gateway held open for the call.
        hold = runCatching { r.holdGateway() }.getOrNull()
        val config = baseConfig.copy(
            model = callSettings.grokModel,
            voice = callSettings.grokVoiceId(),
            keyterms = callSettings.vocabulary + r.taskLabels(),
        )
        val audioIo = try {
            audioFactory().create(callSettings.echoCancel)
        } catch (e: Exception) {
            finish("failed", error = "The microphone could not start.")
            return
        }
        io_ = audioIo
        val realtime = GrokRealtime(
            client, key = key, config = { config }, spec = { SessionSpec(GrokTools.INSTRUCTIONS, GrokTools.DEFINITIONS) },
            scope = scope, online = network::online, telemetry = telemetry,
        )
        val s = GrokSession(realtime, r.core.tasks, audioIo.out, scope, { config }, onEndCall = { scope.launch { finish("goodbye") } }, telemetry = telemetry)
        grok = s
        s.mute(request.muted)
        lateinit var ctl: OnDemandController
        ctl = OnDemandController(
            link = GrokLink(s, realtime, network::online),
            settings = onDemandTimings(callSettings.onDemandSettings(VoiceEngine.Grok)),
            vadFactory = vad,
            context = { s.contextNote(r.core.actor.state.value.conversation.summary) },
            clock = clock,
            wake = { scope.launch { if (onDemand === ctl) evaluate() } },
            telemetry = telemetry,
        )
        onDemand = ctl
        ctl.setMuted(request.muted)
        val requestedAt = clock()
        jobs += scope.launch { s.ui.collect { onGrok(it, requestedAt) } }
        jobs += scope.launch { ctl.ui.collect { _session.value = it } }
        jobs += scope.launch {
            network.changes.collect { up ->
                if (up) {
                    s.networkBack()
                    _state.update { it.copy(offerBillMemo = false, notice = if (s.ui.value.link == LinkState.Parked) null else it.notice) }
                }
            }
        }
        if (!s.start()) {
            finish("failed", error = "Add your xAI key in Settings › Voice to use Grok Voice.")
            return
        }
        ctl.start()
        startMic(audioIo, ctl)
        onLive(true)
        // Idle hang-up and call cap (Settings, as GPT-Live's): counted from the call's start.
        limits = CallLimits(callSettings.bridgeSettings(), clock())
        jobs += scope.launch {
            while (true) {
                evaluate()
                delay(TICK_MS)
            }
        }
    }

    override fun end() {
        if (!_state.value.inCall) return
        finish("close_requested")
    }

    override fun toggleMute() {
        if (!_state.value.inCall) return
        val muted = !_state.value.muted
        if (!muted) autoMuted = false
        setMuted(muted)
        effect(if (muted) Earcon.Muted else Earcon.Unmuted)
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
            applyRoute()
        } else {
            selectRoute(speaker)
        }
    }

    override fun submitText(text: String) {
        if (text.isNotBlank() && _state.value.inCall) _state.update { it.copy(notice = "Grok takes requests by voice. Say it.") }
    }

    override fun answerApproval(id: String, choice: ApprovalChoice) {
        val r = rt ?: runtime() ?: return
        scope.launch {
            if (!r.answerApproval(id, choice)) _state.update { it.copy(notice = "Could not send your answer to Hermes.") }
        }
    }

    override fun answerClarify(id: String, answers: Map<String, String?>) {
        val r = rt ?: runtime() ?: return
        scope.launch {
            if (!r.answerClarify(id, answers)) _state.update { it.copy(notice = "Could not send your answer to Hermes.") }
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
        Telemetry.event("call.other_call", mapOf("active" to active, "engine" to "grok"))
        if (active) {
            if (!_state.value.muted) {
                autoMuted = true
                setMuted(true)
            }
            io_?.out?.stop()
            _state.update { it.copy(onOtherCall = true, notice = "Paused for your other call.") }
        } else {
            audio.reassert()
            applyRoute()
            if (autoMuted) setMuted(false)
            autoMuted = false
            _state.update { it.copy(onOtherCall = false, notice = "Back from your other call.") }
        }
    }

    // ── internals ──────────────────────────────────────────────────────────────────────────

    private fun setMuted(muted: Boolean) {
        micMuted = muted
        grok?.mute(muted)
        onDemand?.setMuted(muted)
        _state.update { it.copy(muted = muted) }
    }

    /** The mic on its own thread: 16 kHz frames to the on-demand controller (which feeds the session while it is open). */
    private fun startMic(audioIo: GrokAudioIo, ctl: OnDemandController) {
        val source = audioIo.source
        mic = Thread({
            val buf = ShortArray(source.sampleRate / 50)
            try {
                source.open()
                while (!Thread.currentThread().isInterrupted) {
                    val n = source.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    micDb = if (micMuted) Dbfs.FLOOR else Dbfs.of(Dbfs.pcm16ToFloat(buf, n))
                    ctl.onMic(buf, n)
                }
            } catch (e: Exception) {
                Telemetry.warn("audio.error", null, mapOf("engine" to "grok", "type" to e.javaClass.simpleName))
            } finally {
                runCatching { source.close() }
                ctl.micClosed()
            }
        }, "grok-mic").apply { isDaemon = true; start() }
    }

    /** Grok's link and turn state onto the call's state. */
    private fun onGrok(ui: GrokUi, requestedAt: Long) {
        if (!_state.value.inCall) return
        when (val link = ui.link) {
            is LinkState.Open -> {
                if (liveAtMs == null) {
                    liveAtMs = clock()
                    Telemetry.event("call.connected", mapOf("engine" to "grok", "connect_ms" to clock() - requestedAt, "route" to route.route?.kind))
                    effect(Earcon.Connected)
                }
                _state.update { it.copy(phase = CallPhase.Live, startedAtMs = liveAtMs, notice = null, offerBillMemo = false) }
            }
            is LinkState.Reconnecting -> _state.update { it.copy(phase = CallPhase.Reconnecting, notice = "Reconnecting to Grok Voice…") }
            LinkState.Offline -> {
                _state.update { it.copy(phase = CallPhase.Reconnecting, notice = "You're offline. Grok Voice needs the internet; it reconnects when you're back.", offerBillMemo = billMemo()) }
                Telemetry.event("grok.offline_offer", mapOf("bill" to billMemo()))
            }
            is LinkState.Failed -> when {
                link.code == GrokRealtime.UNAUTHORIZED -> finish("failed", error = "xAI refused the key. Check it in Settings › Voice.")
                link.code == GrokRealtime.NO_KEY -> finish("failed", error = "Add your xAI key in Settings › Voice to use Grok Voice.")
                !network.online() -> _state.update { it.copy(phase = CallPhase.Reconnecting, notice = "You're offline. Grok Voice needs the internet.", offerBillMemo = billMemo()) }
                else -> finish("failed", error = "Grok Voice can't be reached right now.")
            }
            // Parked on purpose (save cost): the call is still live; offline, the offline notice stays.
            LinkState.Parked -> if (network.online()) _state.update { it.copy(phase = CallPhase.Live, notice = null, offerBillMemo = false) }
            LinkState.Idle, LinkState.Connecting, LinkState.Closed -> Unit
        }
    }

    /** Music policy, orb activity and level, cards; every [TICK_MS]. */
    private fun evaluate() {
        val s = _state.value
        val r = rt ?: return
        if (!s.inCall) return
        val ctl = onDemand
        ctl?.tick()
        val g = grok?.ui?.value ?: GrokUi()
        val now = clock()
        val userSpeaking = !s.muted && !otherCall && (g.userSpeaking || ctl?.phoneHearsSpeech == true)
        val speaking = g.speaking
        val tasks = r.core.actor.ui.value.tasks
        val working = tasks.any { it.status == TaskUiStatus.Running || it.status == TaskUiStatus.Starting || it.status == TaskUiStatus.Queued }
        val headset = route.route?.kind?.isHeadset == true
        val base = GapSignals(userSpeaking, speaking, working, headset, otherCall)
        val decision = policy.update(ctl?.gap(base) ?: base, now)
        if (decision.changed) audio.setFocus(decision.mode)
        val activity = when {
            speaking -> CallActivity.HermesSpeaking
            userSpeaking -> CallActivity.UserSpeaking
            working -> CallActivity.Thinking
            else -> CallActivity.Listening
        }
        val level = when (activity) {
            CallActivity.HermesSpeaking -> outputLevel
            CallActivity.UserSpeaking -> micLevel
            else -> 0f
        }
        val tool = tasks.firstOrNull { it.status == TaskUiStatus.Running && it.currentTool != null }?.currentTool
        val music = policy.musicAllowed && !otherCall && audio.isMusicActive()
        val next = s.copy(activity = activity, level = level, musicInGap = music, tool = tool, approvals = r.approvals.value, clarify = r.clarify.value)
        if (next != s) _state.value = next
        // Running tasks are not activity: the off-call service carries them after an idle hang-up.
        val session = grok
        val busy = userSpeaking || speaking || otherCall || (session != null && (!session.quiet() || session.hasPendingNotice()))
        applyLimits(now, busy)
    }

    /** The idle hang-up and the call cap ([CallLimits]): a visual warning with an earcon first, then the hang-up. */
    private fun applyLimits(now: Long, busy: Boolean) {
        val l = limits ?: return
        when (val step = l.tick(now, busy)) {
            CallLimits.Step.None -> Unit
            CallLimits.Step.IdleWarning -> {
                effect(Earcon.Warning)
                _state.update { it.copy(warning = l.idleWarning) }
            }
            CallLimits.Step.IdleWarningCleared -> _state.update { if (it.warning == l.idleWarning) it.copy(warning = null) else it }
            CallLimits.Step.CapWarning -> {
                effect(Earcon.Warning)
                _state.update { it.copy(warning = CallLimits.CAP_WARNING) }
            }
            is CallLimits.Step.HangUp -> finish(step.reason)
        }
    }

    private fun applyRoute() {
        val r = route.route
        if (r != null) audio.select(r)
        effects.proximity(_state.value.inCall && r?.kind == RouteKind.Earpiece)
        _state.update { it.copy(route = r, routes = devices) }
    }

    private fun effect(kind: Earcon) {
        if (callSettings.earcons) effects.earcon(kind)
        if (callSettings.haptics) effects.haptic(strong = kind == Earcon.Warning)
    }

    /**
     * Hang-up: the state flips to Ended now, the off-call service starts while the call is still in
     * the foreground (tasks carry on), the socket closes, and the mic and speaker are released off
     * the main thread.
     */
    private fun finish(reason: String, error: String? = null) {
        if (!_state.value.inCall) return
        val r = rt
        val running = r?.tasksRunning?.value ?: 0
        val held = running > 0 && runCatching { offCall(running) }.getOrDefault(false)
        jobs.forEach { it.cancel() }
        jobs.clear()
        val endedAt = clock()
        val duration = liveAtMs?.let { endedAt - it }
        val summary = duration?.let {
            val cost = String.format(Locale.UK, "%.2f", it / 60_000.0 * GrokPricing.USD_PER_MINUTE)
            val tasks = if (running > 0) " Your tasks carry on and you'll get a notification." else ""
            val lead = when (reason) {
                CallLimits.REASON_IDLE -> "Nobody spoke for a while, so the call hung up."
                CallLimits.REASON_CAP -> "Call time limit reached."
                else -> "Call ended."
            }
            CallSummary(it, null, "$lead About \$$cost of Grok Voice.$tasks")
        }
        _state.value = CallUiState(phase = if (error != null && liveAtMs == null) CallPhase.Failed else CallPhase.Ended, summary = summary, error = error)
        effects.proximity(false)
        Telemetry.event(
            "call.end",
            mapOf("engine" to "grok", "reason" to reason, "failed" to (error != null), "duration_s" to ((duration ?: 0L) / 1000), "tasks_running" to running, "off_call" to held),
        )
        onDemand?.stop()
        onDemand = null
        limits = null
        _session.value = _session.value.copy(view = SessionView.Connecting)
        grok?.stop()
        grok = null
        mic?.interrupt()
        val thread = mic
        mic = null
        val audioIo = io_
        io_ = null
        val h = hold
        hold = null
        onLive(false)
        scope.launch {
            withContext(io) {
                runCatching { thread?.join(1_000) }
                runCatching { audioIo?.out?.release() }
                runCatching { h?.close() }
                runCatching { audio.end() }
            }
            if (liveAtMs != null || error == null) effect(Earcon.Ended)
        }
    }

    companion object {
        const val TICK_MS = 150L
    }
}
