package com.folio.hermes.cascade.conversation

import com.folio.hermes.cascade.AppLines
import com.folio.hermes.cascade.CascadeEvent
import com.folio.hermes.cascade.ConversationState
import com.folio.hermes.cascade.Effect
import com.folio.hermes.cascade.Exchange
import com.folio.hermes.cascade.StoredConversation
import com.folio.hermes.cascade.HeldTurn
import com.folio.hermes.cascade.ResumeCandidate
import com.folio.hermes.cascade.ResumeChoice
import com.folio.hermes.cascade.SendState
import com.folio.hermes.cascade.SpeechKind
import com.folio.hermes.cascade.State
import com.folio.hermes.cascade.TaskResult
import com.folio.hermes.cascade.TaskState
import com.folio.hermes.cascade.Tx
import com.folio.hermes.cascade.ports.Store
import com.folio.hermes.cascade.store.ConversationDto
import com.folio.hermes.cascade.store.ExchangeDto
import com.folio.hermes.cascade.store.Recent
import com.folio.hermes.cascade.store.Snapshots
import com.folio.hermes.cascade.store.StoreCodec
import com.folio.hermes.cascade.store.StoreKeys

/**
 * Conversations, resume, briefing and memo mode (R-CONV-1/2/4, R-OFF-1 cascade side, R-OFF-3,
 * R-OFF-4 storage). The conversation itself is stored by
 * [com.folio.hermes.cascade.store.DurablePersistence]; this class holds the rules.
 *
 * - **Memo mode:** the network dropping during a call turns memo mode on ([CascadeEvent.Memo],
 *   `error.offline` once); every held turn joins a dictation session ([MemoQueue]). Back online,
 *   [reconnect] (S-A) gets the sessions in order; without it C1's flush sends each held turn.
 * - **Resume card (R-CONV-2):** offered when the last conversation has something in it; the
 *   default is Continue if the last call was within `continueWithinMs`, else New.
 *   - Continue keeps `recent` and the summary.
 *   - New stores the old conversation in the history and starts a fresh one: `recent` and the
 *     summary are cleared; tasks stay and can still be asked about, updated, cancelled and retried.
 *   - Play reads the briefing and queues every unheard result (the app keeps the mic off).
 * - **Briefing (R-CONV-4):** at call start, when something is waiting, at most `briefingItems`
 *   items; spoken at once with no Resume card, else after the choice.
 */
class DurableConversations(private val reconnect: ReconnectRouter? = null) : ConversationPolicy {
    override fun held(tx: Tx, turnId: String, turn: HeldTurn) {
        tx.s = tx.s.copy(ceo = tx.s.ceo.copy(memoSessions = MemoQueue.group(tx.s.ceo.memoSessions, turnId, turn, tx.s.settings)))
        tx.emit(CascadeEvent.TurnHeld(turnId))
    }

    override fun offline(tx: Tx) {
        if (tx.s.connection.memo) return
        tx.s = tx.s.copy(connection = tx.s.connection.copy(memo = true))
        tx.emit(CascadeEvent.Memo(true))
        if (tx.s.started) tx.say(AppLines.OFFLINE, SpeechKind.Notice)
    }

    override fun online(tx: Tx): Boolean {
        tx.s = MemoQueue.prune(tx.s)
        if (tx.s.connection.memo) {
            tx.s = tx.s.copy(connection = tx.s.connection.copy(memo = false))
            tx.emit(CascadeEvent.Memo(false))
        }
        return reconnect?.route(tx, MemoQueue.batches(tx.s)) ?: false
    }

    override fun resumeCandidate(store: Store, state: State): ResumeCandidate? = candidate(store, state)

    override fun briefing(tx: Tx) {
        val items = Briefing.items(tx.s)
        if (tx.s.conversation.resume == null) Briefing.speak(tx, items) else tx.s = tx.s.copy(ceo = tx.s.ceo.copy(briefing = items))
    }

    override fun resume(tx: Tx, choice: ResumeChoice) {
        tx.s = tx.s.copy(conversation = tx.s.conversation.copy(resume = null))
        if (choice == ResumeChoice.New) newConversation(tx)
        Briefing.speak(tx, Briefing.items(tx.s))
        if (choice == ResumeChoice.Play) unheard(tx)
    }

