package com.folio.hermes.cascade

import com.folio.hermes.cascade.ports.AudioOut
import com.folio.hermes.cascade.ports.Clock
import com.folio.hermes.cascade.ports.HermesPort
import com.folio.hermes.cascade.ports.MemoryStore
import com.folio.hermes.cascade.ports.Network
import com.folio.hermes.cascade.ports.OpenRouterPort
import com.folio.hermes.cascade.ports.SpeechToText
import com.folio.hermes.cascade.ports.SttEvent
import com.folio.hermes.cascade.ports.Store
import com.folio.hermes.openrouter.FailureKind
import com.folio.hermes.openrouter.OpenRouterException
import com.folio.hermes.telemetry.TelemetrySink
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Lets a test stop the actor between any two effects (R-DUR-5). Return false to kill the actor before [effect] runs. */
fun interface EffectGate {
    fun allow(effect: Effect): Boolean

    companion object {
        val Open = EffectGate { true }
    }
}

/**
 * The cascade's single state owner (R-ARC-1). One coroutine takes [Event]s from one channel,
 * runs the pure [CascadeReducer] and executes the resulting [Effect]s; executors post their
 * results back as events. No other class mutates call state, so no locks.
 *
 * Off state: nothing runs until [start]; `:live` builds it only when the voice engine is
 * `cascade` and a key is saved (R-Q-1). Telemetry carries event names, counts, durations and
 * stock slot ids only, never words.
 *
 * Inputs keep C1's `Cascade` shape ([speechStarted], [partial], [final], [userSpeech],
 * [network], [say]…) and are safe from any thread. Outputs: [ui], [events], [speaking].
 */
