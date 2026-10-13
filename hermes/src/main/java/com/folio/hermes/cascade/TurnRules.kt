package com.folio.hermes.cascade

import com.folio.hermes.cascade.routing.CascadeBrain
import com.folio.hermes.cascade.routing.Router
import com.folio.hermes.openrouter.FailureKind
import com.folio.hermes.openrouter.JevAnswer
import com.folio.hermes.openrouter.OpenRouterResult

/** How a Jev answer was got, for the decision log. */
data class JevTiming(
    /** Speech end → the answer used. */
    val speechEndToJevMs: Long?,
    /** The answering call's own duration. */
    val jevMs: Long?,
    val calls: Int,
)

/** What the turn-taking concluded at a pause. */
sealed interface TurnEvent {
    val turnId: String
    val heard: String
    val pending: List<String>

    /** The user finished: act on [answers]. The text is pending + heard. */
    data class Done(
        override val turnId: String,
        override val heard: String,
        override val pending: List<String>,
        val request: JevRequest,
        val answers: Map<String, JevAnswer>,
        val timing: JevTiming,
        /** When speech ended (the actor's clock). */
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

/** One Jev call made by the turn-taking, and what came back. Correlated by [seq] (R-ARC-2). */
data class JevCall(
    val seq: Long,
    val request: JevRequest,
    val startedAt: Long,
    val early: Boolean,
    val result: OpenRouterResult<Map<String, JevAnswer>>? = null,
    val doneAt: Long? = null,
    val cancelled: Boolean = false,
) {
    val active: Boolean get() = result == null && !cancelled
}

/** Where a pause is. */
enum class PauseStage {
    /** Waiting for the early call point. */
    Early,

    /** Early call out; waiting for `pause_ms`. */
    Commit,

    /** At `pause_ms`, waiting for an answer (the commit call, maybe racing the early one). */
    Committing,

    /** keep_listening: waiting [CascadeSettings.holdRecheckMs] to ask once more. */
    Holding,

    /** The recheck call is out. */
    Rechecking,
}

/** One pause, from speech end to a turn event. [settings] are read once, at speech end. */
data class PauseState(
    val seq: Long,
    val endAt: Long,
    val settings: CascadeSettings,
    val stage: PauseStage,
    val early: JevCall? = null,
    val commit: JevCall? = null,
    val racing: Boolean = false,
    /** While racing: the first answer back failed. */
    val firstFailed: Boolean = false,
    /** While holding or rechecking: the turn id of the Holding event. */
    val turnId: String? = null,
    /** R-RT-8: no Jev for this pause (commit on the timer alone). Off unless the routing hook says so. */
    val withoutJev: Boolean = false,
    /** Hold-to-talk was released: the turn is done whatever Jev's `turn` says (R-STT-8). */
    val released: Boolean = false,
) {
    val calls: List<JevCall> get() = listOfNotNull(early, commit)
}

/**
 * The turn-taking's state (from the C1 `TurnTaker`), a pure value owned by the actor.
 * [finals] keeps the C1 spacing (each final appended after a space; [heard] trims).
 */
data class TurnState(
    val finals: String = "",
    val partial: String = "",
    val pending: List<String> = emptyList(),
    /** Jev said keep_listening twice: the words stay held for the next turn, but no longer hold back results. */
    val parked: Boolean = false,
    val turns: Int = 0,
    val pause: PauseState? = null,
    val pauseSeq: Long = 0L,
    /** Correlation ids for Jev calls (turn-taking and outbox resends share it). */
    val jevSeq: Long = 0L,
) {
    /** Transcript since the last committed turn. */
    val heard: String get() = listOf(finals.trim(), partial.trim()).filter { it.isNotEmpty() }.joinToString(" ")

    /**
     * True while the user has said something that is not acted on yet (results must wait). Words
     * still held after the keep_listening recheck no longer count: they join the next turn, but
     * the gate opens.
     */
    val holding: Boolean get() = heard.isNotEmpty() || (pending.isNotEmpty() && !parked)

    /** True while waiting out a pause (timer or Jev call running). */
    val deciding: Boolean get() = pause != null

    /** Jev calls in flight for this pause. */
    val jevInFlight: Int get() = pause?.calls?.count { it.active } ?: 0
}

/** What the turn-taking produced: the new state, effects to run, and what happened. */
data class TurnStep(
    val state: TurnState,
    val effects: List<Effect> = emptyList(),
    /** Turn events, in order. */
    val events: List<TurnEvent> = emptyList(),
    /** Jev calls fired (`true` = the early call), in order. */
    val fired: List<Boolean> = emptyList(),
)

/** Builds the Jev request for the words: heard, pending, silence so far. */
typealias JevAsk = (heard: String, pending: List<String>, silenceMs: Long) -> JevRequest

/**
 * Decides when the user has finished, with Jev (R-RT-1, R-RT-2; spec v2 §0). Pure: every function
 * returns a [TurnStep]; timers and calls are [Effect]s whose results come back as events.
 *
 * - At `early = min(500, pause / 2)` ms of silence one Jev call fires; it only hides Jev's latency.
 * - Nothing is acted on before `pause` ms of silence ([CascadeSettings.pause], 400–3000).
 * - At `pause`: the early answer is used if it came back, succeeded and is about the same words.
 *   Otherwise a second call goes out, racing the early one if that is still running and still
 *   about the same words; the first good answer wins.
 * - Speech resuming cancels the timer and any call in flight (their results are stale).
 * - keep_listening holds the words in `pending` and asks once more after
 *   [CascadeSettings.holdRecheckMs] more silence.
 */
object TurnRules {
    const val EARLY = "turn.early"
    const val COMMIT = "turn.commit"
    const val RECHECK = "turn.recheck"

    private class Tx(var s: TurnState) {
        val effects = mutableListOf<Effect>()
        val events = mutableListOf<TurnEvent>()
        val fired = mutableListOf<Boolean>()
        fun step() = TurnStep(s, effects.toList(), events.toList(), fired.toList())
    }

    /** VAD: the user started speaking. Cancels the pause timer and any Jev call. */
    fun speechStarted(t: TurnState): TurnStep {
        val tx = Tx(t)
        tx.endPause()
        tx.s = tx.s.copy(parked = false)
        return tx.step()
    }

    /** STT partial: replaces the current partial. */
    fun partial(t: TurnState, text: String): TurnStep = TurnStep(t.copy(partial = text))

    /** STT final: appended to what was heard. */
    fun final(t: TurnState, text: String): TurnStep =
        TurnStep(t.copy(partial = "", finals = if (text.isNotBlank()) t.finals + " " + text.trim() else t.finals))

    /**
     * VAD: the user stopped speaking. Starts the pause timer if anything was said.
     * [withoutJev] (R-RT-8 hook, default false): commit at `pause + noJevCommitExtraMs` with no Jev call.
     *
     * [endAt] is when speech really ended (`SttEvent.SpeechEndedAt`, the VAD hangover before the
     * event arrived; S-C bench: about 0.4 s); the pause counts from there, not from [now]. The
     * time already gone is clamped to `[0, early]`, so a stamp from another clock or a stale
     * one can at worst fire the early call at once (spec v2 §14, S-C spec problem 2).
     */
    fun speechEnded(t: TurnState, now: Long, settings: CascadeSettings, withoutJev: Boolean = false, endAt: Long = now): TurnStep {
        val tx = Tx(t)
        tx.endPause()
        if (tx.s.heard.isEmpty() && tx.s.pending.isEmpty()) return tx.step()
        val gone = (now - endAt).coerceIn(0L, settings.early)
        val seq = tx.s.pauseSeq + 1
        tx.s = tx.s.copy(pauseSeq = seq, pause = PauseState(seq, now - gone, settings, if (withoutJev) PauseStage.Commit else PauseStage.Early, withoutJev = withoutJev))
        if (withoutJev) {
            tx.effects += Effect.StartTimer(Timer(COMMIT, seq), settings.pause + settings.noJevCommitExtraMs - gone)
        } else {
            tx.effects += Effect.StartTimer(Timer(EARLY, seq), settings.early - gone)
        }
        return tx.step()
    }

    /**
     * Hold-to-talk released (R-STT-8): the user said they are done, so the turn commits at once.
     * One Jev call goes out now (it still picks the action, task and line); its `turn` answer is
     * read as `done` whatever it says (S-C spec problem 6).
     */
    fun released(t: TurnState, now: Long, settings: CascadeSettings, ask: JevAsk, withoutJev: Boolean = false): TurnStep {
        val tx = Tx(t)
        tx.endPause()
        if (tx.s.heard.isEmpty() && tx.s.pending.isEmpty()) return tx.step()
        val seq = tx.s.pauseSeq + 1
        val p = PauseState(seq, now, settings, PauseStage.Committing, withoutJev = withoutJev, released = true)
        tx.s = tx.s.copy(pauseSeq = seq, pause = p)
        if (withoutJev) {
            tx.resolve(p, Unanswered(OpenRouterResult.Failed(FailureKind.Offline)), JevTiming(null, null, 0), now, ask)
        } else {
            val call = tx.fire(ask(tx.s.heard, tx.s.pending, 0L), now, early = false)
            tx.s = tx.s.copy(pause = tx.s.pause!!.copy(commit = call))
        }
        return tx.step()
    }

    /** Forget everything held (call end). */
    fun reset(t: TurnState): TurnStep {
        val tx = Tx(t)
        tx.endPause()
        tx.s = tx.s.copy(finals = "", partial = "", pending = emptyList(), parked = false)
        return tx.step()
    }

    /** A turn timer fired at [now]. Stale timers (an older pause) do nothing. */
    fun timer(t: TurnState, timer: Timer, now: Long, ask: JevAsk): TurnStep {
        val p = t.pause ?: return TurnStep(t)
        if (timer.seq != p.seq) return TurnStep(t)
        val tx = Tx(t)
        val s = p.settings
        when (timer.name) {
            EARLY -> if (p.stage == PauseStage.Early) {
                val call = tx.fire(ask(t.heard, t.pending, s.early), now, early = true)
                tx.s = tx.s.copy(pause = p.copy(stage = PauseStage.Commit, early = call))
                tx.effects += Effect.StartTimer(Timer(COMMIT, p.seq), s.pause - s.early)
            }
            COMMIT -> if (p.stage == PauseStage.Commit) tx.commit(p, now, ask)
            RECHECK -> if (p.stage == PauseStage.Holding) {
                val call = tx.fire(ask(t.heard, t.pending, s.pause + s.holdRecheckMs), now, early = false)
                tx.s = tx.s.copy(pause = p.copy(stage = PauseStage.Rechecking, commit = call, early = null))
            }
        }
        return tx.step()
    }

    /** True if [seq] is a Jev call of the current pause that is still waited on. */
    fun owns(t: TurnState, seq: Long): Boolean = t.pause?.calls?.any { it.seq == seq && it.active } == true

    /** A Jev answer for call [seq] at [now]. A call that is not ours or no longer active is stale and dropped. */
    fun answered(t: TurnState, seq: Long, result: OpenRouterResult<Map<String, JevAnswer>>, now: Long, ask: JevAsk): TurnStep {
        val p = t.pause ?: return TurnStep(t)
        val call = p.calls.firstOrNull { it.seq == seq && it.active } ?: return TurnStep(t)
        val done = call.copy(result = result, doneAt = now)
        val q = if (p.early?.seq == seq) p.copy(early = done) else p.copy(commit = done)
        val tx = Tx(t.copy(pause = q))
        when (q.stage) {
            PauseStage.Early, PauseStage.Commit, PauseStage.Holding -> Unit // kept for the commit point
            PauseStage.Committing -> tx.race(q, done, now, ask)
            PauseStage.Rechecking -> {
                val recheck = result.toAnswer(done.request)
                if (recheck !is Answered || Router.verdict(recheck.answers, q.settings) == TurnVerdict.KeepListening) {
                    tx.s = tx.s.copy(parked = true, pause = null)
                    tx.events += TurnEvent.Parked(q.turnId.orEmpty(), tx.s.heard, tx.s.pending)
                } else {
                    tx.resolve(q, recheck, JevTiming(now - q.endAt, now - done.startedAt, 1), now, ask)
                }
            }
        }
        return tx.step()
    }

    // ── internals ───────────────────────────────────────────────────────────────────────────

    private fun Tx.fire(request: JevRequest, now: Long, early: Boolean): JevCall {
        val seq = s.jevSeq + 1
        s = s.copy(jevSeq = seq)
        fired += early
        val p = s.pause!!
        effects += Effect.CallJev(seq, request, p.settings.jevTimeoutMs, early)
        return JevCall(seq, request, now, early)
    }

    /** Cancel the pause's timer and calls. */
    private fun Tx.endPause() {
        val p = s.pause ?: return
        effects += Effect.CancelTimer(Timer(EARLY, p.seq))
        effects += Effect.CancelTimer(Timer(COMMIT, p.seq))
        effects += Effect.CancelTimer(Timer(RECHECK, p.seq))
        p.calls.filter { it.active }.forEach { effects += Effect.CancelJev(it.seq) }
        s = s.copy(pause = null)
    }

    private fun Tx.commit(p: PauseState, now: Long, ask: JevAsk) {
        if (p.withoutJev) {
            resolve(p, Unanswered(OpenRouterResult.Failed(FailureKind.Offline)), JevTiming(null, null, 0), now, ask)
            return
        }
        val early = p.early!!
        val sameWords = early.request.key == CascadeBrain.key(s.heard, s.pending)
        val earlyResult = early.result
        if (sameWords && earlyResult is OpenRouterResult.Ok) {
            val doneAt = early.doneAt!!
            resolve(p, Answered(early.request, earlyResult.value), JevTiming(doneAt - p.endAt, doneAt - early.startedAt, 1), now, ask)
            return
        }
        var q = p
        if (!sameWords && early.active) {
            effects += Effect.CancelJev(early.seq)
            q = q.copy(early = early.copy(cancelled = true))
        }
        s = s.copy(pause = q.copy(stage = PauseStage.Committing))
        val second = fire(ask(s.heard, s.pending, p.settings.pause), now, early = false)
        val racing = sameWords && early.active
        s = s.copy(pause = s.pause!!.copy(commit = second, racing = racing))
    }

    private fun Tx.race(p: PauseState, done: JevCall, now: Long, ask: JevAsk) {
        val result = done.result!!
        val commit = p.commit ?: return
        if (!p.racing) {
            if (done.seq != commit.seq) return // the early call's late answer: not used
            resolve(
                p,
                result.toAnswer(done.request),
                JevTiming(if (result is OpenRouterResult.Ok) now - p.endAt else null, if (result is OpenRouterResult.Ok) now - done.startedAt else null, 2),
                now,
                ask,
            )
            return
        }
        if (result is OpenRouterResult.Ok) {
            val other = if (done.seq == commit.seq) p.early else commit
            if (other != null && other.active) {
                effects += Effect.CancelJev(other.seq)
                s = s.copy(pause = if (other.seq == commit.seq) p.copy(commit = other.copy(cancelled = true)) else p.copy(early = other.copy(cancelled = true)))
            }
            resolve(p, Answered(done.request, result.value), JevTiming(now - p.endAt, now - done.startedAt, 2), now, ask)
            return
        }
        if (!p.firstFailed) {
            s = s.copy(pause = p.copy(firstFailed = true))
            return
        }
        // Both failed: report the commit call's failure.
        val failure = commit.result as? OpenRouterResult.Failed ?: (result as OpenRouterResult.Failed)
        resolve(p, Unanswered(failure), JevTiming(null, null, 2), now, ask)
    }

    /** The C1 loop body: one answer becomes one turn event (and keep_listening schedules the recheck). */
    private fun Tx.resolve(p: PauseState, answer: Outcome, timing: JevTiming, now: Long, ask: JevAsk) {
        val turnId = "u${s.turns + 1}"
        s = s.copy(turns = s.turns + 1)
        val words = s.heard
        val held = s.pending
        when (answer) {
            is Unanswered -> {
                clearAll()
                events += TurnEvent.NoAnswer(turnId, words, held, answer.failure, timing, p.endAt)
            }
            is Answered -> when (if (p.released) TurnVerdict.Done else Router.verdict(answer.answers, p.settings)) {
                TurnVerdict.Done -> {
                    clearAll()
                    events += TurnEvent.Done(turnId, words, held, answer.request, answer.answers, timing, p.endAt)
                }
                TurnVerdict.Ignore -> {
                    clearHeard()
                    s = s.copy(pause = null)
                    events += TurnEvent.Ignored(turnId, words, held, answer.request, answer.answers, timing)
                }
                TurnVerdict.KeepListening -> {
                    val pending = if (words.isNotEmpty()) s.pending + words else s.pending
                    s = s.copy(pending = pending, finals = "", partial = "")
                    events += TurnEvent.Holding(turnId, words, pending, answer.request, answer.answers, timing)
                    // Still quiet after a while longer: ask once more about everything held.
                    s = s.copy(pause = p.copy(stage = PauseStage.Holding, turnId = turnId, racing = false, firstFailed = false))
                    effects += Effect.StartTimer(Timer(RECHECK, p.seq), p.settings.holdRecheckMs)
                }
            }
        }
    }

    private fun Tx.clearHeard() {
        s = s.copy(finals = "", partial = "")
    }

    private fun Tx.clearAll() {
        s = s.copy(finals = "", partial = "", pending = emptyList(), parked = false, pause = null)
    }

    private sealed interface Outcome
    private data class Answered(val request: JevRequest, val answers: Map<String, JevAnswer>) : Outcome
    private data class Unanswered(val failure: OpenRouterResult.Failed) : Outcome

    private fun OpenRouterResult<Map<String, JevAnswer>>.toAnswer(request: JevRequest): Outcome = when (this) {
        is OpenRouterResult.Ok -> Answered(request, value)
        is OpenRouterResult.Failed -> Unanswered(this)
    }
}