    override fun play(tx: Tx, taskId: String) {
        val t = tx.s.tasks[taskId] ?: return
        tx.s.resultUtterances[taskId]?.let { u ->
            tx.drop(u)
            tx.s = tx.s.copy(resultUtterances = tx.s.resultUtterances - taskId)
        }
        when {
            t.result != null -> tx.env.handlers.result.arrived(tx, TaskResult(t.id, t.label, t.result!!, ok = true))
            t.state == TaskState.Failed || t.send == SendState.NotSent -> tx.env.handlers.result.arrived(tx, TaskResult(t.id, t.label, "", ok = false))
            else -> tx.say("status.working")
        }
    }

    override fun load(store: Store, conversationId: String): StoredConversation? {
        val dto = StoreCodec.decode(ConversationDto.serializer(), store.get(StoreKeys.conversation(conversationId))) ?: return null
        return StoredConversation(dto.id, dto.summary, dto.recent.map { Exchange(it.user, it.bill) })
    }

    /**
     * Reopen a past conversation (R-CONV-1): the current one goes to the history (as New does),
     * the past one comes back as current with its `recent` and summary, and leaves the history
     * list. Tasks are the user's and stay. Opening the current one just closes the Resume card.
     */
    override fun open(tx: Tx, loaded: StoredConversation?) {
        tx.s = tx.s.copy(conversation = tx.s.conversation.copy(resume = null))
        if (loaded == null || loaded.id == tx.s.conversation.id) return
        Snapshots.conversation(tx.s, endedWallMs = tx.s.wallMs)?.let { old ->
            tx.effects += Effect.Persist(StoreKeys.conversation(old.id), StoreCodec.encode(ConversationDto.serializer(), old))
        }
        tx.effects += Effect.Persist(StoreKeys.conversation(loaded.id), null)
        tx.s = Recent.seed(tx.s, loaded.id, loaded.summary, loaded.recent.map { ExchangeDto(it.user, it.bill) }).copy(ceo = tx.s.ceo.copy(briefing = emptyList()))
        tx.report("cascade.conversation", mapOf("event" to "reopened", "exchanges" to tx.s.recent.size))
    }

    /** Every unheard result not already queued, oldest first. */
    private fun unheard(tx: Tx) {
        tx.s.tasks.tasks.filter { it.unread && it.result != null && it.id !in tx.s.resultUtterances }
            .forEach { tx.env.handlers.result.arrived(tx, TaskResult(it.id, it.label, it.result!!, ok = true)) }
    }

    companion object {
        /** New: the current conversation goes to the history; a fresh one starts with no `recent` and no summary. */
        fun newConversation(tx: Tx) {
            Snapshots.conversation(tx.s, endedWallMs = tx.s.wallMs)?.let { old ->
                tx.effects += Effect.Persist(StoreKeys.conversation(old.id), StoreCodec.encode(ConversationDto.serializer(), old))
            }
            val id = Snapshots.conversationId(tx.s.wallMs, avoid = tx.s.conversation.id)
            tx.s = tx.s.copy(conversation = ConversationState(id = id), recent = emptyList(), ceo = tx.s.ceo.copy(briefing = emptyList()))
        }

        /** The Resume card for [state] (R-CONV-2), or null when there is nothing to come back to. */
        fun candidate(store: Store, state: State): ResumeCandidate? {
            val id = state.conversation.id ?: return null
            val last = listOfNotNull(store.get(StoreKeys.CALL_START)?.toLongOrNull(), store.get(StoreKeys.CALL_END)?.toLongOrNull()).maxOrNull()
                ?: return null
            val tasks = state.tasks.tasks
            val unheard = tasks.count { it.unread }
            val held = state.outbox.size
            val waiting = tasks.count { it.state == TaskState.Running || (it.send == SendState.NotSent) }
            val something = state.recent.isNotEmpty() || state.conversation.summary.isNotEmpty() || unheard + held + waiting > 0
            if (!something) return null
            val within = state.wallMs - last <= state.settings.continueWithinMs
            return ResumeCandidate(id, last, unheard, held, waiting, if (within) ResumeChoice.Continue else ResumeChoice.New)
        }
    }
}
