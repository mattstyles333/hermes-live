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
        /** Null: Jev said `done`. [HOLD_SILENCE]: held words that looked like a request, committed after silence. */
        val via: String? = null,
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

    /**
     * Still keep_listening at the recheck: [pending] stays for the next turn, and results may play
     * meanwhile. [answers] is what the recheck said (null when it failed: [failure]), for the log.
     */
    data class Parked(
        override val turnId: String,
        override val heard: String,
        override val pending: List<String>,
        val answers: Map<String, JevAnswer>? = null,
        val failure: FailureKind? = null,
        val jevMs: Long? = null,
    ) : TurnEvent

    /**
     * The hold-silence rule: words are held and the user has been silent for
     * [CascadeSettings.holdSilenceMs], and they do not look like a request. Bill says [slot]
     * ([AppLines.HOLD_GO_ON], then once more [AppLines.HOLD_CONFIRM]); the words stay held.
     */
    data class Prompted(
        override val turnId: String,
        override val heard: String,
        override val pending: List<String>,
        val slot: String,
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

/** What Jev last said about exactly the words held now ([utterance]): the hold-silence rule reads its `action`. */
data class HeldAnswers(val utterance: String, val request: JevRequest, val answers: Map<String, JevAnswer>)

/**
 * Words held with no pause running (parked after the recheck, or left after an ignored blip): the
 * hold-silence timer [seq] and how far it got. [step] 0: waiting to commit or say "Go on";
 * 1: said it, waiting to ask "go ahead?"; 2: asked, nothing more. [endAt] is when speech ended.
 */
data class HoldSilence(val seq: Long, val step: Int, val endAt: Long, val turnId: String, val settings: CascadeSettings)

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
    /** Jev's latest answers about the held words (kept while they are held). */
    val heldAnswers: HeldAnswers? = null,
    /** The hold-silence rule while words are held with no pause running. */
    val silence: HoldSilence? = null,
    val silenceSeq: Long = 0L,
    /**
     * Cloud text shown in the caption in place of a fast segment's text (fast → cloud), when it
     * did not rewrite Jev's words ([TurnRules.revised]). Additive (cloud STT slice).
     */
    val shownAs: Map<String, String> = emptyMap(),
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

    /** [text] as the caption shows it (cloud text in place of fast text, [shownAs]). */
    fun shown(text: String): String = shownAs.entries.fold(text) { acc, (fast, cloud) -> acc.replaceLast(fast, cloud) }
}

