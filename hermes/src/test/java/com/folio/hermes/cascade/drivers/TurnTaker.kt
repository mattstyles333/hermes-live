package com.folio.hermes.cascade

import com.folio.hermes.openrouter.JevAnswer
import com.folio.hermes.openrouter.OpenRouterResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Test driver: C1's `TurnTaker` API over the production [TurnRules] (the actor's turn-taking),
 * so `TurnTakerTest` keeps proving the same timings. Timers and Jev calls run here as they do in
 * `CascadeActor.run`: a delayed post, a call whose result comes back as an answer, cancel by key.
 * Single-threaded: use it from one thread (a `TestScope`).
 */
class TurnTaker(
    private val scope: CoroutineScope,
    private val settings: () -> CascadeSettings,
    private val context: (heard: String, pending: List<String>, silenceMs: Long) -> JevRequest,
    private val decide: suspend (JevRequest, timeoutMs: Long) -> OpenRouterResult<Map<String, JevAnswer>>,
    private val onEvent: (TurnEvent) -> Unit,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val onJevFired: (early: Boolean) -> Unit = {},
) {
    private var state = TurnState()
    private val timers = HashMap<Timer, Job>()
    private val calls = HashMap<Long, Job>()

    val heard: String get() = state.heard
    val pending: List<String> get() = state.pending
    val holding: Boolean get() = state.holding
    val deciding: Boolean get() = state.deciding

    fun speechStarted() = apply(TurnRules.speechStarted(state))
    fun partial(text: String) = apply(TurnRules.partial(state, text))
    fun final(text: String) = apply(TurnRules.final(state, text))
    fun speechEnded() = apply(TurnRules.speechEnded(state, now(), settings()))
    fun reset() = apply(TurnRules.reset(state))

    private fun apply(step: TurnStep) {
        state = step.state
        step.effects.forEach(::run)
        step.fired.forEach(onJevFired)
        step.events.forEach(onEvent)
    }

    private fun run(effect: Effect) {
        when (effect) {
            is Effect.StartTimer -> {
                timers.remove(effect.timer)?.cancel()
                timers[effect.timer] = scope.launch {
                    delay(effect.delayMs)
                    timers.remove(effect.timer)
                    apply(TurnRules.timer(state, effect.timer, now(), context))
                }
            }
            is Effect.CancelTimer -> timers.remove(effect.timer)?.cancel()
            is Effect.CallJev -> calls[effect.seq] = scope.launch {
                val result = decide(effect.request, effect.timeoutMs)
                calls.remove(effect.seq)
                apply(TurnRules.answered(state, effect.seq, result, now(), context))
            }
            is Effect.CancelJev -> calls.remove(effect.seq)?.cancel()
            else -> error("unexpected effect $effect")
        }
    }
}
