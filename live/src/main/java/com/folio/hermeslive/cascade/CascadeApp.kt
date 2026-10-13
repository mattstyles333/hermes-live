package com.folio.hermeslive.cascade

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.folio.hermes.HermesConfig
import com.folio.hermes.HermesGateway
import com.folio.hermes.cascade.BillVoice
import com.folio.hermes.cascade.CascadeCore
import com.folio.hermes.cascade.CascadePorts
import com.folio.hermes.cascade.PhraseBank
import com.folio.hermes.cascade.ports.Clock
import com.folio.hermes.cascade.ports.LiveOpenRouter
import com.folio.hermes.openrouter.OpenRouter
import com.folio.hermes.openrouter.OpenRouterConfig
import com.folio.hermes.AccessTokenProvider
import com.folio.hermeslive.data.CascadeEnvironment
import com.folio.hermeslive.data.CascadeFactory
import com.folio.hermeslive.data.CascadeRuntime
import com.folio.hermeslive.data.VoiceShell
import com.folio.hermeslive.service.Briefing
import com.folio.hermeslive.service.OffCallHooks
import com.folio.hermeslive.service.ResultNotifications
import com.folio.hermeslive.service.TaskResultNotice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient

/**
 * Builds the app's cascade (spec v2 §12 phase 2): the [CascadeFactory] S-E's gate calls, and the
 * [OffCallHooks] the off-call and "Hear it" services use. Nothing here runs unless the gate is
 * open (engine `cascade` and a usable key; R-Q-1): the shell calls [factory] only then.
 */
class CascadeApp(
    private val context: Context,
    private val client: OkHttpClient,
    /** The signed-in Hermes server and its tokens, or null when signed out. */
    private val hermes: () -> Pair<HermesConfig, AccessTokenProvider>?,
    private val onEndCall: () -> Unit,
    private val onHaptic: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val running = MutableStateFlow(0)

    /** Voice tasks running (0 until a runtime exists). */
    val tasksRunning: StateFlow<Int> = running.asStateFlow()

    val factory = CascadeFactory { env -> build(env) }

    /**
     * One runtime: OpenRouter (Jev, writer, Bill's voice through the TTS cache), the Hermes gateway
     * for task sessions, the durable store, the decision log, and the actor with CEO routing on
     * the durable seams ([CascadeCore.create]). Signed out: [CascadeRuntime.Empty] (a call says
     * "sign in"); the shell is released at sign-in and sign-out, so the next call builds afresh.
     */
    private fun build(env: CascadeEnvironment): CascadeRuntime {
        val (config, tokens) = hermes() ?: return CascadeRuntime.Empty
        val shell = VoiceShell.of(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val settings = { env.settings().copy(accuratePass = true) }
        val gateway = HermesGateway(config, tokens, client, scope)
        val openRouterClient = OpenRouter(OpenRouterConfig(), key = env.key, client = client, telemetry = env.telemetry)
        val voice = BillVoice(openRouterClient.speech, env.storage.ttsCache(settings().ttsCacheBytes), { settings().voice }, { settings().ttsModel })
        val openRouter = LiveOpenRouter(openRouterClient, voice)
        val stt = SttSlot()
        val audio = AudioSlot()
        val ports = CascadePorts.gateway(gateway, config, openRouter, audio, env.storage.store, Clock.System, AndroidNetwork(context), stt)
        var core: CascadeCore? = null
        core = CascadeCore.create(
            scope = scope,
            ports = ports,
            bank = PhraseBank.load(),
            settings = settings,
            rotations = env.storage.rotations,
            decisions = shell.decisionLog,
            telemetry = env.telemetry,
            onEndCall = { main.post(onEndCall) },
            onAppEffect = CascadeAppRuntime.appEffects(
                core = { core },
                notify = { id, label, headline -> ResultNotifications.post(context, TaskResultNotice(id, label, headline)) },
                learn = { heard, meant -> runCatching { shell.vocabulary.learn(heard, meant) } },
                haptic = { main.post(onHaptic) },
            ),
        )
        return CascadeAppRuntime(
            core = core,
            stt = stt,
            audio = audio,
            gateway = GatewayControl.of(gateway),
            openRouter = openRouter,
            settings = settings,
            running = running,
            scope = scope,
            hotwordsOf = { vocabulary -> shell.vocabulary.hotwords(vocabulary) },
        )
    }

    /**
     * The off-call hooks (R-CONV-3). [OffCallHooks.holdGateway] builds the runtime if the process
     * was restarted (the gate must still be open), reads the stored tasks back and keeps the
     * gateway connected; "Hear it" reads a result in Bill's voice.
     */
    fun offCallHooks(runtime: () -> CascadeAppRuntime?, ready: suspend () -> Unit): OffCallHooks = object : OffCallHooks {
        override val tasksRunning: StateFlow<Int> = this@CascadeApp.tasksRunning

        /** After process death the account is read back first (no network, bounded), else the runtime can't be built. */
        override fun holdGateway(): AutoCloseable {
            runBlocking(Dispatchers.IO) { withTimeoutOrNull(READY_WAIT_MS) { ready() } }
            return runtime()?.holdGateway() ?: AutoCloseable { }
        }

        override suspend fun briefing(taskId: String?): Briefing? {
            withTimeoutOrNull(READY_WAIT_MS) { ready() }
            return runtime()?.briefing(taskId)
        }
    }

    companion object {
        const val READY_WAIT_MS = 2_000L
    }
}

/**
 * The shell's runtime as Bill's, or null. A [CascadeRuntime.Empty] (built while signed out, or
 * before the account was read back) is released at once, so the next call builds a real one.
 */
fun com.folio.hermeslive.data.CascadeShell.appRuntime(): CascadeAppRuntime? {
    val r = runtime() ?: return null
    if (r is CascadeAppRuntime) return r
    release()
    return null
}
