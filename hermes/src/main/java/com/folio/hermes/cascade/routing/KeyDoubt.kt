package com.folio.hermes.cascade.routing

import com.folio.hermes.cascade.ActionState
import com.folio.hermes.cascade.AppLines
import com.folio.hermes.cascade.Decision
import com.folio.hermes.cascade.Route
import com.folio.hermes.cascade.TaskState
import com.folio.hermes.cascade.Tx
import com.folio.hermes.cascade.UpdateMode
import com.folio.hermes.cascade.writer.BriefTemplate
import com.folio.hermes.cascade.writer.WrittenDoubt

/**
 * R-STT-5 `key_doubt` never blocks (hub decision 2026-10-14): a name, number or date the work
 * depends on may be misheard, but the task starts at once with the Transcript / As heard /
 * Clarity / Notes block (whose brief already says to ask before any external action that depends
 * on an uncertain word). Then Bill asks one short question ("Kestrel, right?"):
 * - only when Jev's `key_doubt` is at `keyDoubtAskFloor` (0.6) and the word the writer names
 *   ([WrittenDoubt]) is not in the user's vocabulary or an earlier task's label; otherwise the
 *   notes go to the agent silently;
 * - the user's next committed turn, within `keyDoubtWaitMs` of the question, is its answer
 *   ([answer]): a correction becomes a redirect `update_task` to the task (seconds old, so the
 *   R-RT-7 window holds; a task already finished takes it as a follow-up), a confirmation becomes
 *   nothing more than "Got it." (a queued note would make Hermes answer it as a second result),
 *   and no answer leaves the task as it is.
 *
 * The question waits in [State.marks] (durable, wall clock), one at a time, and the next
 * committed turn clears it whether or not it was the answer.
 */
object KeyDoubt {
    /** The marks key for the question waiting for its answer. */
    const val MARK = "key_doubt"

    /** On the answering turn: the question and the word it was about. */
    const val QUESTION = "doubt_question"
    const val WORD = "doubt_word"

    /** Marks a turn taken as the answer to a question (a confirmation does nothing more). */
    const val ABSORBED = "absorbed_by"

    /** What Bill says to a confirmation and (after `Accepted`) to a correction: stock lines, no writer. */
    const val CONFIRMED_SLOT = "ack.understood"
    const val CORRECTED_SLOT = "update.redirect"

    /** A turn of at most this many words may be an answer even without "yes", "no" or the word itself. */
    const val SHORT_ANSWER = 5

    /** Jev is sure enough of `key_doubt` to ask (and this is not offline dictation). */
    fun sure(tx: Tx, a: ActionState): Boolean {
        val clarity = a.record.answers[CascadeBrain.CLARITY] ?: return false
        return clarity.choice == CascadeBrain.KEY_DOUBT && clarity.confidence >= tx.s.settings.keyDoubtAskFloor &&
            !MemoRouting.isMemo(tx, a.turnId)
    }

    /**
     * The doubt to ask about once [a]'s tasks start, or null (and why, in telemetry: never the
     * word). [doubt] is the writer's; the vocabulary and the labels of tasks from earlier turns
     * count as known words.
     */
    fun toAsk(tx: Tx, a: ActionState, doubt: WrittenDoubt?): WrittenDoubt? {
        val clarity = a.record.answers[CascadeBrain.CLARITY]
        if (clarity?.choice != CascadeBrain.KEY_DOUBT || MemoRouting.isMemo(tx, a.turnId)) return null
        val labels = tx.s.tasks.tasks.filter { it.turnId != a.turnId }.map { it.label }
        val why = when {
            !sure(tx, a) -> "low_confidence"
            doubt == null -> "no_word"
            known(doubt.word, tx.s.settings.vocabulary + labels) -> "known_word"
            else -> null
        }
        tx.report("cascade.key_doubt", mapOf("event" to if (why == null) "ask" else "silent", "why" to (why ?: "asked")))
        return if (why == null) doubt else null
    }

    /** The line the brief's notes carry when Bill will ask. */
    fun note(d: WrittenDoubt): String = "The voice assistant is checking \"${d.word}\" with the user; a correction may follow."

    /** Ask [d] into [a]'s reply, after the start line, and wait (durably) for the answer to one of [taskIds]. */
    fun ask(tx: Tx, a: ActionState, d: WrittenDoubt, taskIds: List<String>) {
        if (taskIds.isEmpty()) return
        val taskId = taskIds.firstOrNull { id ->
            tx.s.tasks[id]?.brief?.substringBefore(BriefTemplate.REPORT_BACK)?.contains(d.word, ignoreCase = true) == true
        } ?: taskIds.first()
        val question = question(d)
        if (tx.add(a.utteranceId, question)) tx.updateAction { it.copy(pieces = it.pieces + 1) }
        clear(tx)
        tx.mark(MARK, Pending(a.turnId, tx.s.wallMs, taskId, d.word, question).encode())
    }

