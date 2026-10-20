package com.folio.hermeslive.call

import com.folio.hermes.ApprovalChoice
import com.folio.hermes.HermesAuthException
import com.folio.hermes.HermesRpcException
import com.folio.hermes.live.AgentDesk
import com.folio.hermes.live.AgentDeskEvent
import com.folio.hermes.live.BridgeEvent
import com.folio.hermes.live.BridgeStatus
import com.folio.hermes.live.DelegationBridge
import com.folio.hermes.live.DelegationPrompt
import com.folio.hermes.live.Speaker
import com.folio.hermes.live.StopReason
import com.folio.hermes.live.VoiceLiveApi
import com.folio.hermes.live.VoiceLiveException
import com.folio.hermes.telemetry.Telemetry
import com.folio.hermeslive.call.ondemand.OnDemandUi
import com.folio.hermeslive.data.LiveSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Where the Hermes session id to resume is kept between calls. */
interface SessionMemoryStore {
    fun load(): SessionMemory
    fun save(memory: SessionMemory)
}

/**
 * One Hermes Live call, end to end, without Android: WebRTC behind [LiveTransport], audio behind
 * [CallAudio], Hermes behind [HermesLink]. Owns the [DelegationBridge], the captions, the music
 * policy, routes, mute, the other-call pause, one automatic reconnect and the call summary.
 *
 * Lifecycle: [start] → Connecting → (session.started) Live → [end] / stop phrase / idle / cap →
 * Ending (goodbye, `session.close`, ≤15 s for `session.closed`) → Ended with a [CallSummary].
 * A dropped line while Live reconnects once with the transcript as history.
 *
 * Parallel agents (gptlive-parallel slice; off unless [agentsFactory] gives a host): every
 * delegation becomes its own durable task through the shared task API ([AgentDesk]); approvals
 * and clarify come from the task sessions; tasks carry on after the call ([offCall]). The paid
 * voice session can be closed and reopened inside one call ([GptLiveSessionControl]).
 *
 * Every method and callback runs on [scope]'s single thread (Main).
 */
