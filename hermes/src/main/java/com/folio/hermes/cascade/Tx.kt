package com.folio.hermes.cascade

import com.folio.hermes.cascade.routing.Next
import kotlin.random.Random

/**
 * One `reduce` step in progress: the state so far and the effects asked for. Created and thrown
 * away inside a single `reduce` call, so `reduce` stays pure. Route handlers and hooks (routing,
 * writer, store, conversation packages) work through it, so no slice needs to edit the reducer.
 */
class Tx(var s: State, val env: CascadeEnv) {
    val effects = mutableListOf<Effect>()

    // ── outputs ─────────────────────────────────────────────────────────────────────────────

    fun emit(event: CascadeEvent) {
        effects += Effect.Emit(event)
    }

    fun report(name: String, attrs: Map<String, Any?>, warn: Boolean = false) {
        effects += Effect.Report(name, attrs, warn)
    }

    fun log(record: DecisionRecord) {
        if (s.settings.decisionLog) effects += Effect.LogDecision(record)
    }

    fun nextSeq(): Long {
        val seq = s.seq + 1
        s = s.copy(seq = seq)
        return seq
    }

    // ── speech ──────────────────────────────────────────────────────────────────────────────

    fun open(kind: SpeechKind, taskId: String? = null, turnId: String? = null): Long {
        val (q, id) = s.speech.open(kind, taskId, turnId)
        s = s.copy(speech = q)
        return id
    }

    fun add(utteranceId: Long, text: String, slot: String? = null): Boolean {
        val (q, ok) = s.speech.add(utteranceId, text, slot)
        s = s.copy(speech = q)
        return ok
    }

    fun close(utteranceId: Long) {
        s = s.copy(speech = s.speech.close(utteranceId))
    }

    fun drop(utteranceId: Long) {
        s = s.copy(speech = s.speech.drop(utteranceId))
    }

    /** The next line of [slotId] (advancing its rotation, which is saved), or null for an unknown slot. */
    fun nextLine(slotId: String): String? {
        val lines = env.bank[slotId]?.lines?.takeIf { it.isNotEmpty() } ?: return null
        val random = Random(s.rotation.seed)
        val (line, rotation) = Rotations.next(lines, s.rotation.rotations[slotId], random)
        s = s.copy(rotation = RotationState(s.rotation.rotations + (slotId to rotation), random.nextLong()))
        effects += Effect.SaveRotation(slotId, rotation)
        return line
    }

    /** Play a slot on its own utterance (app events, approvals, fallbacks). Returns the line. */
    fun say(slotId: String, kind: SpeechKind = SpeechKind.Notice): String? {
        val line = nextLine(slotId) ?: return null
        val u = open(kind)
        add(u, line, slotId)
        close(u)
        return line
    }

    // ── context ─────────────────────────────────────────────────────────────────────────────

    fun briefs(): List<TaskBrief> = s.tasks.briefs(s.wallMs)

    /** `recent`: what the user said and what of Bill's answer they actually heard. */
    fun recentExchanges(): List<Exchange> = s.recent.map { Exchange(it.user, s.speech.heard(it.utteranceId)) }

    /** The voice context sent with a task submit (≤ `voiceContextChars`). */
    fun voiceContext(): String =
        recentExchanges().joinToString("\n") { "User: ${it.user}\nBill: ${it.bill}" }.takeLast(s.settings.voiceContextChars)

    /** The Jev request for these words, through the R-CTX-1 budget. */
    fun jevRequest(heard: String, pending: List<String>, silenceMs: Long, speaking: Boolean = s.speech.current != null): JevRequest {
        val ctx = TurnContext(heard, pending, silenceMs, recentExchanges(), briefs(), speaking)
        val request = env.handlers.hooks.ask(ctx, env.bank, s.settings)
        return request.copy(state = env.budget.fit(request.state, s.settings))
    }

    // ── the active action (C1 `execute`) ────────────────────────────────────────────────────

    /** The action being carried out. Only valid inside a route handler. */
    val action: ActionState get() = s.actions.active ?: error("no active action")

