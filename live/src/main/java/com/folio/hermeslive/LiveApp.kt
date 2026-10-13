package com.folio.hermeslive

import android.app.Application
import android.content.Context
import com.folio.hermes.telemetry.Telemetry
import com.folio.hermeslive.audio.AndroidCallAudio
import com.folio.hermeslive.audio.AndroidCallEffects
import com.folio.hermeslive.call.CallEngine
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

class LiveApp : Application() {
    /** Built on first use, so the connection provider (created before onCreate) can reach it too. */
    val graph: LiveGraph by lazy { LiveGraph(this) }

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
