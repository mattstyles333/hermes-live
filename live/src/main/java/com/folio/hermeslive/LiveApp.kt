package com.folio.hermeslive

import android.app.Application
import android.content.Context
import com.folio.hermes.telemetry.Telemetry
import com.folio.hermeslive.audio.AndroidCallAudio
import com.folio.hermeslive.audio.AndroidCallEffects
import com.folio.hermeslive.audio.CascadeAudioFactory
import com.folio.hermeslive.call.CallEngine
import com.folio.hermeslive.call.CallSwitch
import com.folio.hermeslive.cascade.CallSpeechFactory
import com.folio.hermeslive.cascade.CascadeApp
import com.folio.hermeslive.cascade.AndroidNetwork
import com.folio.hermeslive.cascade.CascadeCallEngine
import com.folio.hermeslive.grok.GrokAudioFactory
import com.folio.hermeslive.grok.GrokCallEngine
import com.folio.hermeslive.grok.GrokNetwork
import com.folio.hermeslive.cascade.SttSpeechModels
import com.folio.hermeslive.cascade.appRuntime
import com.folio.hermeslive.data.CascadeFactory
import com.folio.hermeslive.data.CascadeHost
import com.folio.hermeslive.data.SpeechModels
import com.folio.hermeslive.data.VoiceShell
import com.folio.hermeslive.service.OffCall
import com.folio.hermeslive.service.OffCallHooks
import com.folio.hermeslive.stt.ModelManager
import java.io.File
import com.folio.hermeslive.call.HermesLinkFactory
import com.folio.hermeslive.call.LiveTransportFactory
import com.folio.hermeslive.data.Account
import com.folio.hermeslive.data.KeystoreSecretBox
import com.folio.hermeslive.data.SecureTokenStore
import com.folio.hermeslive.data.SettingsStore
import com.folio.hermeslive.hermes.RealHermesLink
import com.folio.hermeslive.hermes.SignedOutLink
import com.folio.hermeslive.hooks.BuildHooks
import com.folio.hermeslive.provider.ConnectionProvider
import com.folio.hermeslive.service.CallNotifications
import com.folio.hermeslive.webrtc.WebRtcTransport
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import okhttp3.OkHttpClient

class LiveApp : Application(), CascadeHost {
    /** Built on first use, so the connection provider (created before onCreate) can reach it too. */
    val graph: LiveGraph by lazy { LiveGraph(this) }

    // ── CascadeHost (S-E's shell asks for these; nothing is built until the gate opens) ──
    override val cascadeFactory: CascadeFactory get() = graph.cascadeApp.factory
    override val speechModels: SpeechModels get() = graph.speechModels
    override val offCallHooks: OffCallHooks get() = graph.offCallHooks

    override fun onCreate() {
        super.onCreate()
        // First: so a crash anywhere below is still caught. Off unless an endpoint is configured.
        val prefs = getSharedPreferences(SettingsStore.PREFS, Context.MODE_PRIVATE)
        Telemetry.install(this, "hermes-live", BuildConfig.VERSION_NAME) {
            SettingsStore.diagnosticsEndpoint(prefs, BuildConfig.DIAGNOSTICS_URL)
        }
        CallNotifications.createChannels(this)
        graph.ready // start restoring the session
    }
}

/** The app's objects. One engine per process; the service, activity, tile and provider share it. */
class LiveGraph(private val app: Context) {
    val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val client: OkHttpClient = OkHttpClient.Builder()
        // The system DNS, except in debug builds under an end-to-end test (BuildHooks).
        .dns(BuildHooks.dns)
        // Network timing for the emulator smoke (debug and staging only; none in release).
        .eventListenerFactory(BuildHooks.eventListenerFactory)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val settings = SettingsStore(app.getSharedPreferences(SettingsStore.PREFS, Context.MODE_PRIVATE))
    val tokens = SecureTokenStore(app.getSharedPreferences("hermes_live_secure", Context.MODE_PRIVATE), KeystoreSecretBox())
    val account = Account(settings, tokens, client, ioScope) {
        app.contentResolver.notifyChange(ConnectionProvider.URI, null)
    }

