package com.folio.hermes.cascade.conversation

import com.folio.hermes.cascade.HeldTurn
import com.folio.hermes.cascade.ResumeCandidate
import com.folio.hermes.cascade.ResumeChoice
import com.folio.hermes.cascade.State
import com.folio.hermes.cascade.Tx
import com.folio.hermes.cascade.ports.Store

/**
 * Conversations, memo mode and resume (R-CONV-1/2/4, R-OFF-3/4; S-B owns this package).
 *
 * - [held]: a turn went to the outbox; group it into a dictation session (`State.ceo.memoSessions`,
 *   gaps under `memoGapMs`, capped at `memoCapMs`).
 * - [offline]: the network dropped during a call (memo mode, R-OFF-1).
 * - [online]: the network came back with held turns; return true to take over sending them
 *   (writer segments, Jev routes, briefs note "dictated offline hh:mm–hh:mm", one summary line).
 *   False keeps C1's outbox flush (each held turn through Jev in order).
 * - [resumeCandidate]: what the Resume card offers when the app opens (reads the [Store]; not in `reduce`).
 * - [briefing]: the opening briefing's items when a call starts with something waiting.
 * - [resume]: the Resume card's choice (Play, Continue, New); [play]: ▶ on a task card.
 *
 * Interface only in S0; [None] keeps C1's behaviour.
 */
interface ConversationPolicy {
    fun held(tx: Tx, turnId: String, turn: HeldTurn) = Unit

    fun online(tx: Tx): Boolean = false

    /** The network dropped during a call: memo mode starts (R-OFF-1: banner, `error.offline` once). Default: nothing (C1). */
    fun offline(tx: Tx) = Unit

    fun resumeCandidate(store: Store, state: State): ResumeCandidate? = null

    fun briefing(tx: Tx) = Unit

    /** The user chose on the Resume card (R-CONV-2). */
    fun resume(tx: Tx, choice: ResumeChoice) = Unit

    /** ▶ on a finished task card: its spoken briefing, mic off (R-CONV-2). */
    fun play(tx: Tx, taskId: String) = Unit

    companion object {
        val None: ConversationPolicy = object : ConversationPolicy {}
    }
}
