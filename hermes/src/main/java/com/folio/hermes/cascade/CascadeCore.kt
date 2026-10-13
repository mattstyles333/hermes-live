package com.folio.hermes.cascade

import com.folio.hermes.HermesConfig
import com.folio.hermes.HermesGateway
import com.folio.hermes.cascade.budget.CappedJevStateBudget
import com.folio.hermes.cascade.conversation.ConversationHistory
import com.folio.hermes.cascade.conversation.DurableConversations
import com.folio.hermes.cascade.conversation.PastConversation
import com.folio.hermes.cascade.conversation.ReconnectRouter
import com.folio.hermes.cascade.ports.AudioOut
import com.folio.hermes.cascade.ports.Clock
import com.folio.hermes.cascade.ports.GatewayHermesPort
import com.folio.hermes.cascade.ports.GatewaySessionRecovery
import com.folio.hermes.cascade.ports.HermesPort
import com.folio.hermes.cascade.ports.Network
import com.folio.hermes.cascade.ports.OpenRouterPort
import com.folio.hermes.cascade.ports.SpeechToText
import com.folio.hermes.cascade.ports.Store
import com.folio.hermes.cascade.registry.DurableTaskRegistry
import com.folio.hermes.cascade.registry.SessionRecovery
import com.folio.hermes.cascade.registry.TaskRecovery
import com.folio.hermes.cascade.routing.BackgroundSummaries
import com.folio.hermes.cascade.routing.CeoRouting
import com.folio.hermes.cascade.routing.MemoRouting
import com.folio.hermes.cascade.store.DurablePersistence
import com.folio.hermes.telemetry.TelemetrySink
import java.time.ZoneId
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * The ports one cascade needs (R-ARC-5). [hermes] and [recovery] usually come from one gateway
 * ([gateway]); [store] is the durable `Store` (S-E's `FileStore`), never a `MemoryStore` in the app.
 */
data class CascadePorts(
    val openRouter: OpenRouterPort,
    val hermes: HermesPort,
    val recovery: SessionRecovery,
    val audio: AudioOut,
    val store: Store,
    val clock: Clock = Clock.System,
    val network: Network? = null,
    val stt: SpeechToText? = null,
) {
    companion object {
        /** Hermes task sessions and their crash recovery on one [gateway] (`GatewayHermesPort` + `GatewaySessionRecovery`). */
        fun gateway(
            gateway: HermesGateway,
            config: HermesConfig,
            openRouter: OpenRouterPort,
            audio: AudioOut,
            store: Store,
            clock: Clock = Clock.System,
            network: Network? = null,
            stt: SpeechToText? = null,
        ): CascadePorts = CascadePorts(
            openRouter = openRouter,
            hermes = GatewayHermesPort(gateway, config),
            recovery = GatewaySessionRecovery(gateway, config),
            audio = audio,
            store = store,
            clock = clock,
            network = network,
            stt = stt,
        )
    }
}

/**
 * The one cascade `:live` builds (spec v2 §12 phase 2; S-F wires it). [create] puts together
 * - the env: CEO routing (`CeoRouting.registry`, R-CTX-2 background summaries on), the R-CTX-1
 *   cap, and the durable seams (`DurablePersistence`, `DurableTaskRegistry`,
 *   `DurableConversations` with offline dictation routed by the same `MemoRouting`);
 * - the [actor] on the given ports;
 * - one [TaskRecovery], launched once, on the first [start] or [restore], whichever comes first.
 *
 * Dark (R-Q-1): building it starts nothing: no request, no store write. The app builds it only
 * while its gate is open (engine `cascade` and a usable key).
 *
 * Name: `:live` already has `com.folio.hermeslive.data.CascadeRuntime` (the gate's handle), so
 * this is `CascadeCore`; the app's runtime holds one.
 */
class CascadeCore private constructor(
    val actor: CascadeActor,
    val env: CascadeEnv,
    private val ports: CascadePorts,
    private val scope: CoroutineScope,
) {
    private val recovery = TaskRecovery(ports.hermes, ports.recovery)
    private var recoveryJob: Job? = null

    /** A call starts (mic on): restore if needed, recover tasks once, listen to the ports. */
    fun start() {
        actor.start()
        recoverOnce()
    }

    /** The call ends: Bill stops; tasks keep running (R-CONV-3). */
    fun stop() = actor.stop()

    /**
     * No call, but the off-call service is up (after a hang-up, or after process death): read the
     * stored state back and recover unfinished tasks so they finish and notify. Nothing is spoken.
     */
    fun restore() {
        actor.restore()
        recoverOnce()
    }

    /** The label and headline for `Effect.Notify(kind, taskId)` (spec v2 §14 item 12). */
    fun notifyInfo(taskId: String, kind: String? = null): NotifyInfo? = notifyInfo(actor.state.value, taskId, kind)

    /** Past conversations for the history list, newest first (open one with `actor.openConversation(id)`). */
    fun history(): List<PastConversation> = ConversationHistory.list(ports.store, actor.state.value.takeIf { it.restored })

    @Synchronized
    private fun recoverOnce() {
        if (recoveryJob == null) recoveryJob = recovery.launch(scope, actor)
    }

    companion object {
        /** The cascade env: CEO routing on the durable seams, sharing one [memo] (the default `:live` env). */
        fun env(bank: PhraseBank, memo: MemoRouting = MemoRouting()): CascadeEnv = CascadeEnv(
            bank = bank,
            handlers = CeoRouting.registry(memo, BackgroundSummaries(enabled = true)),
            budget = CappedJevStateBudget(),
            persistence = DurablePersistence,
            registry = DurableTaskRegistry,
            conversation = DurableConversations(reconnect = ReconnectRouter { tx, _ -> memo.begin(tx) }),
        )

        /**
         * Build the cascade. [onAppEffect] gets `Effect.Notify` (map it with [notifyInfo]),
         * `Effect.Haptic` and `Effect.LearnWord` (→ `VocabularyStore.learn`); [onEndCall] ends the
         * call when Bill said goodbye. [zone] formats "dictated offline hh:mm".
         */
        fun create(
            scope: CoroutineScope,
            ports: CascadePorts,
            bank: PhraseBank,
            settings: () -> CascadeSettings,
            rotations: RotationStore = MemoryRotationStore(),
            decisions: DecisionLog? = null,
            telemetry: TelemetrySink = TelemetrySink.Default,
            onEndCall: (tasksRunning: Boolean) -> Unit = {},
            onAppEffect: (Effect) -> Unit = {},
            zone: ZoneId = ZoneId.systemDefault(),
            gate: EffectGate = EffectGate.Open,
            seed: Long = Random.nextLong(),
        ): CascadeCore {
            val env = env(bank, MemoRouting(zone))
            val actor = CascadeActor(
                scope = scope,
                env = env,
                settings = settings,
                openRouter = ports.openRouter,
                hermes = ports.hermes,
                audio = ports.audio,
                clock = ports.clock,
                store = ports.store,
                rotations = rotations,
                decisions = decisions,
                telemetry = telemetry,
                stt = ports.stt,
                net = ports.network,
                onEndCall = onEndCall,
                onAppEffect = onAppEffect,
                gate = gate,
                seed = seed,
            )
            return CascadeCore(actor, env, ports, scope)
        }
    }
}
