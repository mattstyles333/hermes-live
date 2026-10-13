package com.folio.hermes.cascade

import com.folio.hermes.openrouter.JevAnswer
import com.folio.hermes.openrouter.OpenRouterResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

/** How a Jev answer was got, for the decision log. */
data class JevTiming(
    /** Speech end → the answer used. */
    val speechEndToJevMs: Long?,
    /** The answering call's own duration. */
    val jevMs: Long?,
    val calls: Int,
)

/** What the TurnTaker concluded at a pause. */
sealed interface TurnEvent {
    val turnId: String
    val heard: String
    val pending: List<String>

    /** The user finished: act on [answers] (route with [Router.decide]). [text] is pending + heard. */
    data class Done(
        override val turnId: String,
        override val heard: String,
        override val pending: List<String>,
        val request: JevRequest,
        val answers: Map<String, JevAnswer>,
        val timing: JevTiming,
        /** When speech ended ([TurnTaker]'s clock). */
        val speechEndAt: Long,
    ) : TurnEvent

    /** Mid-thought: [heard] was moved into pending; keep collecting. */
    data class Holding(
        override val turnId: String,
        override val heard: String,
        override val pending: List<String>,
        val request: JevRequest,
        val answers: Map<String, JevAnswer>,
        val timing: JevTiming,
    ) : TurnEvent

    /** Still keep_listening at the recheck: [pending] stays for the next turn, and results may play meanwhile. */
    data class Parked(
        override val turnId: String,
        override val heard: String,
        override val pending: List<String>,
    ) : TurnEvent

    /** Noise or echo: [heard] was dropped. */
    data class Ignored(
        override val turnId: String,
        override val heard: String,
        override val pending: List<String>,
        val request: JevRequest,
        val answers: Map<String, JevAnswer>,
        val timing: JevTiming,
    ) : TurnEvent

    /**
     * Jev gave nothing usable. [failure] [FailureKind.Offline] (no response at all) means hold the
     * turn in the outbox; anything else means ask the user to repeat. Never starts a task.
     */
    data class NoAnswer(
        override val turnId: String,
        override val heard: String,
        override val pending: List<String>,
        val failure: OpenRouterResult.Failed,
        val timing: JevTiming,
        val speechEndAt: Long,
    ) : TurnEvent
}

