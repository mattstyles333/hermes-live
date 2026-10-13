package com.folio.hermes.cascade.routing

import com.folio.hermes.SubmitStatus
import com.folio.hermes.TurnUpdate
import com.folio.hermes.cascade.CascadeEnv
import com.folio.hermes.cascade.CascadeEvent
import com.folio.hermes.cascade.CascadeReducer
import com.folio.hermes.cascade.CascadeSettings
import com.folio.hermes.cascade.ClipOutcome
import com.folio.hermes.cascade.Effect
import com.folio.hermes.cascade.Event
import com.folio.hermes.cascade.PhraseBank
import com.folio.hermes.cascade.Piece
import com.folio.hermes.cascade.State
import com.folio.hermes.cascade.Timer
import com.folio.hermes.cascade.TurnRules
import com.folio.hermes.cascade.answer
import com.folio.hermes.cascade.conversation.ConversationPolicy
import com.folio.hermes.openrouter.JevAnswer
import com.folio.hermes.openrouter.OpenRouterResult
import java.time.ZoneOffset

/**
 * Drives the pure `reduce` with the routing slice switched on (no coroutines, no clock): feed
 * events at virtual times and look at the effects. Speech is played instantly by [drain].
 */
class CeoHarness(
    settings: CascadeSettings = CascadeSettings(),
    conversation: ConversationPolicy = ConversationPolicy.None,
    val memo: MemoRouting = MemoRouting(ZoneOffset.UTC),
) {
    val bank: PhraseBank = PhraseBank.load()
    val env: CascadeEnv = CeoRouting.env(bank, memo, conversation = conversation)
    private val reducer = CascadeReducer(env)
    val epoch = 1_760_000_000_000L
    var now = 0L
    var s: State = reducer.reduce(State(settings = settings), Event.Start()).first
    val effects = mutableListOf<Effect>()

    /** Everything Bill has said (stock lines and written sentences), in play order. */
    val spoken = mutableListOf<Piece>()

    /** Session-first opens are answered at once (as the gateway would), so the submit follows in the same call. */
    var openSessions = true

    fun at(ms: Long, event: Event): List<Effect> {
        now = maxOf(now, ms)
        val (next, out) = reducer.reduce(s.copy(nowMs = now, wallMs = epoch + now), event)
        s = next
        effects += out
        if (!openSessions) return out
        val opened = out.filterIsInstance<Effect.OpenTaskSession>().flatMap { o -> at(ms, Event.TaskSessionOpened(o.taskId, o.runId, "stored-${o.taskId}")) }
        return out + opened
    }

    fun on(event: Event): List<Effect> = at(now, event)

    /** Say [text] at [from]; Jev answers [answers]; the turn commits at `from + 1000`. Returns the commit's effects. */
    fun turn(text: String, from: Long, vararg answers: Pair<String, JevAnswer>): List<Effect> {
        at(from, Event.SpeechStarted)
        at(from, Event.Final(text))
        at(from, Event.SpeechEnded)
        val call = at(from + 500, Event.TimerFired(Timer(TurnRules.EARLY, s.turn.pauseSeq))).filterIsInstance<Effect.CallJev>().single()
        at(from + 600, Event.JevAnswered(call.seq, OpenRouterResult.Ok(mapOf("turn" to answer("done")) + answers), from + 600))
        return at(from + 1_000, Event.TimerFired(Timer(TurnRules.COMMIT, s.turn.pauseSeq)))
    }

    /** Answer the newest whole-writer request with [text]. */
    fun write(text: String): List<Effect> {
        val w = effects.filterIsInstance<Effect.Write>().last()
        return on(Event.WriterWrote(w.seq, OpenRouterResult.Ok(text)))
    }

    /** Stream [sentences] for the newest writer stream, then end it. */
    fun stream(vararg sentences: String): List<Effect> {
        val w = effects.filterIsInstance<Effect.StreamWriter>().last()
        sentences.forEach { on(Event.WriterSentence(w.seq, it)) }
        return on(Event.WriterFinished(w.seq, null))
    }

    fun submits(): List<Effect.SubmitTask> = effects.filterIsInstance<Effect.SubmitTask>()

    fun accept(submit: Effect.SubmitTask, status: SubmitStatus = SubmitStatus.Streaming) =
        on(Event.TaskUpdated(submit.taskId, submit.runId, TurnUpdate.Accepted("rt-${submit.taskId}", status)))

    fun emitted(): List<CascadeEvent> = effects.filterIsInstance<Effect.Emit>().map { it.event }

    /** Play everything queued, instantly, recording what was said. */
    fun drain() {
        repeat(500) {
            val pb = s.playback ?: return
            val piece = pb.piece
            if (!pb.playing) on(Event.ClipReady(piece, ClipOutcome.Ok))
            on(Event.PlaybackStarted(piece, now))
            spoken += piece
            on(Event.PlaybackFinished(piece, 100, 100))
        }
    }

    fun said(): List<String> = spoken.map { it.text }

    fun slotsSaid(): List<String?> = spoken.map { it.slot }

    /** Every piece queued so far (played or not). */
    fun queuedTexts(): List<String> = (s.speech.queue + s.speech.done).flatMap { u -> u.pieces.map { it.text } } + spoken.map { it.text }

    companion object {
        fun brief(goal: String, label: String, summary: String) =
            """{"brief":"Goal: $goal\nDone when: it is done","label":"$label","summary":"$summary"}"""

        fun briefs(vararg b: String, repaired: String = "", notes: String = "") =
            """{"briefs":[${b.joinToString(",")}],"repaired":"$repaired","notes":"$notes"}"""
    }
}
