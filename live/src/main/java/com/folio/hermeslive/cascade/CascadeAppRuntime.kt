package com.folio.hermeslive.cascade

import com.folio.hermes.ApprovalChoice
import com.folio.hermes.ApprovalRequest
import com.folio.hermes.ClarifyRequest
import com.folio.hermes.GatewayEvent
import com.folio.hermes.HermesGateway
import com.folio.hermes.cascade.CascadeCore
import com.folio.hermes.cascade.CascadeSettings
import com.folio.hermes.cascade.Effect
import com.folio.hermes.cascade.Notices
import com.folio.hermes.cascade.TaskState
import com.folio.hermes.cascade.ports.AudioOut
import com.folio.hermes.cascade.ports.OpenRouterPort
import com.folio.hermes.cascade.ports.SpeechToText
import com.folio.hermes.openrouter.OpenRouterResult
import com.folio.hermeslive.call.ApprovalCard
import com.folio.hermeslive.call.ClarifyCard
import com.folio.hermeslive.data.CascadeRuntime
import com.folio.hermeslive.service.Briefing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The Hermes gateway as the runtime uses it: the connection, server requests, and the answers to them. */
interface GatewayControl {
    fun connect()
    fun disconnect()

    /** Approval, clarify and cancel requests from the task sessions on this gateway. */
    val events: Flow<GatewayEvent>

    suspend fun answerApproval(request: ApprovalRequest, choice: ApprovalChoice)
    suspend fun answerClarify(request: ClarifyRequest, answers: Map<String, String?>)

    companion object {
        fun of(gateway: HermesGateway): GatewayControl = object : GatewayControl {
            override fun connect() = gateway.connect()
            override fun disconnect() = gateway.disconnect()
            override val events: Flow<GatewayEvent> = gateway.events
            override suspend fun answerApproval(request: ApprovalRequest, choice: ApprovalChoice) = gateway.answerApproval(request, choice)
            override suspend fun answerClarify(request: ClarifyRequest, answers: Map<String, String?>) = gateway.answerClarify(request, answers)
        }

        /** No gateway (signed out): nothing connects; tasks fail before any request. */
        val None: GatewayControl = object : GatewayControl {
            override fun connect() = Unit
            override fun disconnect() = Unit
            override val events: Flow<GatewayEvent> = emptyFlow()
            override suspend fun answerApproval(request: ApprovalRequest, choice: ApprovalChoice) = Unit
            override suspend fun answerClarify(request: ClarifyRequest, answers: Map<String, String?>) = Unit
        }
    }
}

/**
 * The running cascade for this process, while the gate is open (S-E's [CascadeRuntime] handle;
 * built by [CascadeApp.factory]). It holds the one [CascadeCore] (actor, durable store, task
 * recovery), the Hermes gateway its task sessions use, and the call-time slots ([stt], [audio])
 * a call plugs the mic and speaker into. Tasks belong to the user: they outlive calls, and the
 * off-call service keeps the gateway open for them ([holdGateway], R-CONV-3).
 *
 * Approval and clarify requests are tap only (R-TASK-6): they arrive on this gateway (which only
 * carries cascade task sessions) and are shown as cards ([approvals], [clarify]), never answered
 * by themselves.
 */
