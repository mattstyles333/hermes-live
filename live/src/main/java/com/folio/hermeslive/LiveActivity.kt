package com.folio.hermeslive

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import com.folio.hermes.telemetry.FlushResult
import com.folio.hermes.telemetry.Telemetry
import com.folio.hermeslive.call.CallPlan
import com.folio.hermeslive.call.CallRequest
import com.folio.hermeslive.call.CallUiState
import com.folio.hermeslive.cascade.CascadeAppRuntime
import com.folio.hermeslive.cascade.CascadeCallEngine
import com.folio.hermeslive.call.CallEngine
import com.folio.hermeslive.call.ondemand.OnDemandUi
import com.folio.hermeslive.grok.GrokCallEngine
import com.folio.hermeslive.ui.GrokCallRoute
import com.folio.hermeslive.ui.GptLiveCallRoute
import com.folio.hermeslive.cascade.CascadeSignOut
import com.folio.hermeslive.cascade.appRuntime
import com.folio.hermeslive.data.VoiceShell
import com.folio.hermeslive.data.KeyStatus
import com.folio.hermeslive.data.PrepState
import com.folio.hermeslive.data.VoiceEngine
import com.folio.hermeslive.service.MemoEntry
import com.folio.hermeslive.service.ResultNotifications
import com.folio.hermeslive.ui.CascadeCallRoute
import com.folio.hermeslive.ui.MainScreen
import com.folio.hermeslive.ui.mainScreen
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import com.folio.hermeslive.ui.PreparingBill
import com.folio.hermeslive.meeting.MeetingEntry
import com.folio.hermeslive.meeting.MeetingHost
import com.folio.hermeslive.meeting.MeetingScreen
import com.folio.hermeslive.ui.meeting.MeetingRoute
import com.folio.hermeslive.ui.meeting.MeetingStarter
import com.folio.hermeslive.ui.meeting.rememberMeetingStarter
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.runtime.remember
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import com.folio.hermeslive.call.SessionMemory
import com.folio.hermeslive.data.AccountState
import com.folio.hermeslive.data.SignInResult
import com.folio.hermeslive.service.CallService
import com.folio.hermeslive.ui.CallActions
import com.folio.hermeslive.ui.CallScreen
import com.folio.hermeslive.ui.LiveTheme
import com.folio.hermeslive.ui.SettingsActions
import com.folio.hermeslive.ui.SettingsScreen
import com.folio.hermeslive.ui.SignInScreen
import com.folio.hermeslive.ui.SignInUi
import com.folio.hermeslive.ui.clock
import com.folio.hermeslive.ui.HomeVoiceLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The one screen: sign in, the call, settings. Every call starts here (foreground, mic granted). */
class LiveActivity : ComponentActivity() {
    private var pendingStart: CallRequest? = null
    private var afterSignIn: CallRequest? = null
    private var showSettings by mutableStateOf(false)
    private var signIn by mutableStateOf(SignInUi())
    private var micRefused by mutableStateOf(false)
    private var diagnosticsStatus by mutableStateOf<String?>(null)

    /** A call waiting for "Preparing Bill…" (spec v2 §14 item 10). */
    private var preparing by mutableStateOf<CallRequest?>(null)

    /** Bill's screen with no call, on a result notification's Open (§14 item 9). */
    private var viewingTask by mutableStateOf<String?>(null)

    /** Meeting mode asked for by an intent (the shortcut, the notification): its entry, until the screen takes it. */
    private var meetingAsk by mutableStateOf<String?>(null)

    /** Bill's call screen was up when the call ended: it plays its end transition until closed. */
    private var billOutro by mutableStateOf(false)

