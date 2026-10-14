package com.folio.hermeslive.data

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.folio.hermes.cascade.BillVoice
import com.folio.hermes.cascade.DecisionLog
import com.folio.hermes.cascade.PhraseBank
import com.folio.hermes.cascade.PhraseRenderer
import com.folio.hermes.openrouter.OpenRouter
import com.folio.hermes.openrouter.OpenRouterConfig
import com.folio.hermes.telemetry.TelemetrySink
import com.folio.hermeslive.graph
import com.folio.hermeslive.service.OffCallHooks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

/**
 * What the app provides to the cascade shell. S-F makes `LiveApp` implement this; until then
 * every part is a no-op ([CascadeFactory.None], [SpeechModels.None], [OffCallHooks.None]).
 */
interface CascadeHost {
    val cascadeFactory: CascadeFactory
    val speechModels: SpeechModels
    val offCallHooks: OffCallHooks
}

/** Connectivity as the shell needs it: online for memo mode, metered for the Wi-Fi hint. */
interface NetworkHints {
    fun online(): Boolean
    fun metered(): Boolean

    /** On Wi-Fi (or Ethernet): what auto-prepare waits for (spec v2 §14 item 10). Not merely "unmetered". */
    fun wifi(): Boolean = online() && !metered()

    companion object {
        fun of(context: Context): NetworkHints = object : NetworkHints {
            private val cm get() = context.getSystemService(ConnectivityManager::class.java)
            override fun online(): Boolean {
                val caps = cm?.activeNetwork?.let { cm?.getNetworkCapabilities(it) } ?: return false
                return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
            override fun metered(): Boolean = cm?.isActiveNetworkMetered ?: true
            override fun wifi(): Boolean {
                val caps = cm?.activeNetwork?.let { cm?.getNetworkCapabilities(it) } ?: return false
                return online() && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
            }
        }
    }
}

/**
 * The cascade's app shell: settings gate, key, storage, vocabulary, decision log and "Prepare
 * Bill's voice". One per process ([of]). Building it builds no cascade: storage and the log
 * are lazy, and [shell] builds a runtime only while the gate is open (R-Q-1).
 *
 * S-F wires: [shell] (`runtime()` on call start, `release()` on engine switch), [storage]
 * (`store` for the actor, `ttsCache`, `rotations`), [decisionLog], [vocabulary] (`hotwords`,
 * `learn`), [cascadeSettings] and [offCallHooks].
 */
class VoiceShell(
    val context: Context,
    val settings: SettingsStore,
    val keys: OpenRouterKeyStore,
    private val client: OkHttpClient,
    private val host: CascadeHost?,
    val network: NetworkHints = NetworkHints.of(context),
    val telemetry: TelemetrySink = SafeTelemetry(),
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val openRouterConfig: OpenRouterConfig = OpenRouterConfig(),
    /** Every OpenRouter client the shell builds goes through here (tests count them). */
    private val openRouters: (key: () -> String?) -> OpenRouter = { key ->
        OpenRouter(openRouterConfig, key = key, client = client, telemetry = telemetry)
    },
    val dirs: CascadeDirs = CascadeDirs.of(context),
) {
    val storage: CascadeStorage by lazy { CascadeStorage(dirs, telemetry) }
    val gate = CascadeGate({ settings.settings.value }, { keys.status.value })
    val speechModels: SpeechModels get() = host?.speechModels ?: SpeechModels.None
    val offCallHooks: OffCallHooks get() = host?.offCallHooks ?: OffCallHooks.None

    /** The cascade's settings now (Settings › Voice mapped onto S0's [com.folio.hermes.cascade.CascadeSettings]). */
    fun cascadeSettings() = settings.settings.value.cascadeSettings()

    val shell = CascadeShell(gate, host?.cascadeFactory ?: CascadeFactory.None) {
        CascadeEnvironment(::cascadeSettings, keys::key, storage, speechModels, telemetry)
    }

    val decisionLog: DecisionLog by lazy { storage.decisionLog { settings.settings.value.decisionLog } }
    val vocabulary: VocabularyStore by lazy { VocabularyStore({ storage.store }) }
    val keyChecker: KeyChecker = KeyChecker.from(openRouters)

    private val bank: PhraseBank by lazy { PhraseBank.load() }

    private fun fingerprint(): String {
        val s = settings.settings.value
        return VoicePrep.fingerprint(s.billVoice, s.ttsModel, bank.version)
    }

    val prep: VoicePrep by lazy {
        VoicePrep(
            scope = scope,
            gate = gate,
            models = speechModels,
            highAccuracy = { settings.settings.value.highAccuracySpeech },
            bank = { bank },
            renders = {
                val s = cascadeSettings()
                val openRouter = openRouters(keys::key)
                val voice = BillVoice(openRouter.speech, storage.ttsCache(s.ttsCacheBytes), { s.voice }, { s.ttsModel })
                RenderJob(PhraseRenderer(voice, s.renderConcurrency, telemetry), fingerprint(), warm = { openRouter.warm() })
            },
            prepared = object : VoicePrep.PreparedMarker {
                override fun get(): String? = if (dirs.store.exists()) storage.store.get(PREPARED_KEY) else null
                override fun set(fingerprint: String) = storage.store.put(PREPARED_KEY, fingerprint)
            },
            currentFingerprint = ::fingerprint,
            telemetry = telemetry,
        )
    }

    @Volatile private var wasActive: Boolean? = null

    /**
     * Close the cascade when the gate shuts (engine switched back, key removed). When it opens
     * (Bill chosen with a usable key), prepare Bill's voice at once, on Wi-Fi only, unless it is
     * already prepared (spec v2 §14 item 10: the phrase render plus the model downloads).
     */
    fun onSettingsChanged() {
        val active = gate.active
        if (!active) shell.release()
        prep.refresh()
        val opened = active && wasActive != true
        wasActive = active
        if (opened) autoPrepare()
    }

    /** Start "Prepare Bill's voice" if it is needed and the phone is on Wi-Fi. True if it started. */
    fun autoPrepare(): Boolean {
        if (!gate.active || !network.wifi()) return false
        val s = prep.state.value
        val missing = speechModels.wanted(settings.settings.value.highAccuracySpeech).isNotEmpty()
        if (s.ready && !missing) return false
        return prep.start()
    }

    companion object {
        const val PREPARED_KEY = "cascade.shell.voice.prepared"

        // Holds the application context only.
        @SuppressLint("StaticFieldLeak")
        @Volatile private var instance: VoiceShell? = null

        /** The process's shell, on the app graph's settings and HTTP client. */
        fun of(context: Context): VoiceShell = instance ?: synchronized(this) {
            instance ?: run {
                val app = context.applicationContext
                val g = app.graph
                VoiceShell(
                    context = app,
                    settings = g.settings,
                    keys = OpenRouterKeyStore(
                        app.getSharedPreferences(OpenRouterKeyStore.PREFS, Context.MODE_PRIVATE),
                        KeystoreSecretBox(OpenRouterKeyStore.KEYSTORE_ALIAS),
                        SafeTelemetry(),
                    ),
                    client = g.client,
                    host = app as? CascadeHost,
                ).also { instance = it }
            }
        }
    }
}