class CascadeAppRuntime(
    val core: CascadeCore,
    val stt: SttSlot,
    val audio: AudioSlot,
    private val gateway: GatewayControl,
    private val openRouter: OpenRouterPort,
    /** The cascade's settings now (read per call). */
    val settings: () -> CascadeSettings,
    /** Voice tasks running, shared with the off-call hooks (the off-call service watches it). */
    private val running: MutableStateFlow<Int>,
    private val scope: CoroutineScope,
    /** The user's vocabulary plus learned corrections (`VocabularyStore.hotwords`). */
    private val hotwordsOf: (List<String>) -> List<String> = { it },
) : CascadeRuntime {
    private val _approvals = MutableStateFlow<List<ApprovalCard>>(emptyList())
    private val _clarify = MutableStateFlow<ClarifyCard?>(null)
    private val lock = Any()
    private var holds = 0
    private var inCall = false
    private var closed = false

    val approvals: StateFlow<List<ApprovalCard>> = _approvals.asStateFlow()
    val clarify: StateFlow<ClarifyCard?> = _clarify.asStateFlow()
    val tasksRunning: StateFlow<Int> = running.asStateFlow()

    init {
        scope.launch {
            core.actor.state.collect { s ->
                running.value = s.tasks.runningCount
                if (s.tasks.runningCount == 0) releaseIfIdle()
            }
        }
        scope.launch {
            gateway.events.collect { e ->
                when (e) {
                    is GatewayEvent.ApprovalRequested -> _approvals.update { list -> if (list.any { it.id == e.request.id }) list else list + ApprovalCard(e.request) }
                    is GatewayEvent.ClarifyRequested -> _clarify.value = ClarifyCard(e.request)
                    is GatewayEvent.RequestCancelled -> {
                        _approvals.update { list -> list.filterNot { it.id == e.id } }
                        _clarify.update { c -> c?.takeUnless { it.id == e.id } }
                    }
                    else -> Unit
                }
            }
        }
    }

    /** A call starts: the mic and speaker are plugged in, the gateway connects, the actor listens. */
    fun startCall(speech: SpeechToText, out: AudioOut) {
        synchronized(lock) { inCall = true }
        stt.attach(speech)
        audio.attach(out)
        gateway.connect()
        core.start()
    }

    /** The call ends: Bill stops, the mic and speaker are unplugged. Tasks keep running. */
    fun endCall() {
        core.stop()
        stt.detach()
        audio.detach()
        synchronized(lock) { inCall = false }
        releaseIfIdle()
    }

    /** The off-call service: read the stored state back (tasks recover and finish) and keep the gateway open. */
    fun holdGateway(): AutoCloseable {
        synchronized(lock) { holds++ }
        core.restore()
        gateway.connect()
        var open = true
        return AutoCloseable {
            synchronized(lock) {
                if (!open) return@AutoCloseable
                open = false
                holds--
            }
            releaseIfIdle()
        }
    }

    /** "Hear it": Bill reads [taskId]'s result (label and first sentence), or the newest unheard one when null. */
    suspend fun briefing(taskId: String?): Briefing? {
        val tasks = core.actor.state.value.tasks.tasks
        val task = taskId?.let { id -> tasks.firstOrNull { it.id == id } }
            ?: tasks.filter { it.unread && !it.result.isNullOrBlank() }.maxByOrNull { it.startedAt }
            ?: return null
        val info = core.notifyInfo(task.id) ?: return null
        val text = listOf(info.label.trim().trimEnd('.') + ".", info.headline.ifBlank { Notices.headline(task.result.orEmpty()) }).filter { it.length > 1 }.joinToString(" ")
        val clip = (openRouter.clip(text) as? OpenRouterResult.Ok)?.value ?: return null
        return Briefing(listOf(clip))
    }

    suspend fun answerApproval(id: String, choice: ApprovalChoice): Boolean {
        val card = _approvals.value.firstOrNull { it.id == id } ?: return false
        if (choice !in card.choices && choice != ApprovalChoice.Deny) return false
        _approvals.update { list -> list.filterNot { it.id == id } }
        return runCatching { gateway.answerApproval(card.request, choice) }.isSuccess
    }

    suspend fun answerClarify(id: String, answers: Map<String, String?>): Boolean {
        val card = _clarify.value?.takeIf { it.id == id } ?: return false
        _clarify.value = null
        return runCatching { gateway.answerClarify(card.request, answers) }.isSuccess
    }

    /** The user's [vocabulary] and the learned corrections (R-STT-7). */
    fun hotwords(vocabulary: List<String>): List<String> = runCatching { hotwordsOf(vocabulary) }.getOrDefault(vocabulary)

    /** Hotword material besides the user's vocabulary: the labels of tasks still worth naming (R-STT-7). */
    fun taskLabels(): List<String> = core.actor.state.value.tasks.tasks
        .filter { it.state == TaskState.Running || it.unread }
        .map { it.label }

    private fun releaseIfIdle() {
        val idle = synchronized(lock) { !inCall && holds == 0 && !closed }
        if (idle && running.value == 0) gateway.disconnect()
    }

    override fun close() {
        synchronized(lock) { closed = true }
        runCatching { core.stop() }
        stt.detach()
        audio.detach()
        gateway.disconnect()
        running.value = 0
        _approvals.value = emptyList()
        _clarify.value = null
        scope.cancel()
    }

    companion object {
        /** The app effects of one runtime: notifications, learned words, haptics. */
        fun appEffects(
            core: () -> CascadeCore?,
            notify: (taskId: String, label: String, headline: String) -> Unit,
            learn: (heard: String, meant: String) -> Unit,
            haptic: (kind: String) -> Unit,
        ): (Effect) -> Unit = { effect ->
            when (effect) {
                // Spec v2 §14 item 12: the label and headline come from the task state.
                is Effect.Notify -> effect.taskId?.let { id -> core()?.notifyInfo(id, effect.kind)?.let { notify(id, it.label, it.headline) } }
                is Effect.LearnWord -> learn(effect.heard, effect.chosen) // §14 item 13
                is Effect.Haptic -> haptic(effect.kind)
                else -> Unit
            }
        }
    }
}