class CascadeActor(
    scope: CoroutineScope,
    private val env: CascadeEnv,
    private val settings: () -> CascadeSettings,
    private val openRouter: OpenRouterPort,
    private val hermes: HermesPort,
    audio: AudioOut,
    private val clock: Clock = Clock.System,
    private val store: Store = MemoryStore(),
    private val rotations: RotationStore = MemoryRotationStore(),
    private val decisions: DecisionLog? = null,
    private val telemetry: TelemetrySink = TelemetrySink.Default,
    private val stt: SpeechToText? = null,
    private val net: Network? = null,
    private val onEndCall: (tasksRunning: Boolean) -> Unit = {},
    /** [Effect.Notify] and [Effect.Haptic], for the app. */
    private val onAppEffect: (Effect) -> Unit = {},
    private val gate: EffectGate = EffectGate.Open,
    seed: Long = Random.nextLong(),
) {
    private val reducer = CascadeReducer(env)
    private val actorJob = SupervisorJob(scope.coroutineContext[Job])
    private val actorScope = CoroutineScope(scope.coroutineContext + actorJob)
    private val workJob = SupervisorJob(actorJob)
    private val work = CoroutineScope(actorScope.coroutineContext + workJob)
    private val channel = Channel<Event>(Channel.UNLIMITED)
    private val bus = CascadeEventBus()
    private val tasks = TaskRunner(work, hermes, ::post)
    private val player = SpeechPlayer(work, openRouter, audio, clock, ::post)
    private val jobs = HashMap<String, Job>()
    private val _state = MutableStateFlow(
        State(
            settings = settings(),
            rotation = RotationState(env.bank.slots.mapNotNull { s -> rotations.load(s.id)?.let { s.id to it } }.toMap(), seed),
        ),
    )
    private val _ui = MutableStateFlow(UiProjection.project(_state.value))
    private var collectors: Job? = null
    private var hermesCollectors: Job? = null

    @Volatile
    var killed: Boolean = false
        private set

    /** The whole state (for the app's wiring and tests). */
    val state: StateFlow<State> = _state.asStateFlow()

    /** What the call screen shows. */
    val ui: StateFlow<CascadeUiState> = _ui.asStateFlow()

    /** One-shot moments for animations. */
    val events: SharedFlow<CascadeEvent> = bus.events

    /** True while Bill has something queued or playing (wait for this before closing the call). */
    val speaking: Boolean get() = _state.value.speaking

    init {
        actorScope.launch {
            for (event in channel) {
                step(event)
                if (killed) break
            }
        }
    }

    // ── inputs ──────────────────────────────────────────────────────────────────────────────

    /**
     * Start the call: the loop restores what the persistence seam saved (on the loop, before any
     * other event), then the ports are listened to. Hermes task sessions are listened to from the
     * first start until the actor is gone: tasks keep running between calls (R-CONV-3).
     */
    fun start() {
        post(Event.Start())
        listenToHermes()
        collectors?.cancel()
        collectors = actorScope.launch {
            net?.let { n -> launch { n.online.collect { post(Event.Network(it)) } } }
            stt?.let { st -> launch { st.events.collect { post(it.toEvent()) } } }
        }
    }

    /**
     * Read the stored state back with no call (R-CONV-3): the off-call service calls this after
     * process death so unfinished tasks are recovered (with the one `TaskRecovery` run) and their
     * results recorded and notified. Nothing is spoken; the mic stays off. Idempotent, and a later
     * [start] continues from the same state.
     */
    fun restore() {
        post(Event.Restore())
        listenToHermes()
    }

    @Synchronized
    private fun listenToHermes() {
        if (hermesCollectors != null) return
        hermesCollectors = actorScope.launch {
            launch { hermes.sessions.collect { (task, stored) -> post(Event.TaskSessionBound(task, stored)) } }
            launch { hermes.connected.collect { post(Event.HermesConnection(it)) } }
        }
    }

    /** End the call: Bill stops, the call's work is cancelled; tasks keep running. */
    fun stop() = post(Event.Stop)

    fun speechStarted() = post(Event.SpeechStarted)
    fun speechEnded() = post(Event.SpeechEnded)
    fun partial(text: String) = post(Event.Partial(text))
    fun final(text: String) = post(Event.Final(text))

    /** VAD while Bill plays: the user has spoken for [continuousMs]. At 250 ms or more Bill stops. */
    fun userSpeech(continuousMs: Long) = post(Event.UserSpeech(continuousMs))

    /** The player's output level, 0..1. */
    fun outputLevel(level: Float) = post(Event.OutputLevel(level))
    fun hermesConnection(up: Boolean) = post(Event.HermesConnection(up))

    /** The phone's network came or went. Back online sends the outbox (each held turn once). */
    fun network(online: Boolean) = post(Event.Network(online))
    fun renderProgress(progress: RenderProgress) = post(Event.RenderProgressed(progress))

    /** Play a slot the app picks itself (call start, approvals, warnings…). */
    fun say(slotId: String, kind: SpeechKind = SpeechKind.Notice) = post(Event.Say(slotId, kind))

    /** Any other input (taps, resume choice, hold-to-talk). */
    fun send(event: Event) = post(event)

    /**
     * Open past conversation [conversationId] from the history list (`ConversationHistory.list`).
     * The stored state is read back first if no call or restore has yet (an off-call restore).
     */
    fun openConversation(conversationId: String) {
        restore()
        post(Event.OpenConversation(conversationId))
    }

    private fun post(event: Event) {
        channel.trySend(event)
    }

    // ── the loop ────────────────────────────────────────────────────────────────────────────

    private fun step(input: Event) {
        val before = _state.value.copy(nowMs = clock.nowMs(), wallMs = clock.wallMs(), settings = settings())
        val event = when {
            input is Event.Start && input.restored == null && !before.started -> Event.Start(readBack(before))
            input is Event.Restore && input.restored == null && !before.restored && !before.started -> Event.Restore(readBack(before))
            input is Event.OpenConversation && input.loaded == null -> input.copy(loaded = env.conversation.load(store, input.conversationId))
            else -> input
        }
        val (after, effects) = reducer.reduce(before, event)
        _state.value = after
        _ui.value = UiProjection.project(after)
        // R-DUR-1: durable writes first, then everything else, in order.
        val ordered = effects.filter { it.durable } + effects.filterNot { it.durable }
        for (effect in ordered) {
            if (!gate.allow(effect)) {
                kill()
                return
            }
            run(effect)
        }
        if (event is Event.Stop) {
            cancelWork()
            collectors?.cancel()
        }
    }

    /** The persistence seam's saved state, with the conversation seam's Resume candidate (R-CONV-2). */
    private fun readBack(fresh: State): State = env.persistence.restore(store, fresh).let { s ->
        s.copy(conversation = s.conversation.copy(resume = env.conversation.resumeCandidate(store, s)))
    }

    /** Stop dead (the kill test): nothing more runs, nothing is cleaned up. */
    private fun kill() {
        killed = true
        channel.close()
        actorJob.cancel()
    }

    /** The call's own work: timers, Jev, writer, plan, accurate pass and speech. Task runs are not the call's. */
    private fun cancelWork() {
        synchronized(jobs) { jobs.values.toList().also { jobs.clear() } }.forEach { it.cancel() }
        player.stop()
    }

    private fun run(effect: Effect) {
        when (effect) {
            is Effect.Persist -> store.put(effect.key, effect.value)
            is Effect.Append -> store.append(effect.log, effect.line)
            is Effect.StartTimer -> launch("timer:${effect.timer.name}:${effect.timer.seq}") {
                delay(effect.delayMs)
                post(Event.TimerFired(effect.timer))
            }
            is Effect.CancelTimer -> cancel("timer:${effect.timer.name}:${effect.timer.seq}")
            is Effect.CallJev -> launch("jev:${effect.seq}") {
                val result = openRouter.jev(effect.request.state, effect.request.questions, effect.timeoutMs)
                post(Event.JevAnswered(effect.seq, result, clock.nowMs()))
            }
            is Effect.CancelJev -> cancel("jev:${effect.seq}")
            is Effect.StreamWriter -> launch("writer:${effect.seq}") {
                val failure = try {
                    openRouter.sentences(effect.system, effect.prompt).collect { post(Event.WriterSentence(effect.seq, it)) }
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: OpenRouterException) {
                    e.failure.kind
                } catch (e: Exception) {
                    FailureKind.BadBody
                }
                post(Event.WriterFinished(effect.seq, failure))
            }
            is Effect.CancelWriter -> cancel("writer:${effect.seq}")
            is Effect.Write -> launch("plan:${effect.seq}") { post(Event.WriterWrote(effect.seq, openRouter.write(effect.system, effect.prompt))) }
            is Effect.FetchClip -> player.fetch(effect)
            is Effect.DropClips -> player.drop(effect)
            is Effect.Play -> player.play(effect.piece)
            Effect.StopPlayback -> player.stopPlayback()
            is Effect.SubmitTask -> tasks.submit(effect)
            is Effect.OpenTaskSession -> tasks.open(effect)
            is Effect.InterruptTask -> tasks.interrupt(effect.taskId)
            is Effect.CancelTaskRuns -> tasks.cancelRuns(effect.taskId)
            is Effect.ReadBusyMode -> tasks.busyMode(effect.seq)
            is Effect.AccuratePass -> launch("accurate:${effect.turnId}") {
                post(Event.AccurateFinal(effect.turnId, stt?.accurate(effect.turnId)))
            }
            is Effect.SaveRotation -> runCatching { rotations.save(effect.slotId, effect.rotation) }
            is Effect.LogDecision -> runCatching { decisions?.record(effect.record) }
            is Effect.Report -> runCatching { telemetry.event(effect.name, effect.attrs, warn = effect.warn) }
            is Effect.Emit -> bus.emit(effect.event)
            is Effect.EndCall -> onEndCall(effect.tasksRunning)
            is Effect.Notify, is Effect.Haptic, is Effect.LearnWord -> onAppEffect(effect)
        }
    }

    private fun launch(key: String, block: suspend CoroutineScope.() -> Unit) {
        val job = work.launch(block = block)
        val old = synchronized(jobs) { jobs.put(key, job) }
        old?.cancel()
        job.invokeOnCompletion { synchronized(jobs) { if (jobs[key] === job) jobs.remove(key) } }
    }

    private fun cancel(key: String) {
        synchronized(jobs) { jobs.remove(key) }?.cancel()
    }

    private fun SttEvent.toEvent(): Event = when (this) {
        SttEvent.SpeechStarted -> Event.SpeechStarted
        SttEvent.SpeechEnded -> Event.SpeechEnded
        is SttEvent.Partial -> Event.Partial(text)
        is SttEvent.Final -> Event.Final(text, words)
        is SttEvent.UserSpeech -> Event.UserSpeech(continuousMs)
        is SttEvent.HoldToTalk -> Event.HoldToTalk(pressed)
    }
}

/** Durable effects run before any other effect of the same step (R-DUR-1). */
val Effect.durable: Boolean get() = this is Effect.Persist || this is Effect.Append
