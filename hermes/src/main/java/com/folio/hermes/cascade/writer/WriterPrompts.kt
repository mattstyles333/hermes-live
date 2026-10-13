package com.folio.hermes.cascade.writer

import com.folio.hermes.cascade.Exchange
import com.folio.hermes.cascade.TaskBrief
import com.folio.hermes.cascade.TaskSnapshot
import com.folio.hermes.cascade.UpdateMode

/** A new task as the writer planned it. */
data class TaskPlan(val label: String, val prompt: String, val say: String)

/**
 * What the writer is asked. Every spoken line: plain speech, short sentences, no markdown. When
 * no stock line played first, the reply opens with a 1–4 word lead ("On it.", "Sure.", "Right,")
 * that [com.folio.hermes.openrouter.SentenceSplitter] sends as its own TTS request, so first audio
 * is short. When a stock line did play, the writer carries on after it without another lead.
 */
object WriterPrompts {
    private const val VOICE =
        "You are Bill, the voice of the user's own assistant, speaking on a phone call. Write only what Bill says: " +
            "plain spoken English, short sentences, warm, dry and brief. No markdown, lists, emoji, URLs or stage directions."

    /** System prompt for a spoken line. [said] is the stock line already played, if any. */
    fun spoken(said: String?, maxSentences: Int = 3): String = buildString {
        append(VOICE)
        append(" At most $maxSentences sentences.")
        if (said == null) {
            append(" Open with a lead of one to four words as its own sentence or phrase, such as \"On it.\", \"Sure.\" or \"Right,\".")
        } else {
            append(" Bill has just said \"$said\". Carry straight on from it: no opening lead, and do not repeat it.")
        }
    }

    fun reply(text: String, recent: List<Exchange>, tasks: List<TaskBrief>): String = buildString {
        context(recent, tasks)
        append("The user just said: \"").append(text).append("\"\nReply to them.")
    }

    fun clarify(text: String, recent: List<Exchange>, tasks: List<TaskBrief>): String = buildString {
        context(recent, tasks)
        append("The user just said: \"").append(text).append("\"\n")
        append("It is not clear what they want. Ask one short question to find out. Do not start anything.")
    }

    const val PLAN_SYSTEM =
        "You turn a spoken request into a task for an AI agent with tools. Answer in exactly three lines:\n" +
            "LABEL: a 2 to 4 word name for the task, e.g. \"stock check\"\n" +
            "PROMPT: a clear, complete instruction for the agent, in the second person, with every detail the user gave\n" +
            "SAY: one short spoken sentence for Bill confirming what he started, no lead word"

    fun plan(text: String, recent: List<Exchange>): String = buildString {
        if (recent.isNotEmpty()) {
            append("Recent conversation:\n")
            recent.forEach { append("User: ").append(it.user).append("\nBill: ").append(it.bill).append('\n') }
        }
        append("The request: \"").append(text).append('"')
    }

    /** Parse [PLAN_SYSTEM]'s three lines; anything missing falls back to the request itself. */
    fun parsePlan(raw: String?, text: String): TaskPlan {
        fun field(name: String) = raw?.lineSequence()
            ?.map { it.trim().removePrefix("**").replace("**", "") }
            ?.firstOrNull { it.startsWith("$name:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.trim('"')?.takeIf { it.isNotEmpty() }
        val label = field("LABEL")?.split(' ')?.filter { it.isNotEmpty() }?.take(4)?.joinToString(" ") ?: fallbackLabel(text)
        return TaskPlan(label = label, prompt = field("PROMPT") ?: text, say = field("SAY").orEmpty())
    }

    /** The first three words of the request (when the writer could not label it). */
    fun fallbackLabel(text: String): String =
        text.split(' ').map { it.trim(',', '.', '?', '!') }.filter { it.isNotEmpty() }.take(3).joinToString(" ").ifEmpty { "task" }

    fun status(tasks: List<TaskSnapshot>, focus: String?): String = buildString {
        append("Say how the user's tasks are getting on, from this log only (do not invent progress).\n")
        tasks.filter { focus == null || it.id == focus }.forEach { t ->
            append("- ").append(t.label).append(": ").append(t.state.wire)
            append(", ").append(t.steps).append(" steps")
            t.currentTool?.let { append(", now using ").append(it) }
            if (t.waitingOnUser) append(", waiting on the user")
            val tail = t.progress.takeLast(300).trim()
            if (tail.isNotEmpty()) append(", latest text: \"").append(tail).append('"')
            append('\n')
        }
    }

    fun result(label: String, text: String): String =
        "The task \"$label\" finished. Its result is below. Say it for the ear in at most three sentences, " +
            "starting with a lead-in that names it (\"The $label's back.\"). If it is long, give the gist and say " +
            "the full answer is on screen.\n\nResult:\n${text.take(6_000)}"

    fun updated(label: String, mode: UpdateMode): String =
        "Bill just passed the user's message to the task \"$label\" (${if (mode == UpdateMode.Queue) "after its current step" else "changing what it is doing now"}). Confirm it in one short sentence."

    fun cancelled(label: String): String = "Bill just stopped the task \"$label\". Confirm it in one short sentence."

    private fun StringBuilder.context(recent: List<Exchange>, tasks: List<TaskBrief>) {
        if (recent.isNotEmpty()) {
            append("Recent conversation:\n")
            recent.forEach { append("User: ").append(it.user).append("\nBill: ").append(it.bill).append('\n') }
        }
        if (tasks.isNotEmpty()) {
            append("Tasks:\n")
            tasks.forEach { append("- ").append(it.label).append(" (").append(it.state.wire).append(", ").append(it.lastEvent).append(")\n") }
        }
    }
}
