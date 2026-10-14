package com.folio.hermes.cascade.store

import com.folio.hermes.cascade.ConversationState
import com.folio.hermes.cascade.RecentTurn
import com.folio.hermes.cascade.SpeechKind
import com.folio.hermes.cascade.State
import com.folio.hermes.cascade.Utterance

/** Putting a stored conversation back into a [State] (R-CONV-1). */
object Recent {
    /**
     * [state] in conversation [id] with [summary] and the [recent] exchanges. Bill's side of
     * `recent` is what the user heard of an utterance, so each exchange gets a closed, already
     * heard utterance in the speech queue's history (nothing plays).
     */
    fun seed(state: State, id: String, summary: String, recent: List<ExchangeDto>): State {
        val keep = recent.takeLast(state.settings.recentExchanges.coerceAtLeast(0))
        var speech = state.speech
        val turns = keep.map { e ->
            val u = Utterance(speech.nextId, SpeechKind.Reply, taskId = null, turnId = null, open = false, started = true, heard = e.bill)
            speech = speech.copy(done = (speech.done + u).takeLast(64), nextId = speech.nextId + 1)
            RecentTurn(e.user, u.id)
        }
        return state.copy(
            speech = speech,
            recent = turns,
            conversation = ConversationState(id = id, summary = summary, resume = null),
        )
    }
}