    /** The writer's question, kept short; "<word>, right?" when it gave none. */
    fun question(d: WrittenDoubt): String {
        val words = d.question.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty() || words.size > 10) return "${d.word}, right?"
        val q = words.joinToString(" ")
        return if (q.endsWith("?")) q else q.trimEnd('.', '!', ',') + "?"
    }

    /**
     * A turn was committed: if a question waits (and is still fresh), take [next] as its answer.
     * Cancels, goodbyes, status and read-outs are never answers; a turn is one when it leads with
     * yes or no, names the doubted word, or is a short reply.
     */
    fun answer(tx: Tx, next: ActionState): ActionState {
        val p = pending(tx) ?: return next
        clear(tx)
        if (p.turnId == next.turnId || tx.s.wallMs - p.askedAt > tx.s.settings.keyDoubtWaitMs) return next
        // A fast task may already be done when the answer comes: a yes still sends nothing, and a
        // correction still reaches it (as a follow-up). Only a cancelled task ("scrap that") lets the turn route on its own.
        val task = tx.s.tasks[p.taskId]?.takeIf { it.state != TaskState.Cancelled } ?: return next
        if (!answerLike(p, next)) return next
        val confirmed = confirms(p.word, next.text)
        // "Yes, and also email Bob": a yes with a new request after it is that request.
        if (!confirmed && tokens(next.text).first() in YES && next.decision.route == Route.NewTask) return next
        val data = next.data + (QUESTION to p.question) + (WORD to p.word) + (ABSORBED to p.turnId)
        return if (confirmed) {
            tx.report("cascade.key_doubt", mapOf("event" to "confirmed"))
            next.copy(decision = Decision(Route.Reply, CONFIRMED_SLOT, writer = false), data = data, record = next.record.copy(route = Route.Reply.wire))
        } else {
            tx.report("cascade.key_doubt", mapOf("event" to "corrected"))
            val running = task.state == TaskState.Running
            val route = Route.UpdateTask(task.id, if (running) UpdateMode.Redirect else UpdateMode.Queue)
            val slot = if (running) CORRECTED_SLOT else "update.queued"
            next.copy(decision = Decision(route, slot, writer = false), data = data, record = next.record.copy(route = route.wire))
        }
    }

    /** The update text for a correction: what Bill asked and what the user said. */
    fun correction(a: ActionState): String? {
        val question = a.data[QUESTION] ?: return null
        return "Correction: I asked the user \"$question\" about \"${a.data[WORD].orEmpty()}\" in the request, and they answered: \"${a.text}\". " +
            "Use their answer for that word."
    }

    fun answerLike(p: Pending, next: ActionState): Boolean {
        val route = next.decision.route
        if (route is Route.CancelTask || route == Route.EndCall || route == Route.AskRepeat || route is Route.Status || route is Route.ReadResult) return false
        if (route == Route.Clarify && next.decision.slot == AppLines.WHICH_CANCEL) return false
        val words = tokens(next.text)
        if (words.isEmpty()) return false
        if (words.first() in YES || words.first() in NO) return true
        if (tokens(p.word).let { w -> w.isNotEmpty() && words.containsAll(w) }) return true
        val replyLike = route == Route.Reply || route == Route.Clarify || (route is Route.UpdateTask && route.taskId == p.taskId)
        return replyLike && words.size <= SHORT_ANSWER
    }

    /** "Yes", "that's right", "Kestrel, yeah": only yes words, filler and the doubted word itself. */
    fun confirms(word: String, text: String): Boolean {
        val words = tokens(text)
        if (words.isEmpty() || words.first() in NO) return false
        val doubted = tokens(word).toSet()
        if (words.none { it in YES || it in doubted }) return false
        return words.all { it in YES || it in FILLER || it in doubted }
    }

    /** [word] is one of [known] (a vocabulary entry or a label), or every token of it is in them. */
    fun known(word: String, known: List<String>): Boolean {
        val w = tokens(word)
        if (w.isEmpty()) return true
        val joined = w.joinToString("")
        if (known.any { tokens(it).joinToString("") == joined }) return true
        val all = known.flatMap(::tokens).toSet()
        return w.all { it in all }
    }

    private fun tokens(text: String): List<String> =
        text.lowercase().replace('’', '\'').split(Regex("[^\\p{L}\\p{N}']+")).map { it.trim('\'') }.filter { it.isNotEmpty() }

    private val YES = setOf(
        "yes", "yeah", "yep", "yup", "yah", "aye", "correct", "right", "exactly", "indeed", "sure", "affirmative",
        "absolutely", "definitely", "ok", "okay", "mhm", "spot", "perfect", "that's", "thats",
    )
    private val NO = setOf("no", "nope", "nah", "not", "wrong", "actually", "sorry")
    private val FILLER = setOf(
        "it's", "its", "it", "is", "that", "the", "one", "on", "bill", "cheers", "thanks", "please", "good", "great",
        "lovely", "uh", "huh", "mm", "yes", "so", "well", "i", "said", "did", "do", "you", "got",
    )

    private fun pending(tx: Tx): Pending? = tx.marks(MARK).firstNotNullOfOrNull(Pending::decode)

    private fun clear(tx: Tx) {
        tx.marks(MARK).forEach { tx.unmark(MARK, it) }
    }

    /** The question waiting for its answer, as one mark value. */
    data class Pending(val turnId: String, val askedAt: Long, val taskId: String, val word: String, val question: String) {
        fun encode(): String = listOf(turnId, askedAt.toString(), taskId, word, question).joinToString(SEP)

        companion object {
            private const val SEP = "\u001f"

            fun decode(s: String): Pending? {
                val p = s.split(SEP)
                if (p.size != 5) return null
                return Pending(p[0], p[1].toLongOrNull() ?: return null, p[2], p[3], p[4])
            }
        }
    }
}