class CallEngine(
    private val scope: CoroutineScope,
    private val settings: () -> LiveSettings,
    private val links: HermesLinkFactory,
    private val transports: LiveTransportFactory,
    private val audio: CallAudio,
    private val effects: CallEffects,
    private val memory: SessionMemoryStore,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Parallel agents for a call, or null (off: today's single Hermes conversation). */
    private val agentsFactory: GptLiveAgentsFactory = GptLiveAgentsFactory.Off,
    /** Hang-up with agent tasks still running: start the off-call service while the call is foreground. */
    private val offCall: (tasksRunning: Int) -> Boolean = { false },
    /** An agents call is live (true) or over: task notifications stay quiet meanwhile (the voice says results). */
    private val onAgentsLive: (Boolean) -> Unit = {},
    /** On-demand sessions for a call (the shared controller), or null: the session stays open, as before. */
    private val onDemandFactory: GptLiveOnDemandFactory = GptLiveOnDemandFactory.Off,
    /** The phone has a validated network now: a failure while offline is [CallFailure.Offline] (offline-memo slice). */
    private val online: () -> Boolean = { true },
) : CallAudio.Listener, VoiceCall, GptLiveSessionControl {
    private val _state = MutableStateFlow(CallUiState())

    /** Everything the UI, notification, tile and provider show. */
    override val state: StateFlow<CallUiState> = _state.asStateFlow()

    private var epoch = 0
    private var link: HermesLink? = null
    private var transport: LiveTransport? = null
    private var bridge: DelegationBridge? = null
    private var sender: OrderedSender? = null
    private val captions = CaptionLog()
    private var policy = GapMusicPolicy()
    private val vad = EnergyVad()
    private val remoteGate = LevelGate()
    private var callSettings = LiveSettings()

    private var devices: List<RouteOption> = emptyList()
    private var route = RouteChoice(null, manual = false)
    private var lastUserTextAtMs = Long.MIN_VALUE / 2
    private var lastVoiceTextAtMs = Long.MIN_VALUE / 2
    private var delegationActive = false
    private var delegationCutOff = false
    private var reconnectsLeft = 0
    private var userEnding = false
    private var everLive = false
    private var liveAtMs: Long? = null
    private var usageDone = 0.0
    private var usageNow = 0.0
    private var pendingPrompt: String? = null
    private var autoMuted = false
    private var otherCall = false
    private var utterance = StringBuilder()
    private var utteranceJob: Job? = null
    private var tickJob: Job? = null
    private var startedTimeout: Job? = null
    private var micLevel = 0f
    private var remoteLevel = 0f
    private var lastRemoteLevel = 0.0
    private var requestedAtMs = 0L
    private var lostAtMs = 0L
    private var lastRouteKind: RouteKind? = null

    // Parallel agents and on-demand sessions.
    private var agents: GptLiveAgentsHost? = null
    private var lastAgents: GptLiveAgentsHost? = null
    private var desk: AgentDesk? = null
    private var agentHold: AutoCloseable? = null
    private val agentJobs = mutableListOf<Job>()
    private var dormant = false
    private var reopening = false
    private var reopenAtMs = 0L
    private var preRoll: String? = null
    private val _sessionOpen = MutableStateFlow(false)
    private val noPending = MutableStateFlow(0)
    private var onDemand: GptLiveOnDemand? = null
    private var dormantSinceMs = 0L
    private var finishing = false
    private var voiceSessionId: String? = null
    private val noOnDemand = MutableStateFlow(OnDemandUi())

    /** Who is audible, as of the last tick (the on-demand link reads these). */
    internal var userSpeakingNow = false
        private set
    internal var voiceSpeakingNow = false
        private set

    /** On-demand sessions' state for the call screen (indicator, dimmed orb); disabled when off. */
    val onDemandUi: StateFlow<OnDemandUi> get() = onDemand?.ui ?: noOnDemand

    /** The agents of the current (or last) call; null when agents are off. The call screen draws their tray. */
    val agentSession: GptLiveAgentsHost? get() = lastAgents

    override val sessionOpen: StateFlow<Boolean> = _sessionOpen.asStateFlow()
    override val pendingResults: StateFlow<Int> get() = desk?.pendingResults ?: noPending
    override var lastReopenMs: Long? = null
        private set

    // ── commands ────────────────────────────────────────────────────────────────────────────

    /** Start a call. While one is running, a [CallRequest.prompt] is submitted to it instead. */
    override fun start(request: CallRequest) {
        val current = _state.value
        if (current.inCall) {
            request.prompt?.let(::submitText)
            if (request.muted && !current.muted) setMuted(true)
            return
        }
        callSettings = settings()
        if (request.newConversation) memory.save(SessionMemory.Empty)
        resetCall()
        policy = GapMusicPolicy(callSettings.gapSettings())
        reconnectsLeft = 1
        pendingPrompt = request.prompt?.trim()?.ifEmpty { null }
        _state.value = CallUiState(phase = CallPhase.Connecting, muted = request.muted)
        audio.begin(this)
        devices = audio.devices()
        route = RouteChoice(RouteSelector.auto(devices, callSettings.fallbackRoute), manual = false)
        applyRoute()
        audio.setFocus(FocusMode.Hold)
        val resume = memory.load().resumable(clock())
        link = links.create(resume)
        startAgents()
        requestedAtMs = clock()
        Telemetry.event(
            "call.start",
            mapOf(
                "entry" to request.entry,
                "muted" to request.muted,
                "has_prompt" to (pendingPrompt != null),
                "new_conversation" to request.newConversation,
                "resumed" to (resume != null),
                "route" to route.route?.kind,
                "agents" to (desk != null),
            ),
        )
        tickJob = scope.launch {
            while (true) {
                delay(TICK_MS)
                evaluate()
            }
        }
        connect()
        onDemand = runCatching { onDemandFactory.create(this) }.getOrNull()?.also {
            if (request.muted) it.setMuted(true)
            it.start()
        }
    }

    /** End the call (End button, notification, tile, headset). A second press skips the goodbye. */
    override fun end() {
        val phase = _state.value.phase
        if (!_state.value.inCall) return
        userEnding = true
        val b = bridge
        if (b == null || phase == CallPhase.Connecting || phase == CallPhase.Reconnecting) {
            if (b != null && b.status.value != BridgeStatus.Connecting) b.close() else finish("close_requested")
            return
        }
        _state.update { it.copy(phase = CallPhase.Ending, notice = "Ending…") }
        b.close(StopReason.UserRequest)
    }

    override fun toggleMute() = setMuted(!_state.value.muted)

    fun setMuted(muted: Boolean, quiet: Boolean = false) {
        if (!_state.value.inCall || _state.value.muted == muted) return
        if (!muted) autoMuted = false
        _state.update { it.copy(muted = muted) }
        onDemand?.setMuted(muted)
        transport?.setMicEnabled(!muted)
        if (bridge?.status?.value.let { it != null && it != BridgeStatus.Connecting && it != BridgeStatus.Closed }) {
            bridge?.setMuted(muted)
        }
        if (!quiet) {
            effect(if (muted) Earcon.Muted else Earcon.Unmuted)
        }
        evaluate()
    }

    /** The user picked a route in the call screen. */
    override fun selectRoute(option: RouteOption) {
        if (!_state.value.inCall) return
        route = RouteChoice(devices.firstOrNull { it.id == option.id } ?: option, manual = true)
        applyRoute()
    }

    /** The speaker button on the agents screen: speaker if not on it, else back to the automatic pick. */
    fun toggleSpeaker() {
        if (!_state.value.inCall) return
        val speaker = devices.firstOrNull { it.kind == RouteKind.Speaker } ?: return
        if (route.route?.kind == RouteKind.Speaker) {
            route = RouteChoice(RouteSelector.auto(devices.filterNot { it.kind == RouteKind.Speaker }, callSettings.fallbackRoute) ?: speaker, manual = true)
            applyRoute()
        } else {
            selectRoute(speaker)
        }
    }

    /** A typed or shared request during the call. */
    override fun submitText(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val b = bridge
        if (b == null || b.status.value == BridgeStatus.Connecting) {
            pendingPrompt = clean
            return
        }
        captions.appendUserText(clean)
        _state.update { it.copy(captions = captions.captions) }
        b.delegateText(clean)
    }

    /** The user tapped an approval answer. The only way (besides opted-in speech) an approval is answered. */
    override fun answerApproval(id: String, choice: ApprovalChoice) {
        val card = _state.value.approvals.firstOrNull { it.id == id } ?: return
        if (choice !in card.choices && choice != ApprovalChoice.Deny) return
        _state.update { s -> s.copy(approvals = s.approvals.filterNot { it.id == id }) }
        agents?.let { host ->
            scope.launch { if (!host.answerApproval(id, choice)) _state.update { it.copy(notice = "Could not send your answer to Hermes.") } }
            return
        }
        val l = link ?: return
        scope.launch {
            try {
                l.answerApproval(card.request, choice)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Telemetry.warn("gateway.error", null, failureAttrs(e) + ("op" to "approval.respond"))
                _state.update { it.copy(notice = "Could not send your answer to Hermes.") }
            }
        }
    }

    /** The user answered the clarify card ([answers] by question id; null skips one). */
    override fun answerClarify(id: String, answers: Map<String, String?>) {
        val card = _state.value.clarify?.takeIf { it.id == id } ?: return
        _state.update { it.copy(clarify = null) }
        agents?.let { host ->
            scope.launch { if (!host.answerClarify(id, answers)) _state.update { it.copy(notice = "Could not send your answer to Hermes.") } }
            return
        }
        val l = link ?: return
        scope.launch {
            try {
                l.answerClarify(card.request, answers)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Telemetry.warn("gateway.error", null, failureAttrs(e) + ("op" to "clarify.respond"))
                _state.update { it.copy(notice = "Could not send your answer to Hermes.") }
            }
        }
    }

    /** Clear the summary or error after the user saw it. */
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
        evaluate()
    }

    override fun onBecomingNoisy() {
        if (!_state.value.inCall) return
        route = RouteSelector.onNoisy(devices, callSettings.fallbackRoute)
        applyRoute()
    }

    override fun onOtherCall(active: Boolean) {
        if (!_state.value.inCall || active == otherCall) return
        otherCall = active
        Telemetry.event("call.other_call", mapOf("active" to active))
        if (active) {
            if (!_state.value.muted) {
                setMuted(true, quiet = true)
                autoMuted = true
            }
            transport?.setSpeakerMuted(true)
            _state.update { it.copy(onOtherCall = true, notice = "Paused for your other call.") }
        } else {
            audio.reassert()
            applyRoute()
            transport?.setSpeakerMuted(false)
            if (autoMuted) setMuted(false, quiet = true)
            autoMuted = false
            _state.update { it.copy(onOtherCall = false, notice = "Back from your other call.") }
        }
        evaluate()
    }

    // ── connection ─────────────────────────────────────────────────────────────────────────

    private fun connect() {
        val attempt = ++epoch
        val l = link ?: return
        val s = callSettings
        scope.launch {
            try {
                val validity = (if (s.callCapMinutes > 0) s.callCapMinutes else 60) * 60_000L + 5 * 60_000L
                l.prepare(validity)
                if (attempt != epoch) return@launch
                val t = transports.create(eventsFor(attempt))
                transport = t
                val snd = OrderedSender(scope, t::send, ::commentaryLead)
                sender = snd
                val b = DelegationBridge(scope, l.backend, snd::send, s.bridgeSettings(), agents = desk) { event ->
                    if (attempt == epoch) onBridgeEvent(event)
                }
                b.delegationInterceptor = ::intercept
                bridge = b
                if (_state.value.muted) t.setMicEnabled(false)
                val history = VoiceLiveApi.history(captions.history(), note = desk?.openTasksNote())
                withTimeout(START_TIMEOUT_MS) { t.start { offer -> l.createVoiceSession(offer, history) } }
                if (attempt != epoch) return@launch
                startedTimeout = scope.launch {
                    delay(STARTED_TIMEOUT_MS)
                    if (attempt == epoch && b.status.value == BridgeStatus.Connecting) {
                        Telemetry.warn("call.failed", null, mapOf("type" to "session_not_started", "reconnect" to everLive))
                        connectFailed("Hermes Live did not start. Try again.", if (online()) CallFailure.VoiceUnavailable else CallFailure.Offline)
                    }
                }
            } catch (e: CancellationException) {
                if (attempt == epoch && _state.value.inCall && e is kotlinx.coroutines.TimeoutCancellationException) {
                    Telemetry.warn("call.failed", null, mapOf("type" to "timeout", "after_ms" to clock() - requestedAtMs, "reconnect" to everLive))
                    connectFailed("Hermes Live took too long to connect.", if (online()) CallFailure.HermesUnreachable else CallFailure.Offline)
                } else if (e !is kotlinx.coroutines.TimeoutCancellationException) {
                    throw e
                }
            } catch (e: Exception) {
                if (attempt == epoch) {
                    val attrs = failureAttrs(e) + mapOf("reconnect" to everLive, "shown" to describe(e))
                    // Expected failures (network, auth, vendor) carry codes only: their messages can echo the server.
                    if (e is java.io.IOException) Telemetry.warn("call.failed", null, attrs) else Telemetry.error("call.failed", e, attrs)
                    connectFailed(describe(e), failureKind(e, online()))
                }
            }
        }
    }

    private fun eventsFor(attempt: Int) = object : TransportEvents {
        override fun onMessage(text: String) {
            if (attempt == epoch) bridge?.onDataChannelMessage(text)
        }

        override fun onLost(reason: String) {
            if (attempt == epoch) bridge?.onTransportLost() ?: finish("connection_lost")
        }

        override fun onRemoteLevel(level: Double) {
            if (attempt != epoch) return
            lastRemoteLevel = level
            remoteGate.offer(level, clock())
            remoteLevel = (level * 4).toFloat().coerceIn(0f, 1f)
        }

        override fun onMicLevel(levelDb: Double, durationMs: Long) {
            if (attempt != epoch) return
            vad.offer(levelDb, durationMs)
            micLevel = ((levelDb + 60) / 50).toFloat().coerceIn(0f, 1f)
        }
    }

    private fun onBridgeEvent(event: BridgeEvent) {
        when (event) {
            is BridgeEvent.Started -> {
                voiceSessionId = event.sessionId
                onStarted()
            }
            is BridgeEvent.Transcript -> {
                captions.append(event.fragment)
                val now = clock()
                if (event.fragment.speaker == Speaker.User) {
                    lastUserTextAtMs = now
                    desk?.userWords(now)
                    onUserWords(event.fragment.text)
                } else {
                    lastVoiceTextAtMs = now
                    desk?.voiceWords(now)
                    utterance = StringBuilder()
                }
                _state.update { it.copy(captions = captions.captions) }
                evaluate()
            }
            is BridgeEvent.DelegationStarted -> {
                // With agents, the desk's tasks say whether work is pending (nothing settles here).
                if (desk == null) delegationActive = true
                touchMemory()
                _state.update { it.copy(tool = null) }
                evaluate()
            }
            is BridgeEvent.ToolStarted -> _state.update { it.copy(tool = event.label) }
            is BridgeEvent.DelegationSettled -> settled(null)
            is BridgeEvent.DelegationFailed -> {
                Telemetry.warn("delegation.failed", null, mapOf("kind" to event.kind, "code" to event.code))
                if (event.code != null) Telemetry.warn("gateway.error", null, mapOf("code" to event.code, "op" to "prompt.submit"))
                settled(
                    if (event.message.contains("sign in", ignoreCase = true)) "Sign in to Hermes again." else "Could not reach Hermes for that request.",
                )
            }
            is BridgeEvent.ApprovalNeeded -> {
                if (_state.value.approvals.none { it.id == event.request.id }) {
                    _state.update { it.copy(approvals = it.approvals + ApprovalCard(event.request)) }
                    effects.haptic(strong = true)
                }
            }
            is BridgeEvent.ClarifyNeeded -> {
                _state.update { it.copy(clarify = ClarifyCard(event.request)) }
                effects.haptic(strong = true)
            }
            is BridgeEvent.RequestWithdrawn -> _state.update { s ->
                s.copy(approvals = s.approvals.filterNot { it.id == event.id }, clarify = s.clarify?.takeUnless { it.id == event.id })
            }
            is BridgeEvent.Stopping -> {
                Telemetry.event("call.stopping", mapOf("reason" to event.reason))
                _state.update {
                    it.copy(
                        phase = CallPhase.Ending,
                        notice = when (event.reason) {
                            StopReason.SpokenStop -> "Saying goodbye…"
                            StopReason.IdleTimeout -> "Nobody has spoken for a while. Hanging up."
                            StopReason.CallCap -> "Call time limit reached. Hanging up."
                            StopReason.UserRequest -> "Ending…"
                        },
                    )
                }
            }
            BridgeEvent.CapWarning -> {
                effect(Earcon.Warning)
                _state.update { it.copy(warning = "This call ends in 1 minute (call time limit).") }
            }
            BridgeEvent.IdleWarning -> {
                effect(Earcon.Warning)
                _state.update { it.copy(warning = "It has gone quiet. Hanging up in 30 seconds unless you speak.") }
            }
            BridgeEvent.IdleWarningCleared -> _state.update { it.copy(warning = null) }
            is BridgeEvent.LiveError -> {
                // The vendor's message can echo the conversation: the code only.
                Telemetry.warn("live.error", null, mapOf("code" to (event.code ?: "none")))
                _state.update { it.copy(notice = event.message) }
            }
            is BridgeEvent.UsageUpdated -> usageNow = event.seconds
            is BridgeEvent.Closed -> onClosed(event.reason, event.usageSeconds)
        }
    }

    private fun onStarted() {
        startedTimeout?.cancel()
        _sessionOpen.value = true
        if (reopening) {
            onReopened()
            onDemand?.sessionReady()
            return
        }
        val reconnected = everLive
        everLive = true
        if (reconnected) {
            Telemetry.event("call.reconnected", mapOf("ms" to clock() - lostAtMs))
        } else {
            Telemetry.event("call.connected", mapOf("connect_ms" to clock() - requestedAtMs, "route" to route.route?.kind))
        }
        if (liveAtMs == null) liveAtMs = clock()
        _state.update {
            it.copy(
                phase = CallPhase.Live,
                startedAtMs = liveAtMs,
                notice = if (reconnected) "Reconnected." else null,
                error = null,
            )
        }
        val b = bridge ?: return
        if (_state.value.muted) b.setMuted(true)
        if (!reconnected) {
            effect(Earcon.Connected)
        } else {
            effect(Earcon.Unmuted)
            b.instruct(
                "The line dropped for a moment and is back now. Tell the user in a few words that you are back." +
                    if (delegationCutOff) " The request you passed to Hermes was cut off; ask whether to try it again." else "",
            )
            delegationCutOff = false
        }
        // Agents: the session note, then any results that waited (tasks outlive the line).
        desk?.attach(b.voice, sessionId = voiceSessionId)
        onDemand?.sessionReady()
        pendingPrompt?.let { prompt ->
            pendingPrompt = null
            captions.appendUserText(prompt)
            _state.update { it.copy(captions = captions.captions) }
            b.delegateText(prompt)
        }
        applyRoute()
    }

    private fun onClosed(reason: String, usage: Double?) {
        usageDone += usage ?: usageNow
        usageNow = 0.0
        _sessionOpen.value = false
        desk?.detach()
        if (dormant && !userEnding && _state.value.inCall) {
            onDormant(reason)
            return
        }
        val wasLive = _state.value.phase == CallPhase.Live
        if (reason == "connection_lost" && !userEnding && wasLive && reconnectsLeft > 0) {
            reconnect()
        } else {
            finish(reason)
        }
    }

    private fun reconnect() {
        lostAtMs = clock()
        Telemetry.warn("call.reconnect", null, mapOf("delegation_cut_off" to delegationActive, "live_s" to liveAtMs?.let { (clock() - it) / 1000 }))
        reconnectsLeft--
        delegationCutOff = delegationActive
        delegationActive = false
        dropTransport()
        effect(Earcon.Warning)
        _state.update {
            // Approval and clarify cards stay: the Hermes turn and the gateway outlive the WebRTC drop.
            it.copy(phase = CallPhase.Reconnecting, notice = "The line dropped. Reconnecting…", tool = null)
        }
        scope.launch {
            delay(RECONNECT_DELAY_MS)
            if (_state.value.phase == CallPhase.Reconnecting) connect()
        }
    }

    private fun settled(problem: String?) {
        delegationActive = false
        touchMemory()
        _state.update { it.copy(tool = null, notice = problem ?: it.notice) }
        evaluate()
    }

    // ── parallel agents ────────────────────────────────────────────────────────────────────

    /** Agents on (a host): the desk, the gateway held for the call, the task sessions' cards on screen. */
    private fun startAgents() {
        val host = runCatching { agentsFactory.create() }
            .onFailure { Telemetry.warn("agents.unavailable", null, mapOf("type" to it.javaClass.simpleName)) }
            .getOrNull() ?: return
        agents = host
        lastAgents = host
        agentHold = runCatching { host.hold() }.getOrNull()
        val d = AgentDesk(scope, host.tasks, host.intents, host.labeler, clock, listener = ::onDeskEvent)
        desk = d
        d.start()
        agentJobs += scope.launch(start = CoroutineStart.UNDISPATCHED) {
            host.approvals.collect { cards ->
                if (!_state.value.inCall) return@collect
                val fresh = cards.any { c -> _state.value.approvals.none { it.id == c.id } }
                _state.update { it.copy(approvals = cards) }
                if (fresh) effects.haptic(strong = true)
            }
        }
        agentJobs += scope.launch(start = CoroutineStart.UNDISPATCHED) {
            host.clarify.collect { card ->
                if (!_state.value.inCall) return@collect
                val fresh = card != null && card.id != _state.value.clarify?.id
                _state.update { it.copy(clarify = card) }
                if (fresh) effects.haptic(strong = true)
            }
        }
        onAgentsLive(true)
    }

    private fun stopAgents() {
        if (agents == null) return
        desk?.stop()
        desk = null
        agentJobs.forEach { it.cancel() }
        agentJobs.clear()
        agentHold?.let { runCatching { it.close() } }
        agentHold = null
        agents = null
        onAgentsLive(false)
    }

    /** The desk's doings as telemetry: kinds, ids and timings only, never what was said. */
    private fun onDeskEvent(event: AgentDeskEvent) {
        when (event) {
            is AgentDeskEvent.Started -> {
                touchMemory()
                Telemetry.event("agents.task", mapOf("event" to "started", "queued" to event.queued, "existing" to event.existing, "typed" to (event.delegationId == null)))
            }
            is AgentDeskEvent.Handled -> Telemetry.event("agents.intent", mapOf("intent" to event.intent))
            is AgentDeskEvent.Failed -> Telemetry.warn("agents.failed", null, mapOf("kind" to event.kind))
            is AgentDeskEvent.Injected -> Telemetry.event("agents.injected", mapOf("count" to event.count, "waited_ms" to event.waitedMs, "commentary" to event.asCommentary))
        }
    }

    // ── on-demand sessions ─────────────────────────────────────────────────────────────────

    /**
     * Close the paid voice session (`session.close`) and keep the call: audio, the Hermes link,
     * the agents and their tasks. Not while a single-conversation delegation runs (it would be
     * cut off), connecting, ending or already closed.
     */
    override fun closeSession(): Boolean {
        val b = bridge ?: return false
        if (_state.value.phase != CallPhase.Live || dormant || reopening || userEnding || delegationActive) return false
        if (b.status.value == BridgeStatus.Connecting || b.status.value == BridgeStatus.Closing || b.status.value == BridgeStatus.Closed) return false
        dormant = true
        Telemetry.event("call.session_close", mapOf("tasks_running" to (desk?.running?.value ?: 0)))
        b.close(StopReason.UserRequest)
        return true
    }

    /**
     * Open a new voice session in the same call: `POST /api/audio/voice-live/session` with the
     * recent transcript and an open-tasks note as history (≤ 24 messages). WebRTC takes only the
     * live mic track, so the words said while it was closed come as text: [preRoll] (on-device
     * speech-to-text) goes in as "The user just said: …" once the session starts.
     */
    override fun reopenSession(preRoll: String?): Boolean {
        if (!dormant || bridge != null || !_state.value.inCall || userEnding) return false
        dormant = false
        reopening = true
        reopenAtMs = clock()
        this.preRoll = preRoll?.trim()?.ifEmpty { null }
        _state.update { it.copy(notice = null) }
        connect()
        return true
    }

    private fun onDormant(reason: String) {
        dropTransport()
        dormantSinceMs = clock()
        Telemetry.event("call.session_closed", mapOf("reason" to reason, "voice_s" to usageDone.toLong()))
        _state.update { it.copy(notice = "Paused. Speak to carry on.", tool = null, level = 0f, activity = CallActivity.Listening) }
    }

    private fun onReopened() {
        reopening = false
        val ms = clock() - reopenAtMs
        lastReopenMs = ms
        Telemetry.event("call.session_reopened", mapOf("ms" to ms, "pre_roll" to (preRoll != null), "tasks_running" to (desk?.running?.value ?: 0)))
        _state.update { it.copy(phase = CallPhase.Live, notice = null, error = null) }
        val b = bridge ?: return
        if (_state.value.muted) b.setMuted(true)
        desk?.attach(b.voice, sessionId = voiceSessionId)
        preRoll?.let { said ->
            b.instruct("While the line was paused the user said: \"${said.take(PRE_ROLL_CHARS)}\". Respond to that now, as if you had heard it.")
        }
        preRoll = null
        applyRoute()
    }

    /** A connect failed: a reopen goes back to closed (the call stays; the controller retries), anything else ends the call. */
    private fun connectFailed(message: String, failure: CallFailure) {
        if (!reopening) {
            finish("failed", error = message, failure = failure)
            return
        }
        Telemetry.warn("call.session_reopen_failed", null, mapOf("after_ms" to clock() - reopenAtMs))
        reopening = false
        dormant = true
        epoch++
        startedTimeout?.cancel()
        dropTransport()
        _sessionOpen.value = false
        dormantSinceMs = clock()
        _state.update { it.copy(phase = CallPhase.Live, notice = "Couldn't reconnect. Speak to try again.") }
        onDemand?.sessionFailed()
    }

    /** Hermes looks reachable (the call's gateway is not backing off or stopped); false with no call. */
    internal val hermesReachable: Boolean
        get() = link?.reachable ?: false

    /** The first session is still connecting (the on-demand link's first open waits for it). */
    internal val connecting: Boolean
        get() = _state.value.inCall && !dormant && !_sessionOpen.value && (reopening || bridge == null || bridge?.status?.value == BridgeStatus.Connecting)

    /**
     * The on-demand link closes: an open session closes ([closeSession] without its idle checks:
     * the controller has made them), a connect in progress is abandoned. The call stays.
     */
    internal fun dropSession() {
        if (!_state.value.inCall || userEnding || finishing) return
        val b = bridge
        val open = b != null && _sessionOpen.value && b.status.value != BridgeStatus.Closing && b.status.value != BridgeStatus.Closed
        if (open) {
            dormant = true
            Telemetry.event("call.session_close", mapOf("tasks_running" to (desk?.running?.value ?: 0)))
            b!!.close(StopReason.UserRequest)
            return
        }
        if (dormant && !reopening) return
        // Opening: give it up.
        epoch++
        startedTimeout?.cancel()
        reopening = false
        dormant = true
        desk?.detach()
        _sessionOpen.value = false
        onDormant("abandoned")
    }

    /** What the user said while the session was closed (on-device text), told once the new session is up. */
    internal fun tellPreRoll(text: String?, missed: Boolean) {
        val b = bridge?.takeIf { _sessionOpen.value } ?: return
        when {
            text != null -> {
                captions.appendUserText(text)
                _state.update { it.copy(captions = captions.captions) }
                b.instruct("While the line was paused the user said: \"${text.take(PRE_ROLL_CHARS)}\". Respond to that now, as if you had heard it.")
            }
            missed -> b.instruct("The user started speaking just before the line reopened, so the start of what they said may be missing. If it seems to begin mid-sentence, ask them to repeat it.")
        }
    }

    /** Nothing in flight for the voice: no single-conversation turn, no delegation being handled, no result it was given and hasn't said. */
    internal fun voiceIdle(): Boolean {
        if (bridge == null || delegationActive) return false
        return desk?.settled(clock()) ?: true
    }

    /**
     * While the session is closed the bridge's idle and cap timers are gone: the call keeps them.
     * True when the call ended.
     */
    private fun dormantLimits(now: Long): Boolean {
        if (!dormant || reopening || bridge != null) return false
        val idleMs = callSettings.idleTimeoutMinutes.coerceAtLeast(0) * 60_000L
        val capMs = callSettings.callCapMinutes.coerceAtLeast(0) * 60_000L
        val lastActive = maxOf(lastUserTextAtMs, dormantSinceMs)
        val busy = desk?.working == true || pendingResults.value > 0 || onDemand?.phoneHearsSpeech == true
        return when {
            capMs > 0 && liveAtMs?.let { now - it >= capMs } == true -> {
                finish("call_cap")
                true
            }
            idleMs > 0 && !busy && now - lastActive >= idleMs -> {
                finish("idle_timeout")
                true
            }
            else -> false
        }
    }

    // ── spoken answers ─────────────────────────────────────────────────────────────────────

    /** A delegation while a card is open: answer the clarify, or an approval when spoken approvals are on. */
    private fun intercept(prompt: DelegationPrompt): Boolean {
        val said = prompt.prompt
        _state.value.clarify?.let { card ->
            val question = card.request.questions.singleOrNull()
            val answer = question?.let { SpokenReplies.clarify(said, it) }
            if (question != null && answer != null) {
                answerClarify(card.id, mapOf(question.qid to answer))
                bridge?.noteProgress("The user answered Hermes's question: $answer. Hermes is carrying on. Not done yet.")
                return true
            }
        }
        val approval = _state.value.approvals.firstOrNull() ?: return false
        val choice = SpokenReplies.approval(said, approval.choices) ?: return false
        if (callSettings.spokenApprovals) {
            answerApproval(approval.id, choice)
            bridge?.noteProgress("The user said ${if (choice == ApprovalChoice.Deny) "deny" else "approve"}. Hermes is carrying on. Not done yet.")
        } else {
            bridge?.noteProgress("Approvals need a tap on the screen. Ask the user to tap Approve or Deny. Not done yet.")
        }
        return true
    }

    /** Spoken approvals (setting, off by default): judge the whole utterance after 1.5 s of quiet. */
    private fun onUserWords(text: String) {
        if (!callSettings.spokenApprovals) return
        utterance.append(text)
        utteranceJob?.cancel()
        utteranceJob = scope.launch {
            delay(UTTERANCE_SETTLE_MS)
            val said = utterance.toString()
            utterance = StringBuilder()
            val card = _state.value.approvals.firstOrNull() ?: return@launch
            SpokenReplies.approval(said, card.choices)?.let { answerApproval(card.id, it) }
        }
    }

    // ── audio ──────────────────────────────────────────────────────────────────────────────

    private fun commentaryLead(): Long {
        val lead = policy.beforeCommentary(clock())
        if (lead > 0) audio.setFocus(FocusMode.Hold)
        return lead
    }

    /** Music policy, orb activity and level; runs on every signal and every [TICK_MS]. */
    private fun evaluate() {
        val s = _state.value
        if (!s.inCall) return
        val now = clock()
        remoteGate.offer(lastRemoteLevel, now)
        val od = onDemand
        val sessionUp = bridge != null
        val userSpeaking = !s.muted && !otherCall && (vad.speaking || now - lastUserTextAtMs < TEXT_SPEECH_MS || od?.phoneHearsSpeech == true)
        val remoteSpeaking = sessionUp && (remoteGate.on || now - lastVoiceTextAtMs < TEXT_SPEECH_MS)
        userSpeakingNow = userSpeaking
        voiceSpeakingNow = remoteSpeaking
        od?.tick()
        if (dormantLimits(now)) return
        bridge?.setRemoteSpeaking(remoteSpeaking)
        val headset = route.route?.kind?.isHeadset == true
        desk?.signals(userSpeaking, remoteSpeaking)
        val working = delegationActive || desk?.working == true
        val base = GapSignals(userSpeaking, remoteSpeaking, working, headset, otherCall)
        val decision = policy.update(od?.gap(base) ?: base, now)
        if (decision.changed) {
            audio.setFocus(decision.mode)
            // Music is about to come back where the mic can hear it: make it the VAD's new floor
            // so it does not read as the user talking and pause itself again.
            if (decision.mode != FocusMode.Hold && !headset) vad.rebaseline(VAD_SETTLE_MS)
        }
        val activity = when {
            remoteSpeaking -> CallActivity.HermesSpeaking
            userSpeaking -> CallActivity.UserSpeaking
            working -> CallActivity.Thinking
            else -> CallActivity.Listening
        }
        val level = when (activity) {
            CallActivity.HermesSpeaking -> remoteLevel
            CallActivity.UserSpeaking -> micLevel
            else -> 0f
        }
        val music = policy.musicAllowed && !otherCall && audio.isMusicActive()
        if (s.activity != activity || s.musicInGap != music || s.level != level) {
            _state.update { it.copy(activity = activity, musicInGap = music, level = level) }
        }
    }

    private fun applyRoute() {
        val r = route.route
        if (r?.kind != lastRouteKind && _state.value.inCall) {
            Telemetry.event(
                "audio.route_change",
                mapOf("from" to lastRouteKind, "to" to r?.kind, "manual" to route.manual, "available" to devices.map { it.kind.name }.sorted().joinToString(",")),
            )
            lastRouteKind = r?.kind
        }
        if (r != null) audio.select(r)
        effects.proximity(_state.value.inCall && r?.kind == RouteKind.Earpiece)
        _state.update { it.copy(route = r, routes = devices) }
    }

    private fun effect(kind: Earcon) {
        if (callSettings.earcons) effects.earcon(kind)
        if (callSettings.haptics) effects.haptic(strong = kind == Earcon.Warning)
    }

    // ── teardown ───────────────────────────────────────────────────────────────────────────

    private fun finish(reason: String, error: String? = null, failure: CallFailure? = null) {
        if (!_state.value.inCall) return
        finishing = true
        epoch++
        val endedAt = clock()
        val tasksRunning = desk?.running?.value ?: 0
        val heldOffCall = tasksRunning > 0 && runCatching { offCall(tasksRunning) }.getOrDefault(false)
        onDemand?.stop()
        onDemand = null
        stopAgents()
        Telemetry.event(
            "call.end",
            mapOf(
                "reason" to reason,
                "failed" to (error != null),
                "ever_live" to everLive,
                "duration_s" to (liveAtMs?.let { (endedAt - it) / 1000 } ?: 0L),
                "voice_s" to (usageDone + usageNow).toLong(),
                "user_ended" to userEnding,
                "reconnects_used" to (1 - reconnectsLeft).coerceAtLeast(0),
                "agents" to (lastAgents != null),
                "tasks_running" to tasksRunning,
                "off_call" to heldOffCall,
                "failure" to failure?.wire,
            ),
        )
        tickJob?.cancel()
        utteranceJob?.cancel()
        startedTimeout?.cancel()
        dropTransport()
        touchMemory()
        link?.close()
        link = null
        effects.proximity(false)
        audio.end()
        if (everLive || error == null) effect(Earcon.Ended)
        val summary = if (everLive) {
            CallSummary(
                durationMs = liveAtMs?.let { endedAt - it } ?: 0L,
                voiceSeconds = usageDone.takeIf { it > 0 },
                reason = endReason(reason) + if (tasksRunning > 0) " Hermes's tasks carry on and you'll get a notification." else "",
            )
        } else {
            null
        }
        _state.value = CallUiState(
            phase = if (error != null && !everLive) CallPhase.Failed else CallPhase.Ended,
            summary = summary,
            error = error,
            captions = captions.captions,
            // A line that dropped for good while offline can go on as a memo too.
            failure = failure ?: CallFailure.Offline.takeIf { reason == "connection_lost" && !online() },
        )
    }

    private fun dropTransport() {
        sender?.clear()
        sender = null
        transport?.close()
        transport = null
        bridge = null
        remoteGate.reset()
    }

    private fun touchMemory() {
        val id = link?.storedSessionId ?: return
        memory.save(SessionMemory(id, clock()))
    }

    private fun resetCall() {
        captions.clear()
        finishing = false
        dormant = false
        reopening = false
        preRoll = null
        lastReopenMs = null
        lastAgents = null
        _sessionOpen.value = false
        devices = emptyList()
        route = RouteChoice(null, false)
        lastUserTextAtMs = Long.MIN_VALUE / 2
        lastVoiceTextAtMs = Long.MIN_VALUE / 2
        delegationActive = false
        delegationCutOff = false
        userEnding = false
        everLive = false
        liveAtMs = null
        usageDone = 0.0
        usageNow = 0.0
        autoMuted = false
        otherCall = false
        utterance = StringBuilder()
        micLevel = 0f
        remoteLevel = 0f
        lastRemoteLevel = 0.0
        lastRouteKind = null
    }

    companion object {
        const val TICK_MS = 150L
        const val TEXT_SPEECH_MS = 700L
        const val UTTERANCE_SETTLE_MS = 1_500L
        const val START_TIMEOUT_MS = 60_000L
        const val STARTED_TIMEOUT_MS = 20_000L
        const val RECONNECT_DELAY_MS = 600L
        const val VAD_SETTLE_MS = 1_200L
        const val PRE_ROLL_CHARS = 600

        /** Plain English for `session.closed` reasons and our own. */
        fun endReason(reason: String): String = when (reason.trim()) {
            "", "closed", "close_requested", "client_closed", "client_close" -> "Call ended."
            "connection_lost" -> "The connection dropped."
            "failed" -> "The call failed."
            else -> "Hermes Live ended the call (${reason.trim().replace('_', ' ')})."
        }

        /** Safe attributes for a failure: type and codes, never the message (it can echo the server). */
        fun failureAttrs(e: Throwable): Map<String, Any?> = buildMap {
            put("type", e.javaClass.simpleName)
            when (e) {
                is VoiceLiveException -> {
                    put("kind", e.kind.name)
                    put("http_status", e.httpCode)
                }
                is HermesAuthException.Unavailable -> put("http_status", e.httpCode)
                is HermesRpcException -> put("code", e.code)
                else -> Unit
            }
        }

        /** Why a start failed, as a [CallFailure] (the screen offers a memo for all but [CallFailure.Other]). */
        fun failureKind(e: Throwable, online: Boolean): CallFailure = when {
            !online -> CallFailure.Offline
            e is VoiceLiveException -> when {
                e.kind == VoiceLiveException.Kind.NotConfigured || e.kind == VoiceLiveException.Kind.VendorRejected || e.httpCode == 503 -> CallFailure.VoiceUnavailable
                e.kind == VoiceLiveException.Kind.Failed && e.httpCode in 500..599 -> CallFailure.HermesUnreachable
                else -> CallFailure.Other
            }
            e is HermesAuthException.Unavailable -> CallFailure.HermesUnreachable
            e is HermesAuthException -> CallFailure.Other
            e is java.io.IOException -> CallFailure.HermesUnreachable
            else -> CallFailure.Other
        }

        /** Plain English for a failed start. */
        fun describe(e: Throwable): String = when (e) {
            is HermesAuthException.SignInRequired -> "Sign in to Hermes again."
            is HermesAuthException.InvalidCredentials -> "Hermes no longer accepts the saved password. Sign in again."
            is HermesAuthException -> e.message ?: "Could not sign in to Hermes."
            is VoiceLiveException -> when (e.kind) {
                VoiceLiveException.Kind.NotConfigured -> "Voice is not set up on this Hermes server (no OpenAI key)."
                VoiceLiveException.Kind.VendorRejected -> "OpenAI refused the voice session. Try again in a moment."
                VoiceLiveException.Kind.Unauthorized -> "Sign in to Hermes again."
                VoiceLiveException.Kind.EmptyOffer, VoiceLiveException.Kind.Failed -> "Could not start the voice session."
            }
            else -> {
                val text = e.message.orEmpty()
                when {
                    text.contains("CLEARTEXT", ignoreCase = true) || text.contains("cleartext", ignoreCase = true) ->
                        "Android refused plain http:// to that address. Use the *.ts.net name or https://."
                    text.contains("Unable to resolve host", ignoreCase = true) -> "Could not find the Hermes server. Check the network."
                    text.contains("microphone", ignoreCase = true) -> "The microphone is not available."
                    else -> "Could not connect to Hermes Live."
                }
            }
        }
    }
}