    /** Leaves Bill's end transition for home (Close, Back, the summary's hold, sign-out). */
    private fun closeBillOutro() {
        if (!billOutro) return
        billOutro = false
        if (!graph.calls.state.value.inCall) graph.calls.dismissSummary()
    }

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val request = pendingStart ?: return@registerForActivityResult
        pendingStart = null
        val mic = result[Manifest.permission.RECORD_AUDIO] ?: hasMic()
        if (mic) {
            startCall(request)
        } else {
            micRefused = true
            billOutro = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handle(intent)
        setContent { LiveTheme { Root() } }
        observeWindowFlags()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val launch = LiveIntents.parse(intent)
        if (launch.wake) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        if (intent?.action == MemoEntry.ACTION_MEMO) MemoEntry.reportUsed(this)
        when (MeetingEntry.parse(intent)) {
            MeetingEntry.Ask.Open -> {
                if (intent?.action == MeetingEntry.ACTION_MEETING) MeetingEntry.reportUsed(this)
                meetingAsk = "shortcut"
                return
            }
            MeetingEntry.Ask.End -> {
                MeetingHost.existing()?.flow?.end()
                return
            }
            MeetingEntry.Ask.None -> Unit
        }
        launch.openTaskId?.let(::openTask)
        val request = launch.request ?: return
        lifecycleScope.launch {
            graph.ready.await()
            val engine = graph.calls
            if (engine.state.value.inCall) {
                request.prompt?.let(engine::submitText)
                return@launch
            }
            if (graph.account.state.value !is AccountState.SignedIn) {
                if (!launch.onlyIfStartOnOpen) afterSignIn = request
                return@launch
            }
            if (launch.onlyIfStartOnOpen && !graph.settings.settings.value.startOnOpen) return@launch
            requestStart(request)
        }
    }

    /** A result notification's Open: Bill's screen on the stored tasks, no call (the off-call restore). */
    private fun openTask(taskId: String) {
        ResultNotifications.cancel(this, taskId)
        lifecycleScope.launch {
            graph.ready.await()
            val shell = VoiceShell.of(this@LiveActivity)
            val rt = withContext(Dispatchers.Default) { shell.shell.appRuntime() } ?: return@launch
            rt.core.restore()
            viewingTask = taskId
        }
    }

