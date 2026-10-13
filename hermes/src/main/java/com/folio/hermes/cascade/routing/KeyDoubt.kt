package com.folio.hermes.cascade.routing

import com.folio.hermes.cascade.ActionState
import com.folio.hermes.cascade.Decision
import com.folio.hermes.cascade.Route
import com.folio.hermes.cascade.Tx
import com.folio.hermes.cascade.writer.Repair
import com.folio.hermes.cascade.writer.WriterPrompts

/**
 * R-STT-5 `key_doubt`: a name, number or date the action depends on looks misheard. Bill asks one
 * targeted question (the writer, e.g. "Safilo or Sapphire?"), waits `keyDoubtWaitMs` for an
 * answer, then proceeds, with the answer or with notes for the agent.
 *
 * The action waits on a slice timer; the user's next committed turn is taken as the answer
 * ([absorb]): it is folded into the waiting action and fires the timer at once. "Scrap that"
 * instead drops the waiting action.
 */
object KeyDoubt {
    const val TIMER = "key_doubt"
    const val STATE = "doubt"
    const val ANSWER = "doubt_answer"
    const val ASKED = "asked"
    const val UNANSWERED = "unanswered"
    const val DROPPED = "dropped"

    /** True if [a] should ask before acting: Jev is sure of `key_doubt` and this is not offline dictation. */
    fun needed(tx: Tx, a: ActionState): Boolean {
        val clarity = a.record.answers[CascadeBrain.CLARITY] ?: return false
        return clarity.choice == CascadeBrain.KEY_DOUBT && clarity.confidence >= tx.s.settings.confidenceFloor &&
            a.data[STATE] == null && !MemoRouting.isMemo(tx, a.turnId)
    }

    /** Ask the question into the action's reply; resumes as `Written`. */
    fun ask(tx: Tx): Next {
        val a = tx.action
        tx.updateAction { it.copy(data = it.data + (STATE to ASKED)) }
        val t = BriefPlanner.transcript(tx, a)
        return tx.write(WriterPrompts.spoken(null, 1), prompt(a.text, Repair.marked(t), tx.s.settings.vocabulary), null)
    }

    fun prompt(text: String, marked: String, vocabulary: List<String>): String = buildString {
        append("The user just said: \"").append(text).append("\"\n")
        if (marked != text) append("Speech-to-text was unsure of the words in brackets: \"").append(marked).append("\"\n")
        if (vocabulary.isNotEmpty()) append("Names they use: ").append(vocabulary.joinToString(", ")).append('\n')
        append("A name, number or date that matters may be misheard. Ask ONE short question to check just that, ")
        append("e.g. \"Safilo or Sapphire?\" or \"Forty, or fourteen?\". Nothing else.")
    }

    /** After the question: wait for the answer (or go on at once if the question never played). */
    fun afterQuestion(tx: Tx, spoke: Boolean): Next? {
        if (!spoke) {
            tx.updateAction { it.copy(data = it.data + (STATE to UNANSWERED)) }
            return null
        }
        return tx.waitTimer(TIMER, tx.s.settings.keyDoubtWaitMs)
    }

    /** The wait ended: with the user's answer (folded in by [absorb]), dropped, or unanswered. */
    fun waited(tx: Tx) {
        if (tx.action.data[STATE] == ASKED) tx.updateAction { it.copy(data = it.data + (STATE to UNANSWERED)) }
    }

    /**
     * A turn was committed while an action waits on its key-doubt question: the turn is the answer.
     * Returns the action to queue instead (a silent no-op), or null if nothing waits.
     */
    fun absorb(tx: Tx, next: ActionState): ActionState? {
        val active = tx.s.actions.active ?: return null
        if (active.waitingOn?.startsWith("timer:$TIMER:") != true) return null
        val route = next.decision.route
        val update = if (route is Route.CancelTask || (route == Route.Clarify && next.decision.slot == "cancel.which")) {
            DROPPED to ""
        } else if (route == Route.EndCall) {
            return null
        } else {
            ANSWER to next.text
        }
        tx.updateAction {
            it.copy(data = it.data + if (update.first == DROPPED) mapOf(STATE to DROPPED) else mapOf(ANSWER to update.second, STATE to "answered"))
        }
        tx.fireWaitingTimer(TIMER)
        return next.copy(decision = Decision(Route.Reply, null, writer = false), data = next.data + (ABSORBED to active.turnId))
    }

    /** Marks a turn folded into another as its answer (its own action does nothing). */
    const val ABSORBED = "absorbed_by"
}
