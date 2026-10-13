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
import com.folio.hermeslive.cascade.CascadeSignOut
import com.folio.hermeslive.data.VoiceShell
import com.folio.hermeslive.service.MemoEntry
import com.folio.hermeslive.service.ResultNotifications
import com.folio.hermeslive.ui.CascadeCallRoute
import com.folio.hermeslive.ui.PreparingBill
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
import com.folio.hermeslive.ui.voiceText
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

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val request = pendingStart ?: return@registerForActivityResult
        pendingStart = null
        val mic = result[Manifest.permission.RECORD_AUDIO] ?: hasMic()
        if (mic) startCall(request) else micRefused = true
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
            val rt = withContext(Dispatchers.Default) { shell.shell.runtime() as? CascadeAppRuntime } ?: return@launch
            rt.core.restore()
            viewingTask = taskId
        }
    }

    private fun requestStart(request: CallRequest) {
        micRefused = false
        if (graph.calls.plan(request) == CallPlan.PreparingBill) {
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
                    wifi = shell.network.wifi(),
                    onDownload = { shell.prep.start() },
                    onUseGptLive = { requestStart(pending.copy(forceGptLive = true)) },
                    onCancel = { preparing = null },
                )
            }
            (active is CascadeCallEngine && call.inCall && (active as CascadeCallEngine).session != null) || (viewingTask != null && !call.inCall) -> {
                val engine = g.calls.bill() as? CascadeCallEngine
                val rt = engine?.session ?: (VoiceShell.of(this@LiveActivity).shell.existing() as? CascadeAppRuntime)
                if (engine == null || rt == null) {
                    LaunchedEffect(Unit) { viewingTask = null }
                } else {
                    val shell = remember { VoiceShell.of(this@LiveActivity) }
                    BackHandler(enabled = viewingTask != null && !call.inCall) { viewingTask = null }
                    CascadeCallRoute(
                        engine = engine,
                        runtime = rt,
                        pauseMs = settings.pauseMs,
                        viewer = !call.inCall,
                        decisions = { runCatching { shell.decisionLog.records().asReversed() }.getOrDefault(emptyList()) },
                        onHaptic = { if (settings.haptics) g.haptic() },
                        onClose = { viewingTask = null },
                    )
                }
            }
            else -> CallScreen(
                state = if (micRefused && !call.inCall) call.copy(error = "Hermes Live needs the microphone. Allow it in Settings › Apps.") else call,
                elapsed = elapsed,
                voiceLine = voice?.let { "Voice: ${voiceText(it)}" },
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
                ),
            )
        }
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