    private fun requestStart(request: CallRequest) {
        // The mic belongs to meeting mode while it takes notes: no call starts over it.
        if (MeetingHost.existing()?.flow?.busy == true) return
        micRefused = false
        val plan = graph.calls.plan(request)
        if (plan == CallPlan.PreparingBill || plan == CallPlan.NoVoice) {
            billOutro = false
            preparing = request
            VoiceShell.of(this).autoPrepare()
            return
        }
        preparing = null
        viewingTask = null
        val missing = buildList {
            if (!hasMic()) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this@LiveActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (missing.isEmpty()) {
            startCall(request)
        } else {
            pendingStart = request
            permissions.launch(missing.toTypedArray())
        }
    }

    private fun startCall(request: CallRequest) {
        // The microphone foreground service may only start while this activity is visible.
        lifecycleScope.launch {
            withResumed { CallService.start(this@LiveActivity, request) }
        }
    }

    private fun hasMic() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeWindowFlags() {
        lifecycleScope.launch {
            var wasInCall = false
            graph.calls.active.flatMapLatest { it?.state ?: flowOf(CallUiState()) }.collect { state ->
                val keepOn = state.inCall && graph.settings.settings.value.keepScreenOn
                if (keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (wasInCall && !state.inCall) {
                    // Woken over the lock screen for a call: once it ends, hide behind the keyguard again.
                    setShowWhenLocked(false)
                    setTurnScreenOn(false)
                }
                wasInCall = state.inCall
            }
        }
    }

    @Composable
    private fun Root() {
        val g = graph
        val active by g.calls.active.collectAsStateWithLifecycle()
        val call by (active ?: g.calls).state.collectAsStateWithLifecycle()
        val account by g.account.state.collectAsStateWithLifecycle()
        val settings by g.settings.settings.collectAsStateWithLifecycle()
        val voice by g.account.voiceStatus.collectAsStateWithLifecycle()
        // The idle screen's voice line follows the chosen engine: Bill's readiness, not GPT-Live's status.
        val voiceLine = if (settings.voiceEngine == VoiceEngine.Cascade) {
            val shell = remember { VoiceShell.of(this@LiveActivity) }
            val key by shell.keys.status.collectAsStateWithLifecycle()
            val prep by shell.prep.state.collectAsStateWithLifecycle()
            val models by shell.speechModels.status.collectAsStateWithLifecycle()
            HomeVoiceLine.of(settings, voice, key, prep, models)
        } else if (settings.voiceEngine == VoiceEngine.Grok) {
            val shell = remember { VoiceShell.of(this@LiveActivity) }
            val xai by (shell.xaiKeys?.status ?: kotlinx.coroutines.flow.MutableStateFlow(KeyStatus())).collectAsStateWithLifecycle()
            HomeVoiceLine.of(settings, voice, KeyStatus(), PrepState(), emptyList(), xai)
        } else {
            HomeVoiceLine.of(settings, voice, KeyStatus(), PrepState(), emptyList())
        }
        val scope = rememberCoroutineScope()
        val elapsed by produceState<String?>(null, call.startedAtMs, call.inCall) {
            val started = call.startedAtMs
            while (started != null && call.inCall) {
                value = clock(System.currentTimeMillis() - started)
                delay(1_000)
            }
        }
        LaunchedEffect(account) {
            if (account is AccountState.SignedIn && voice == null) g.account.refreshVoiceStatus()
        }
        BackHandler(enabled = showSettings) { showSettings = false }
        // Meeting mode: built only while Settings › Meeting mode is on (or a meeting is under way).
        val meetingOn by remember { MeetingHost.prefs(this@LiveActivity) }.settings.collectAsStateWithLifecycle()
        val meetingHost = remember(meetingOn.enabled) { MeetingHost.ifEnabled(this@LiveActivity) }
        val meetingScreen by (meetingHost?.flow?.screen ?: remember { MutableStateFlow<MeetingScreen>(MeetingScreen.None) }).collectAsStateWithLifecycle()
        val meetingStarter = meetingHost?.let { rememberMeetingStarter(it) }
        LaunchedEffect(meetingAsk, meetingStarter) {
            val entry = meetingAsk ?: return@LaunchedEffect
            meetingAsk = null
            if (!call.inCall) meetingStarter?.open(entry)
        }
        when {
            showSettings -> SettingsScreen(
                settings = settings,
                account = account,
                voice = voice,
                version = BuildConfig.VERSION_NAME,
                diagnosticsBuiltIn = BuildConfig.DIAGNOSTICS_URL.isNotBlank(),
                diagnosticsStatus = diagnosticsStatus,
                actions = SettingsActions(
                    onBack = { showSettings = false },
                    onChange = { change -> g.settings.update(change) },
                    onSignOut = {
                        billOutro = false
                        MeetingHost.existing()?.flow?.signedOut()
                        g.calls.end()
                        scope.launch {
                            g.account.signOut()
                            CascadeSignOut.apply(VoiceShell.of(this@LiveActivity))
                        }
                        showSettings = false
                    },
                    onResetConversation = { g.settings.save(SessionMemory.Empty) },
                    onRefreshVoice = { scope.launch { g.account.refreshVoiceStatus() } },
                    onSendTestEvent = {
                        diagnosticsStatus = "Sending…"
                        scope.launch {
                            Telemetry.event("diagnostics.test", mapOf("screen" to "settings"))
                            diagnosticsStatus = describe(withContext(Dispatchers.IO) { Telemetry.flush() })
                        }
                    },
                ),
            )
            meetingHost != null && meetingStarter != null && meetingScreen !is MeetingScreen.None && !call.inCall ->
                MeetingRoute(meetingHost, meetingScreen, meetingStarter, onOpenSettings = { showSettings = true })
            account !is AccountState.SignedIn && !call.inCall -> SignInScreen(
                ui = signIn.copy(reason = (account as? AccountState.NeedsSignIn)?.reason),
                onSignIn = { url, user, password ->
                    signIn = signIn.copy(busy = true, error = null)
                    scope.launch {
                        when (val result = g.account.signIn(url, user, password)) {
                            is SignInResult.Ok -> {
                                signIn = SignInUi()
                                // Bill's runtime is built for one account: the next call builds it for this one.
                                VoiceShell.of(this@LiveActivity).shell.release()
                                afterSignIn?.let { pending ->
                                    afterSignIn = null
                                    requestStart(pending)
                                }
                            }
                            is SignInResult.Failed -> signIn = signIn.copy(busy = false, error = result.message, serverUrl = url)
                        }
                    }
                },
                onSettings = { showSettings = true },
            )
            preparing != null && !call.inCall -> {
                val shell = remember { VoiceShell.of(this@LiveActivity) }
                val prep by shell.prep.state.collectAsStateWithLifecycle()
                val pending = preparing!!
                LaunchedEffect(prep.phase, prep.models) {
                    if (g.calls.plan(pending) is CallPlan.Ready) requestStart(pending)
                }
                PreparingBill(
                    prep = prep,
                    noVoice = g.calls.plan(pending) == CallPlan.NoVoice,
                    wifi = shell.network.wifi(),
                    onDownload = { shell.prep.start() },
                    onUseGptLive = { requestStart(pending.copy(forceGptLive = true)) },
                    onCancel = { preparing = null },
                )
            }
            else -> {
                // Grok's call uses Bill's screen too (grok-voice slice).
                val grokCall = active as? GrokCallEngine
                // GPT-Live with parallel agents uses Bill's screen too (its task tray).
                val gptAgents = (active as? CallEngine)?.takeIf { it.agentSession?.runtime != null }
                val billActive = active is CascadeCallEngine || grokCall != null || gptAgents != null
                val hasSession = (active as? CascadeCallEngine)?.session != null || grokCall?.session != null || gptAgents != null
                val screen = mainScreen(billActive, call.inCall, hasSession, billOutro, viewingTask != null)
                LaunchedEffect(screen) { if (screen == MainScreen.Bill) billOutro = true }
                // Bill's end fades into home; every other change cuts as before. Bill's three screens are one
                // content, so the call screen (its scene and memory) carries on into its end transition.
                AnimatedContent(
                    screen,
                    transitionSpec = {
                        if (initialState == MainScreen.BillEnded && targetState == MainScreen.Home) {
                            fadeIn(tween(BILL_TO_HOME_MS)) togetherWith fadeOut(tween(BILL_TO_HOME_MS))
                        } else {
                            EnterTransition.None togetherWith ExitTransition.None
                        }
                    },
                    contentKey = { it == MainScreen.Home },
                    label = "main",
                ) { shown ->
                    when {
                        shown == MainScreen.Home -> Home(call, elapsed, voiceLine, meetingStarter)
                        grokCall != null && shown != MainScreen.BillViewer -> GrokScreen(grokCall, shown, call, settings.haptics)
                        gptAgents != null && shown != MainScreen.BillViewer -> GptLiveAgentsScreen(gptAgents, shown, call, settings.haptics)
                        else -> BillScreen(shown, call, settings.pauseMs, settings.haptics)
                    }
                }
            }
        }
    }

    /** Bill's call screen: the call, its end transition, or the no-call viewer. */
    @Composable
    private fun BillScreen(shown: MainScreen, call: CallUiState, pauseMs: Int, haptics: Boolean) {
        val g = graph
        val engine = g.calls.bill() as? CascadeCallEngine
        val rt = engine?.session ?: (VoiceShell.of(this@LiveActivity).shell.existing() as? CascadeAppRuntime)
        if (engine == null || rt == null) {
            LaunchedEffect(Unit) {
                viewingTask = null
                billOutro = false
            }
            return
        }
        val shell = remember { VoiceShell.of(this@LiveActivity) }
        BackHandler(enabled = shown == MainScreen.BillViewer) { viewingTask = null }
        CascadeCallRoute(
            engine = engine,
            runtime = rt,
            pauseMs = pauseMs,
            viewer = shown == MainScreen.BillViewer,
            decisions = { runCatching { shell.decisionLog.records().asReversed() }.getOrDefault(emptyList()) },
            onHaptic = { if (haptics) g.haptic() },
            onClose = { viewingTask = null },
            ending = shown == MainScreen.BillEnded && !call.inCall,
            onCloseEnded = ::closeBillOutro,
            onCallAgain = { requestStart(CallRequest()) },
        )
    }

    /** Grok's call on Bill's screen, and its end transition. */
    @Composable
    private fun GrokScreen(engine: GrokCallEngine, shown: MainScreen, call: CallUiState, haptics: Boolean) {
        val g = graph
        val rt = engine.session
        if (rt == null) {
            LaunchedEffect(Unit) { billOutro = false }
            return
        }
        GrokCallRoute(
            engine = engine,
            runtime = rt,
            onHaptic = { if (haptics) g.haptic() },
            ending = shown == MainScreen.BillEnded && !call.inCall,
            onCloseEnded = ::closeBillOutro,
            onCallAgain = { requestStart(CallRequest()) },
            onUseBill = {
                // Grok is offline: Bill takes notes offline and sends them once the network is back.
                engine.end()
                billOutro = false
                requestStart(CallRequest(memo = true, forceBill = true, entry = "grok_offline"))
            },
        )
    }

    /** GPT-Live's call with parallel agents on Bill's screen, and its end transition. */
    @Composable
    private fun GptLiveAgentsScreen(engine: CallEngine, shown: MainScreen, call: CallUiState, haptics: Boolean) {
        val g = graph
        val rt = engine.agentSession?.runtime
        if (rt == null) {
            LaunchedEffect(Unit) { billOutro = false }
            return
        }
        GptLiveCallRoute(
            engine = engine,
            runtime = rt,
            onHaptic = { if (haptics) g.haptic() },
            ending = shown == MainScreen.BillEnded && !call.inCall,
            onCloseEnded = ::closeBillOutro,
            onCallAgain = { requestStart(CallRequest()) },
        )
    }

    /** The idle home screen (and GPT-Live's call screen). */
    @Composable
    private fun Home(call: CallUiState, elapsed: String?, voiceLine: String?, meeting: MeetingStarter?) {
        val g = graph
        val gptLive = g.calls.active.collectAsStateWithLifecycle().value as? CallEngine
        val session by (gptLive?.onDemandUi ?: remember { MutableStateFlow(OnDemandUi()) }).collectAsStateWithLifecycle()
        CallScreen(
            session = session,
            state = if (micRefused && !call.inCall) call.copy(error = "Hermes Live needs the microphone. Allow it in Settings › Apps.") else call,
            elapsed = elapsed,
            voiceLine = voiceLine,
            actions = CallActions(
                onTalk = {
                    g.calls.dismissSummary()
                    requestStart(CallRequest())
                },
                onEnd = g.calls::end,
                onMute = g.calls::toggleMute,
                onRoute = g.calls::selectRoute,
                onApprove = g.calls::answerApproval,
                onClarify = g.calls::answerClarify,
                onSettings = { showSettings = true },
                onDismissSummary = g.calls::dismissSummary,
                onNewConversation = {
                    g.calls.dismissSummary()
                    requestStart(CallRequest(newConversation = true))
                },
                onMeeting = meeting?.let { m -> { m.open("app") } },
            ),
        )
    }

    private companion object {
        /** Bill's end transition crossfades into home over this long. */
        const val BILL_TO_HOME_MS = 450
    }
}

/** What "Send test event" shows. */
internal fun describe(result: FlushResult): String = when (result) {
    is FlushResult.Sent -> "✓ Sent (HTTP ${result.status})"
    is FlushResult.Failed -> if (result.status > 0) "Failed: HTTP ${result.status}" else "Failed: ${result.reason}"
    FlushResult.Off -> "Off: switched off or no endpoint"
    FlushResult.Nothing -> "Nothing to send"
    FlushResult.BackingOff -> "Waiting to retry"
}