    fun updateAction(f: (ActionState) -> ActionState) {
        val a = s.actions.active ?: return
        s = s.copy(actions = s.actions.copy(active = f(a)))
    }

    /** Play [slot] into the action's reply, once per turn (a resend never repeats a line). */
    fun play(slot: String) {
        val a = action
        if (s.outbox.stageDone(a.turnId, "line:$slot")) return
        val line = nextLine(slot) ?: return
        if (add(a.utteranceId, line, slot)) {
            s = s.copy(outbox = s.outbox.markStage(a.turnId, "line:$slot"))
            updateAction { if (it.slot == null) it.copy(slot = slot, line = line, pieces = it.pieces + 1) else it.copy(pieces = it.pieces + 1) }
        }
    }

    /**
     * Stream the writer into the action's reply ([failSlot] plays if it fails with nothing said).
     * Skipped (done) if this turn's writer already spoke. Ends the handler step: return its [Next].
     */
    fun write(system: String, prompt: String, failSlot: String?): Next {
        val a = action
        if (s.outbox.stageDone(a.turnId, "writer")) return Next.Done
        updateAction { it.copy(writerRan = true) }
        val seq = stream(WriterOwner.Action(a.turnId), a.utteranceId, system, prompt, failSlot = failSlot)
        updateAction { it.copy(waitingOn = "writer:$seq") }
        return Next.Wait("writer")
    }

    /** Ask for one whole writer answer for the action; the result resumes it as `ActionResult.Wrote`. */
    fun requestWrite(system: String, prompt: String): Next {
        val seq = nextSeq()
        effects += Effect.Write(seq, system, prompt)
        updateAction { it.copy(waitingOn = "plan:$seq") }
        return Next.Wait("plan")
    }

    /** Read the server's busy mode for the action; resumes it as `ActionResult.Busy`. */
    fun readBusyMode(): Next {
        val seq = nextSeq()
        effects += Effect.ReadBusyMode(seq)
        updateAction { it.copy(waitingOn = "busy:$seq") }
        return Next.Wait("busy")
    }

    /** Start a writer stream into [utteranceId] for [owner]. Returns its seq. */
    fun stream(owner: WriterOwner, utteranceId: Long, system: String, prompt: String, lead: Pair<String, String>? = null, failSlot: String? = null): Long {
        val seq = nextSeq()
        s = s.copy(
            writers = s.writers + (seq to WriterJob(seq, utteranceId, owner, lead, failSlot = failSlot)),
            writer = WriterUi(streaming = true, sentences = emptyList()),
        )
        effects += Effect.StreamWriter(seq, system, prompt)
        return seq
    }

    // ── outbox ──────────────────────────────────────────────────────────────────────────────

    /** Hold a turn for the network (C1 `holdOffline`): the first hold while online says `error.offline`. */
    fun holdOffline(turnId: String, turn: HeldTurn): Boolean {
        val (box, held) = s.outbox.hold(turnId, turn)
        if (!held) return false
        s = s.copy(outbox = box)
        if (s.connection.online) {
            s = s.copy(connection = s.connection.copy(online = false))
            emit(CascadeEvent.Offline)
            say(AppLines.OFFLINE, SpeechKind.Reply)
        }
        report("cascade.outbox", mapOf("event" to "held", "size" to s.outbox.size))
        env.conversation.held(this, turnId, turn)
        return true
    }

    // ── tasks ───────────────────────────────────────────────────────────────────────────────

    /** Apply a task-table step: its effects, events, reports, then results and requests (to the hooks). */
    fun <R> tasks(step: TaskStep<R>): R {
        s = s.copy(tasks = step.table)
        effects += step.effects
        step.events.forEach(::emit)
        step.reports.forEach { (event, extra) ->
            report("cascade.task", mapOf("event" to event, "running" to (extra["running"] ?: step.table.runningCount)) + (extra - "running"), warn = event == "failed")
        }
        step.results.forEach { env.handlers.result.arrived(this, it) }
        step.requests.forEach { env.handlers.hooks.taskRequest(this, it) }
        return step.value
    }
}
