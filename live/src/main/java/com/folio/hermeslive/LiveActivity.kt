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
import com.folio.hermeslive.call.CallRequest
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The one screen: sign in, the call, settings. Every call starts here (foreground, mic granted). */
class LiveActivity : ComponentActivity() {
    private var pendingStart: CallRequest? = null
    private var afterSignIn: CallRequest? = null
    private var showSettings by mutableStateOf(false)
    private var signIn by mutableStateOf(SignInUi())
    private var micRefused by mutableStateOf(false)

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
        val request = launch.request ?: return
        lifecycleScope.launch {
            graph.ready.await()
            val engine = graph.engine
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

    private fun requestStart(request: CallRequest) {
        micRefused = false
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

    private fun observeWindowFlags() {
        lifecycleScope.launch {
            var wasInCall = false
            graph.engine.state.collect { state ->
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
        val call by g.engine.state.collectAsStateWithLifecycle()
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
                actions = SettingsActions(
                    onBack = { showSettings = false },
                    onChange = { change -> g.settings.update(change) },
                    onSignOut = {
                        g.engine.end()
                        scope.launch { g.account.signOut() }
                        showSettings = false
                    },
                    onResetConversation = { g.settings.save(SessionMemory.Empty) },
                    onRefreshVoice = { scope.launch { g.account.refreshVoiceStatus() } },
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
            else -> CallScreen(
                state = if (micRefused && !call.inCall) call.copy(error = "Hermes Live needs the microphone. Allow it in Settings › Apps.") else call,
                elapsed = elapsed,
                voiceLine = voice?.let { "Voice: ${voiceText(it)}" },
                actions = CallActions(
                    onTalk = {
                        g.engine.dismissSummary()
                        requestStart(CallRequest())
                    },
                    onEnd = g.engine::end,
                    onMute = g.engine::toggleMute,
                    onRoute = g.engine::selectRoute,
                    onApprove = g.engine::answerApproval,
                    onClarify = g.engine::answerClarify,
                    onSettings = { showSettings = true },
                    onDismissSummary = g.engine::dismissSummary,
                    onNewConversation = {
                        g.engine.dismissSummary()
                        requestStart(CallRequest(newConversation = true))
                    },
                ),
            )
        }
    }
}
