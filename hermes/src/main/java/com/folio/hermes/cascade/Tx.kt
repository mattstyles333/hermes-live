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

    /** The line [slotId] plays next, without playing it: a slot with no pass under way gets its pass now (saved). */
    fun primeLine(slotId: String): String? {
        val lines = env.bank[slotId]?.lines?.takeIf { it.isNotEmpty() } ?: return null
        val current = s.rotation.rotations[slotId]
        val random = Random(s.rotation.seed)
        val primed = Rotations.prime(lines, current, random)
        if (primed != current) {
            s = s.copy(rotation = RotationState(s.rotation.rotations + (slotId to primed), random.nextLong()))
            effects += Effect.SaveRotation(slotId, primed)
        }
        return primed.order[primed.cursor]
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
        val request = env.handlers.hooks.ask(ctx, env.bank, s.settings, s)
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

    /** Open [gate] on queued turn [turnId] (R-STT-2 accurate pass, R-STT-5 key doubt); it starts once all its gates are open. */
    fun openGate(turnId: String, gate: String) {
        s = s.copy(actions = s.actions.copy(waiting = s.actions.waiting.map { if (it.turnId == turnId) it.copy(gates = it.gates - gate) else it }))
    }

    // ── marks (State.marks) ─────────────────────────────────────────────────────────────────

    /** Remember [value] under [key] (a turn id, `task:<id>`, a slice name). */
    fun mark(key: String, value: String) {
        val now = s.marks[key].orEmpty()
        if (value in now) return
        val next = (s.marks - key) + (key to (now + value))
        s = s.copy(marks = if (next.size > MAX_MARK_KEYS) next.entries.drop(next.size - MAX_MARK_KEYS).associate { it.key to it.value } else next)
    }

    fun marked(key: String, value: String): Boolean = s.marks[key]?.contains(value) == true

    /** Every mark under [key]. */
    fun marks(key: String): Set<String> = s.marks[key].orEmpty()

    /** Forget mark [value] under [key]. */
    fun unmark(key: String, value: String) {
        val now = s.marks[key] ?: return
        if (value !in now) return
        val left = now - value
        s = s.copy(marks = if (left.isEmpty()) s.marks - key else s.marks + (key to left))
    }

    // ── outbox ──────────────────────────────────────────────────────────────────────────────

    /**
     * Send update [text] to task [taskId] for user turn [turnId] (R-DUR-1/2, exactly once per
     * turn): the outbox stage `update:<taskId>` is marked in the same step, and a resend of the
     * turn finds it and skips. Null when this turn's update already went out (or no such task).
     */
    fun launchUpdate(taskId: String, text: String, kind: RunKind, turnId: String?): Long? {
        if (turnId != null && updateSent(turnId, taskId)) return null
        val runId = tasks(s.tasks.launch(taskId, text, voiceContext(), kind, s.wallMs, turnId)) ?: return null
        if (turnId != null) s = s.copy(outbox = s.outbox.markStage(turnId, updateStage(taskId)))
        return runId
    }

    /** True if turn [turnId]'s update to [taskId] already went out (this run or an earlier one). */
    fun updateSent(turnId: String, taskId: String): Boolean =
        s.outbox.stageDone(turnId, updateStage(taskId)) || s.tasks[taskId]?.runs?.values?.any { it.turnId == turnId } == true

    /** Restart flushing the outbox from its first held turn (R-OFF-4 routing calls this once it has replaced the held turns). */
    fun startFlush() {
        if (s.flush != null || s.outbox.size == 0) return
        s = s.copy(flush = FlushState(turnId = ""))
        flushNext()
    }

    /** Send the next held turn again: Jev decides afresh; steps that already happened are not repeated. */
    fun flushNext() {
        val f = s.flush ?: return
        val entry = s.outbox.first()
        if (entry == null) {
            endFlush()
            return
        }
        val seq = s.turn.jevSeq + 1
        val request = jevRequest(entry.turn.text, emptyList(), s.settings.pause, speaking = false)
        s = s.copy(turn = s.turn.copy(jevSeq = seq), flush = f.copy(turnId = entry.turnId, jevSeq = seq, request = request, startedAt = s.nowMs))
        effects += Effect.CallJev(seq, request, s.settings.jevTimeoutMs, early = false)
    }

    /** Held turn [turnId] was carried out: it leaves the outbox; the flush goes on. */
    fun flushSent(turnId: String) {
        val f = s.flush ?: return
        s = s.copy(outbox = s.outbox.markSent(turnId), flush = f.copy(sent = f.sent + 1, jevSeq = null))
        flushNext()
    }

    fun endFlush() {
        val f = s.flush ?: return
        s = s.copy(flush = null)
        if (f.sent > 0) report("cascade.outbox", mapOf("event" to "sent", "count" to f.sent, "size" to s.outbox.size))
    }

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
        // Spoken now only in a call with no Resume card up; otherwise they wait in `offCall` (R-CONV-2/3).
        if (s.started && s.conversation.resume == null) {
            step.results.forEach { env.handlers.result.arrived(this, it) }
            step.requests.forEach { env.handlers.hooks.taskRequest(this, it) }
        } else if (step.results.isNotEmpty() || step.requests.isNotEmpty()) {
            s = s.copy(offCall = OffCallState(s.offCall.results + step.results, s.offCall.requests + step.requests))
        }
        return step.value
    }

    companion object {
        /** Mark keys kept (least recently marked dropped first). */
        const val MAX_MARK_KEYS = 400

        fun updateStage(taskId: String): String = "update:$taskId"
    }
}
