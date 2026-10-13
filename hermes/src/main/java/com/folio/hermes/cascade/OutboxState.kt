package com.folio.hermes.cascade

/**
 * A user turn held while offline (R-DUR-1, R-OFF). [text] is the full verbatim dictation.
 * [wallMs] and [transcript] are for memo sessions and the "dictated offline hh:mm" note (R-OFF-3/4).
 */
data class HeldTurn(
    val text: String,
    val speechEndAt: Long,
    val wallMs: Long = 0L,
    val transcript: Transcript? = null,
    /**
     * Not offline dictation: a turn carried over a hang-up or a restart. It is resent as it is,
     * never segmented or labelled "dictated offline" (R-OFF-4 applies to memo mode only).
     */
    val carried: Boolean = false,
)

/** One held turn, by turn id. */
data class OutboxEntry(val turnId: String, val turn: HeldTurn)

/**
 * User turns waiting on the network, by turn id, sent again on reconnect. A pure value owned by the actor.
 *
 * - [hold] keeps a turn once: the same id again, or an id already sent, is refused.
 * - Held turns are sent in order, one at a time ([first], then [markSent]); a sent turn is
 *   remembered, so a second reconnect never sends it twice.
 * - [markStage] / [stageDone] record which steps of a turn already happened (a stock line
 *   played, a task started), so a resend never repeats one: Bill does not say the same thing twice.
 */
data class OutboxState(
    val held: List<OutboxEntry> = emptyList(),
    /** Turn ids already sent, oldest first (at most [KEEP]). */
    val sent: List<String> = emptyList(),
    /** Steps done per turn id, in first-marked order. */
    val stages: Map<String, Set<String>> = emptyMap(),
) {
    val size: Int get() = held.size

    fun ids(): List<String> = held.map { it.turnId }

    fun first(): OutboxEntry? = held.firstOrNull()

    /** Hold [turnId]; false if it is already held or was already sent. */
    fun hold(turnId: String, turn: HeldTurn): Pair<OutboxState, Boolean> {
        if (held.any { it.turnId == turnId } || turnId in sent) return this to false
        return copy(held = held + OutboxEntry(turnId, turn)) to true
    }

    /** [turnId] went out: it leaves the outbox and is never held again. */
    fun markSent(turnId: String): OutboxState =
        copy(held = held.filter { it.turnId != turnId }, sent = (sent.filter { it != turnId } + turnId)).trim()

    fun markStage(turnId: String, stage: String): OutboxState =
        copy(stages = stages + (turnId to (stages[turnId].orEmpty() + stage))).trim()

    fun stageDone(turnId: String, stage: String): Boolean = stages[turnId]?.contains(stage) == true

    private fun trim(): OutboxState {
        val keptSent = if (sent.size > KEEP) sent.takeLast(KEEP) else sent
        val keptStages = if (stages.size > KEEP) {
            val heldIds = held.map { it.turnId }.toSet()
            val drop = stages.keys.filter { it !in heldIds }.take(stages.size - KEEP).toSet()
            stages.filterKeys { it !in drop }
        } else {
            stages
        }
        return if (keptSent === sent && keptStages === stages) this else copy(sent = keptSent, stages = keptStages)
    }

    companion object {
        const val KEEP = 200
    }
}