/** [this] with the last [old] replaced by [new] (unchanged if absent). */
internal fun String.replaceLast(old: String, new: String): String {
    if (old.isEmpty()) return this
    val i = lastIndexOf(old)
    return if (i < 0) this else substring(0, i) + new + substring(i + old.length)
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
 * - **No held turn waits forever in silence** (hold-silence rule, 2026-10-14). Words still held
 *   with no pause running (parked after the recheck, or left after an ignored blip) start a
 *   [CascadeSettings.holdSilenceMs] timer. When it fires in real silence: if Jev's latest answer
 *   about exactly those words looks like a request ([Router.looksLikeRequest]), they commit
 *   ([TurnEvent.Done] `via` [HOLD_SILENCE]); otherwise Bill says "Go on" once, and after another
 *   [CascadeSettings.holdSilenceMs] asks "go ahead?" (`clarify.confirm`); the words stay held.
 *   Any speech resets it; while Bill (or an action) is busy it waits again.
 */
object TurnRules {
    const val EARLY = "turn.early"
    const val COMMIT = "turn.commit"
    const val RECHECK = "turn.recheck"
    const val SILENCE = "turn.silence"

    /** [TurnEvent.Done.via] for words committed by the hold-silence rule. */
    const val HOLD_SILENCE = "hold_silence"

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

    /**
     * Cloud speech-to-text's [text] for the fast segment that read [fast] (cloud mode).
     *
     * - **Material and Jev has not decided on these words yet** (no pause, or the pause is before
     *   its commit point, or keep_listening is waiting to ask again; not parked): the cloud words
     *   replace the fast ones. The commit point then sees other words than the early call's and
     *   asks Jev again through its usual path; the recheck asks about the cloud words.
     * - **Otherwise** (not material, or the commit or recheck call is already out, or parked):
     *   the caption shows the cloud text ([TurnState.shownAs]) and Jev's words stay as they are,
     *   so an answer in flight and the hold-silence rule's [HeldAnswers] still match them.
     *
     * Words already committed are gone from the turn: nothing changes (the accurate pass carries
     * the cloud text into the turn's action instead).
     */
    fun revised(t: TurnState, fast: String, text: String, material: Boolean): TurnStep {
        val f = fast.trim()
        val c = text.trim()
        if (f.isEmpty() || c.isEmpty() || f == c) return TurnStep(t)
        val inFinals = t.finals.contains(f)
        val inPending = t.pending.indexOfLast { it.contains(f) }
        if (!inFinals && inPending < 0) return TurnStep(t)
        val stage = t.pause?.stage
        val beforeCommit = !t.parked && t.silence == null && (stage == null || stage == PauseStage.Early || stage == PauseStage.Commit || stage == PauseStage.Holding)
        if (material && beforeCommit) {
            val next = if (inFinals) {
                t.copy(finals = t.finals.replaceLast(f, c))
            } else {
                t.copy(pending = t.pending.mapIndexed { i, p -> if (i == inPending) p.replaceLast(f, c) else p })
            }
            return TurnStep(next)
        }
        return TurnStep(t.copy(shownAs = t.shownAs + (f to c)))
    }

    /** Forget everything held (call end). */
    fun reset(t: TurnState): TurnStep {
        val tx = Tx(t)
        tx.endPause()
        tx.s = tx.s.copy(finals = "", partial = "", pending = emptyList(), parked = false, heldAnswers = null, shownAs = emptyMap())
        return tx.step()
    }

    /**
     * A turn timer fired at [now]. Stale timers (an older pause) do nothing. [busy]: Bill is
     * speaking or an action is running (the hold-silence rule waits for real silence).
     */
    fun timer(t: TurnState, timer: Timer, now: Long, ask: JevAsk, busy: Boolean = false): TurnStep {
        if (timer.name == SILENCE) return silence(t, timer, busy)
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

    /** The hold-silence timer fired (see the class comment). */
    private fun silence(t: TurnState, timer: Timer, busy: Boolean): TurnStep {
        val h = t.silence ?: return TurnStep(t)
        if (timer.seq != h.seq) return TurnStep(t)
        val tx = Tx(t)
        if (t.pending.isEmpty() || t.pause != null) {
            tx.s = tx.s.copy(silence = null)
            return tx.step()
        }
        if (busy) {
            tx.holdSilently(h.endAt, h.turnId, h.settings, h.step)
            return tx.step()
        }
        val utterance = CascadeBrain.utterance(t.heard, t.pending)
        val held = t.heldAnswers?.takeIf { it.utterance == utterance }
        when {
            h.step == 0 && held != null && Router.looksLikeRequest(held.answers, h.settings) -> {
                val turnId = "u${t.turns + 1}"
                val pending = t.pending
                tx.s = tx.s.copy(turns = t.turns + 1)
                tx.clearAll()
                tx.events += TurnEvent.Done(turnId, "", pending, held.request, held.answers, JevTiming(null, null, 0), h.endAt, via = HOLD_SILENCE)
            }
            h.step == 0 -> {
                tx.events += TurnEvent.Prompted(h.turnId, t.heard, t.pending, AppLines.HOLD_GO_ON)
                tx.holdSilently(h.endAt, h.turnId, h.settings, step = 1)
            }
            h.step == 1 -> {
                tx.events += TurnEvent.Prompted(h.turnId, t.heard, t.pending, AppLines.HOLD_CONFIRM)
                // Asked twice: the words stay held for the user's answer, but no longer hold back results.
                tx.s = tx.s.copy(silence = h.copy(step = 2), parked = true)
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
                if (recheck is Answered) tx.s = tx.s.copy(heldAnswers = HeldAnswers(CascadeBrain.utterance(tx.s.heard, tx.s.pending), recheck.request, recheck.answers))
                if (recheck !is Answered || Router.verdict(recheck.answers, q.settings) == TurnVerdict.KeepListening) {
                    tx.s = tx.s.copy(parked = true, pause = null)
                    tx.events += TurnEvent.Parked(
                        q.turnId.orEmpty(), tx.s.heard, tx.s.pending,
                        answers = (recheck as? Answered)?.answers,
                        failure = (result as? OpenRouterResult.Failed)?.kind,
                        jevMs = now - done.startedAt,
                    )
                    // Never wait forever in silence (the hold-silence rule).
                    tx.holdSilently(q.endAt, q.turnId.orEmpty(), q.settings)
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

    /** Words held with no pause running: (re)start the hold-silence timer at [step]. */
    private fun Tx.holdSilently(endAt: Long, turnId: String, settings: CascadeSettings, step: Int = 0) {
        s.silence?.let { effects += Effect.CancelTimer(Timer(SILENCE, it.seq)) }
        val seq = s.silenceSeq + 1
        s = s.copy(silenceSeq = seq, silence = HoldSilence(seq, step, endAt, turnId, settings))
        effects += Effect.StartTimer(Timer(SILENCE, seq), settings.holdSilenceMs)
    }

    /** Speech (or a new pause, or the call ending): the hold-silence timer starts again from nothing. */
    private fun Tx.endSilence() {
        val h = s.silence ?: return
        effects += Effect.CancelTimer(Timer(SILENCE, h.seq))
        s = s.copy(silence = null)
    }

    /** Cancel the pause's timer and calls (and the hold-silence timer: speech or a new pause). */
    private fun Tx.endPause() {
        endSilence()
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
                    // Words held before the blip stay held, and the blip cancelled their recheck: never wait forever.
                    if (s.pending.isNotEmpty()) holdSilently(p.endAt, turnId, p.settings)
                }
                TurnVerdict.KeepListening -> {
                    val pending = if (words.isNotEmpty()) s.pending + words else s.pending
                    s = s.copy(pending = pending, finals = "", partial = "", heldAnswers = HeldAnswers(CascadeBrain.utterance("", pending), answer.request, answer.answers))
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
        endSilence()
        s = s.copy(finals = "", partial = "", pending = emptyList(), parked = false, pause = null, heldAnswers = null, shownAs = emptyMap())
    }

    private sealed interface Outcome
    private data class Answered(val request: JevRequest, val answers: Map<String, JevAnswer>) : Outcome
    private data class Unanswered(val failure: OpenRouterResult.Failed) : Outcome

    private fun OpenRouterResult<Map<String, JevAnswer>>.toAnswer(request: JevRequest): Outcome = when (this) {
        is OpenRouterResult.Ok -> Answered(request, value)
        is OpenRouterResult.Failed -> Unanswered(this)
    }
}
