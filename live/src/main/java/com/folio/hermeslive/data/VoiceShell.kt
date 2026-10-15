package com.folio.hermeslive.data

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.folio.hermes.AccessTokenProvider
import com.folio.hermes.HermesConfig
import com.folio.hermes.cascade.BillVoice
import com.folio.hermes.live.AudioDecoder
import com.folio.hermes.live.HermesSpeechSource
import com.folio.hermes.live.HermesVoiceState
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
import kotlinx.coroutines.launch
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
    /** The xAI key for Grok Voice (null: none, so Grok stays off). */
    val xaiKeys: OpenRouterKeyStore? = null,
    xaiChecker: KeyChecker? = null,
    /** Signed in to Hermes (GPT-Live's parallel agents need it before the task machinery opens). */
    private val signedIn: () -> Boolean = { false },
    /** The signed-in Hermes server and its tokens (Hermes voice slice), or null. */
    hermesAccount: () -> Pair<HermesConfig, AccessTokenProvider>? = { null },
    /** Decodes the relay's MP3/Ogg clips (`MediaCodecDecoder` in the app). */
    audioDecoder: () -> AudioDecoder = { AudioDecoder.None },
    /** Tests widen it to their fake provider. */
    hermesReachable: ((String) -> Boolean)? = null,
) {
    /** Checks an xAI key (`GET /v1/api-key`); tests pass a fake. */
    val xaiChecker: KeyChecker = xaiChecker ?: XaiKeyChecker(client, telemetry = telemetry)

    val storage: CascadeStorage by lazy { CascadeStorage(dirs, telemetry) }

    /**
     * Hermes's voice for Custom (Hermes voice slice). Off state: built and fetched only while
     * signed in **and** Custom is the engine or cloud speech recognition is on (meeting mode).
     */
    val hermesVoices: HermesVoices = HermesVoices(
        account = hermesAccount,
        client = client,
        decoder = audioDecoder,
        telemetry = telemetry,
        wanted = { hermesVoiceWanted(settings.settings.value) },
        scope = scope,
        reachable = hermesReachable ?: com.folio.hermes.live.HermesVoiceConfig::phoneReachable,
    )

    /**
     * Hermes's voice may be asked for: signed in, and Custom with "Voice from" Hermes, or cloud
     * speech recognition on (calls or meeting mode) with "Cloud speech recognition from" Hermes.
     * Otherwise nothing is fetched (a GPT-Live, Grok or OpenRouter-only user never holds its keys).
     */
    fun hermesVoiceWanted(s: LiveSettings): Boolean = signedIn() &&
        ((s.voiceEngine == VoiceEngine.Cascade && s.voiceFrom.prefersHermes) || (s.speechRecognition == SpeechRecognition.Cloud && s.sttFrom.prefersHermes))

    /** Signed in to Hermes now. */
    val isSignedIn: Boolean get() = signedIn()

    /** Where Custom's voice would come from now, without a request ([VoiceRoute.None]: no voice at all). */
    fun voiceRoute(): VoiceRoute {
        val s = settings.settings.value
        return VoiceRouting.tts(s.voiceFrom, if (s.voiceFrom.prefersHermes) hermesVoices.cached() else null, keys.status.value.usable)
    }

    /** Bill's voice routed between Hermes and OpenRouter on [speech] (OpenRouter's TTS client). */
    fun routedVoice(speech: com.folio.hermes.openrouter.SpeechClient, live: () -> LiveSettings = { settings.settings.value }): RoutedVoice =
        RoutedVoice(live, hermesVoices, RoutedVoice.openRouterSource(speech, live), openRouterUsable = { keys.status.value.usable })
    val gate = CascadeGate(
        { settings.settings.value }, { keys.status.value }, { xaiKeys?.status?.value ?: KeyStatus() },
        gptLiveAgents = { signedIn() && GptLivePrefs.of(context).agents.value },
    )
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

    /**
     * What the stock lines should be rendered as: OpenRouter's voice and model (byte-identical to
     * before the Hermes voice slice), or, with Hermes preferred and its TTS known, Hermes's
     * identity. Hermes preferred but not known yet (not fetched, or unreachable for now): null, so
     * an offline start never re-renders the bank through OpenRouter and back.
     */
    private fun fingerprintOrNull(): String? {
        val s = settings.settings.value
        val openRouter = VoicePrep.fingerprint(s.billVoice, s.ttsModel, bank.version)
        if (!s.voiceFrom.prefersHermes) return openRouter
        val v = hermesVoices.voice() ?: return openRouter
        val cfg = v.cached()
        if (cfg != null) {
            if (!cfg.tts.available) return openRouter
            val id = v.ttsIdentity(cfg, s.hermesVoiceFor(VoiceRouting.hermesTtsProvider(cfg)))
            return VoicePrep.fingerprint(id.voice, id.model, bank.version)
        }
        return when (val st = v.state.value) {
            // The server has no voice route, or refuses us: OpenRouter is the voice, not a blip.
            is HermesVoiceState.Unavailable -> if (st.kind in PERMANENT) openRouter else null
            else -> null
        }
    }

    private fun preparedMarker(): String? = if (dirs.store.exists()) runCatching { storage.store.get(PREPARED_KEY) }.getOrNull() else null

    private fun fingerprint(): String = fingerprintOrNull() ?: preparedMarker() ?: PENDING_FINGERPRINT

    val prep: VoicePrep by lazy {
        VoicePrep(
            scope = scope,
            gate = gate,
            models = speechModels,
            wanted = { wantedModels() },
            bank = { bank },
            renders = {
                val s = cascadeSettings()
                val live = settings.settings.value
                val openRouter = openRouters(keys::key)
                val voice = routedVoice(openRouter.speech) { live }.billVoice(storage.ttsCache(s.ttsCacheBytes))
                renderingFor = fingerprint()
                RenderJob(
                    PhraseRenderer(voice, s.renderConcurrency, telemetry),
                    { fingerprint().also { renderingFor = it } },
                    warm = {
                        openRouter.warm()
                        hermesVoices.refresh(force = false)
                    },
                )
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

    /** The voices each engine offers (Settings › Voice pickers; ondemand slice). */
    val catalog: VoiceCatalog by lazy { VoiceCatalog(client, java.io.File(context.cacheDir, "voices"), telemetry = telemetry) }

    /** "Preview" in the voice pickers: one cached sample line through the engine's TTS. */
    val preview: VoicePreview by lazy {
        val player = com.folio.hermeslive.service.TrackBriefingPlayer()
        VoicePreview(
            cache = { storage.ttsCache(cascadeSettings().ttsCacheBytes) },
            openRouterClip = { model, voice, text ->
                if (!keys.status.value.usable) {
                    null
                } else {
                    val v = BillVoice(openRouters(keys::key).speech, storage.ttsCache(cascadeSettings().ttsCacheBytes), { voice }, { model })
                    (v.clip(text) as? com.folio.hermes.openrouter.OpenRouterResult.Ok)?.value
                }
            },
            xaiKey = { xaiKeys?.takeIf { it.status.value.usable }?.key() },
            xai = XaiTts(client, telemetry = telemetry),
            play = { player.play(it) },
            hermesClip = { voice ->
                hermesVoices.voice()?.let { hv ->
                    BillVoice(storage.ttsCache(cascadeSettings().ttsCacheBytes), { HermesSpeechSource(hv) { voice } })
                        .clip(VoicePreview.LINE).valueOrNull()
                }
            },
        )
    }

    /** An OpenRouter key is saved and not refused (the Custom voice preview needs it). */
    val canPreviewCustom: Boolean get() = keys.status.value.usable

    /** The speech-model ids the current settings want on the phone. */
    fun wantedModels(): Set<String> = SpeechModelChoice.wanted(settings.settings.value).map { it.id }.toSet()

    /**
     * The first time the picker exists here: an install with speech models keeps them (and is
     * told once about better ones), a new one gets the defaults ([SpeechModelChoice.firstRun]).
     */
    fun settleSpeechModels() {
        val s = settings.settings.value
        if (s.liveModel != null && s.accurateModel != null) return
        val next = SpeechModelChoice.firstRun(s) { speechModels.installed(it.id) }
        if (next != s) settings.update { next }
    }

    /**
     * Close the cascade when the gate shuts (engine switched back, key removed). When it opens
     * (Bill chosen with a usable key), prepare Bill's voice at once, on Wi-Fi only, unless it is
     * already prepared (spec v2 §14 item 10: the phrase render plus the model downloads).
     */
    fun onSettingsChanged() {
        val active = gate.active
        if (!gate.tasksActive) shell.release()
        prep.refresh()
        val opened = active && wasActive != true
        wasActive = active
        if (opened && autoPrepare()) return
        // The stock lines were rendered (or are being rendered) for another voice or model: render
        // them in this one (also on mobile data, as long as no speech models are missing). Decided
        // from the stored marker, so it holds after a restart too.
        if (active && voiceChanged()) rerender()
    }

    init {
        // Hermes's voice became known or changed (provider, model, voice): re-render the stock lines in it.
        scope.launch {
            hermesVoices.state.collect { st ->
                if (st is HermesVoiceState.Unknown) return@collect
                prep.refresh()
                if (gate.active && voiceChanged()) rerender()
            }
        }
    }

    /** The voice, model and bank version the running render is for (null: none running). */
    @Volatile private var renderingFor: String? = null

    /** The lines were prepared, or are being rendered, for a different voice, model or bank version than now. */
    private fun voiceChanged(): Boolean {
        val now = fingerprintOrNull() ?: return false
        if (prep.state.value.phase == PrepPhase.Running) return renderingFor != null && renderingFor != now
        val marker = if (dirs.store.exists()) runCatching { storage.store.get(PREPARED_KEY) }.getOrNull() else null
        return marker != null && marker != now
    }

    /**
     * The voice or TTS model changed: render the stock lines again in it (only the missing ones;
     * the old voice's stay cached), with progress in [prep]. Needs Custom on with a usable key;
     * when speech models are also missing, only on Wi-Fi.
     */
    fun rerender(): Boolean {
        if (!gate.active) return false
        if (speechModels.wanted(wantedModels()).isNotEmpty() && !network.wifi()) return false
        prep.cancel()
        return prep.start()
    }

    /** Start "Prepare Bill's voice" if it is needed and the phone is on Wi-Fi. True if it started. */
    fun autoPrepare(): Boolean {
        if (!gate.active || !network.wifi()) return false
        val s = prep.state.value
        val missing = speechModels.wanted(wantedModels()).isNotEmpty()
        if (s.ready && !missing) return false
        return prep.start()
    }

    companion object {
        const val PREPARED_KEY = "cascade.shell.voice.prepared"
        private const val PENDING_FINGERPRINT = "pending"

        /** `HermesVoiceState.Unavailable` kinds that mean "not this server" rather than "not right now". */
        private val PERMANENT = setOf("none", "refused", "no_key", "bad_body")

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
                    xaiKeys = XaiKeys.store(app),
                    signedIn = { g.account.config() != null && g.account.currentAuth() != null },
                    hermesAccount = { g.account.config()?.let { c -> g.account.currentAuth()?.let { a -> c to a } } },
                    audioDecoder = { com.folio.hermeslive.audio.MediaCodecDecoder() },
                ).also {
                    instance = it
                    it.settleSpeechModels()
                }
            }
        }
    }
}