/**
 * Decides when the user has finished, with Jev (U1, U3). Single-threaded: call it and run [scope]
 * on one thread (Main in the app).
 *
 * - At `early = min(500, pause / 2)` ms of silence one Jev call fires; it only hides Jev's latency.
 * - Nothing is acted on before `pause` ms of silence ([CascadeSettings.pause], 400–3000).
 * - At `pause`: the early answer is used if it came back, succeeded and is about the same words.
 *   Otherwise a second call goes out (2.5 s budget), racing the early one if that is still
 *   running and still about the same words; the first good answer wins.
 * - Speech resuming cancels the timer and any call in flight.
 * - keep_listening holds the words in `pending` and asks once more after
 *   [CascadeSettings.holdRecheckMs] more silence.
 *
 * [context] builds the Jev context from the TurnTaker's words; [decide] is the Jev call (with its
 * timeout); [now] is a millisecond clock.
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
    private val finals = StringBuilder()
    private var partial = ""
    private val pendingWords = mutableListOf<String>()
    private var timer: Job? = null
    private var turns = 0

    /** Jev said keep_listening twice: the words stay held for the next turn, but no longer hold back results. */
    private var parked = false

    /** Transcript since the last committed turn. */
    val heard: String get() = listOf(finals.toString().trim(), partial.trim()).filter { it.isNotEmpty() }.joinToString(" ")

    val pending: List<String> get() = pendingWords.toList()

    /**
     * True while the user has said something that is not acted on yet (results must wait). Words
     * still held after the keep_listening recheck no longer count: they join the next turn, but
     * the gate opens.
     */
    val holding: Boolean get() = heard.isNotEmpty() || (pendingWords.isNotEmpty() && !parked)

    /** True while waiting out a pause (timer or Jev call running). */
    val deciding: Boolean get() = timer?.isActive == true

    /** VAD: the user started speaking. Cancels the pause timer and any Jev call. */
    fun speechStarted() {
        timer?.cancel()
        timer = null
        parked = false
    }

    /** STT partial: replaces the current partial. */
    fun partial(text: String) {
        partial = text
    }

    /** STT final: appended to what was heard. */
    fun final(text: String) {
        partial = ""
        if (text.isNotBlank()) finals.append(' ').append(text.trim())
    }

    /** VAD: the user stopped speaking. Starts the pause timer if anything was said. */
    fun speechEnded() {
        timer?.cancel()
        if (heard.isEmpty() && pendingWords.isEmpty()) return
        val endAt = now()
        timer = scope.launch { pause(endAt) }
    }

    /** Forget everything held (call end). */
    fun reset() {
        timer?.cancel()
        timer = null
        finals.setLength(0)
        partial = ""
        pendingWords.clear()
        parked = false
    }

    private suspend fun CoroutineScope.pause(endAt: Long) {
        val s = settings()
        val early = s.early
        delay(early)
        val earlyRequest = context(heard, pending, early)
        val earlyStart = now()
        onJevFired(true)
        val earlyCall = async { decide(earlyRequest, s.jevTimeoutMs) to now() }
        delay(s.pause - early)
        var (answer, timing) = commit(s, earlyRequest, earlyStart, earlyCall, endAt)
        while (true) {
            val turnId = "u${++turns}"
            val words = heard
            val held = pending
            when (answer) {
                is Unanswered -> {
                    clearAll()
                    onEvent(TurnEvent.NoAnswer(turnId, words, held, answer.failure, timing, endAt))
                    return
                }
                is Answered -> when (Router.verdict(answer.answers, s)) {
                    TurnVerdict.Done -> {
                        clearAll()
                        onEvent(TurnEvent.Done(turnId, words, held, answer.request, answer.answers, timing, endAt))
                        return
                    }
                    TurnVerdict.Ignore -> {
                        clearHeard()
                        onEvent(TurnEvent.Ignored(turnId, words, held, answer.request, answer.answers, timing))
                        return
                    }
                    TurnVerdict.KeepListening -> {
                        if (words.isNotEmpty()) pendingWords += words
                        clearHeard()
                        onEvent(TurnEvent.Holding(turnId, words, pending, answer.request, answer.answers, timing))
                        // Still quiet after a while longer: ask once more about everything held.
                        delay(s.holdRecheckMs)
                        val again = context(heard, pending, s.pause + s.holdRecheckMs)
                        val start = now()
                        onJevFired(false)
                        val result = decide(again, s.jevTimeoutMs)
                        val recheck = result.toAnswer(again)
                        if (recheck !is Answered || Router.verdict(recheck.answers, s) == TurnVerdict.KeepListening) {
                            parked = true
                            onEvent(TurnEvent.Parked(turnId, heard, pending))
                            return
                        }
                        answer = recheck
                        timing = JevTiming(now() - endAt, now() - start, 1)
                    }
                }
            }
        }
    }

    /** The answer at the commit point: the early one if usable, else a second call (racing the early one). */
    private suspend fun CoroutineScope.commit(
        s: CascadeSettings,
        earlyRequest: JevRequest,
        earlyStart: Long,
        earlyCall: Deferred<Pair<OpenRouterResult<Map<String, JevAnswer>>, Long>>,
        endAt: Long,
    ): Pair<Outcome, JevTiming> {
        val sameWords = earlyRequest.key == CascadeBrain.key(heard, pending)
        if (sameWords && earlyCall.isCompleted) {
            val (result, doneAt) = earlyCall.await()
            if (result is OpenRouterResult.Ok) {
                return result.toAnswer(earlyRequest) to JevTiming(doneAt - endAt, doneAt - earlyStart, 1)
            }
        }
        if (!sameWords) earlyCall.cancel()
        val request = context(heard, pending, s.pause)
        val start = now()
        onJevFired(false)
        val second = async { decide(request, s.jevTimeoutMs) to now() }
        val racing = sameWords && earlyCall.isActive
        if (racing) {
            val (first, firstIsEarly) = select {
                earlyCall.onAwait { it to true }
                second.onAwait { it to false }
            }
            if (first.first is OpenRouterResult.Ok) {
                (if (firstIsEarly) second else earlyCall).cancel()
                val (req, began) = if (firstIsEarly) earlyRequest to earlyStart else request to start
                return first.first.toAnswer(req) to JevTiming(first.second - endAt, first.second - began, 2)
            }
            val other = (if (firstIsEarly) second else earlyCall).await()
            if (other.first is OpenRouterResult.Ok) {
                val (req, began) = if (firstIsEarly) request to start else earlyRequest to earlyStart
                return other.first.toAnswer(req) to JevTiming(other.second - endAt, other.second - began, 2)
            }
            // Both failed: report the commit call's failure.
            val failed = if (firstIsEarly) other else first
            return failed.first.toAnswer(request) to JevTiming(null, null, 2)
        }
        val (result, doneAt) = second.await()
        return result.toAnswer(request) to JevTiming(
            if (result is OpenRouterResult.Ok) doneAt - endAt else null,
            if (result is OpenRouterResult.Ok) doneAt - start else null,
            2,
        )
    }

    private fun clearHeard() {
        finals.setLength(0)
        partial = ""
    }

    private fun clearAll() {
        clearHeard()
        pendingWords.clear()
        parked = false
    }

    private sealed interface Outcome
    private data class Answered(val request: JevRequest, val answers: Map<String, JevAnswer>) : Outcome
    private data class Unanswered(val failure: OpenRouterResult.Failed) : Outcome

    private fun OpenRouterResult<Map<String, JevAnswer>>.toAnswer(request: JevRequest): Outcome = when (this) {
        is OpenRouterResult.Ok -> Answered(request, value)
        is OpenRouterResult.Failed -> Unanswered(this)
    }
}