    /** Completes once the stored session (if any) is loaded. No network. */
    val ready: Deferred<Unit> = ioScope.async { account.restore() }

    private val links = HermesLinkFactory { stored ->
        val config = account.config()
        val auth = account.currentAuth()
        if (config != null && auth != null) RealHermesLink(config, auth, client, ioScope, stored) else SignedOutLink
    }

    private val transports = LiveTransportFactory { events -> WebRtcTransport(app, events) }

    /** S-C's model store: `noBackupFilesDir/stt` (where the emulator tests installed them too). Lazy: no file is touched before use. */
    val modelManager: ModelManager by lazy { ModelManager(File(app.noBackupFilesDir, "stt")) }

    val speechModels: SpeechModels by lazy { SttSpeechModels(modelManager, ioScope) }

    val cascadeApp: CascadeApp by lazy {
        CascadeApp(
            context = app,
            client = client,
            hermes = {
                val config = account.config()
                val auth = account.currentAuth()
                if (config != null && auth != null) config to auth else null
            },
            // Bill's goodbye (`Effect.EndCall`) plays to the end while the call winds down.
            onEndCall = { calls.cascadeEngine.let { if (it is CascadeCallEngine) it.goodbyeSaid() else it?.end() } },
            onHaptic = { cascadeEffects.haptic(strong = false) },
            user = { settings.username },
        )
    }

    val offCallHooks: OffCallHooks by lazy { cascadeApp.offCallHooks({ VoiceShell.of(app).shell.appRuntime() }, { ready.await() }) }

    private val cascadeEffects by lazy { AndroidCallEffects(app) }

    /** A light haptic tick for Bill's screen (turn committed, task finished). */
    fun haptic() = cascadeEffects.haptic(strong = false)

    /**
     * The engine switch: every call goes through here (service, activity, tile, notification
     * actions, provider). GPT-Live unless Bill is chosen with a usable key (R-Q-1).
     */
    val calls: CallSwitch by lazy {
        CallSwitch(
            gptLive = { engine },
            cascade = {
                CascadeCallEngine(
                    scope = mainScope,
                    settings = { settings.settings.value },
                    runtime = { VoiceShell.of(app).shell.appRuntime() },
                    speech = CallSpeechFactory.sherpa(app.assets, modelManager),
                    audioFactory = { BuildHooks.cascadeAudio ?: CascadeAudioFactory.Device },
                    audio = AndroidCallAudio(app),
                    effects = cascadeEffects,
                    offCall = { running -> OffCall.afterHangUp(app, running) },
                )
            },
            cascadeActive = { VoiceShell.of(app).gate.active },
            modelsReady = { CallSpeechFactory.ready(modelManager, settings.settings.value) },
            grok = {
                val shell = VoiceShell.of(app)
                val network = AndroidNetwork(app)
                GrokCallEngine(
                    scope = mainScope,
                    settings = { settings.settings.value },
                    runtime = { shell.shell.appRuntime() },
                    key = { shell.xaiKeys?.key() },
                    client = client,
                    audioFactory = { GrokAudioFactory.Device },
                    audio = AndroidCallAudio(app),
                    effects = cascadeEffects,
                    offCall = { running -> OffCall.afterHangUp(app, running) },
                    network = object : GrokNetwork {
                        override fun online(): Boolean = network.isOnline()
                        override val changes = network.online
                    },
                    billMemo = { shell.gate.billAvailable && CallSpeechFactory.ready(modelManager, settings.settings.value) },
                    onLive = { cascadeApp.grokLive = it },
                )
            },
            grokActive = { VoiceShell.of(app).gate.grokActive },
            billAvailable = { VoiceShell.of(app).gate.billAvailable },
        )
    }

    /** GPT-Live (today's call, unchanged). Use [calls]; this is its engine. */
    val engine: CallEngine by lazy {
        CallEngine(
            scope = mainScope,
            settings = { settings.settings.value },
            links = links,
            transports = transports,
            audio = AndroidCallAudio(app),
            effects = AndroidCallEffects(app),
            memory = settings,
        )
    }
}

val Context.graph: LiveGraph get() = (applicationContext as LiveApp).graph
