package com.folio.hermes.live

import com.folio.hermes.cascade.CascadeCore
import com.folio.hermes.cascade.CascadeTasks
import com.folio.hermes.cascade.TaskNotice
import com.folio.hermes.cascade.TaskReply
import com.folio.hermes.cascade.TaskView
import com.folio.hermes.cascade.UpdateMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/*
 * GPT-Live parallel agents (gptlive-parallel slice, 2026-10-14). GPT-Live has no custom tools:
 * delegation is server-defined (`session.delegation.created`, no text). With agents on, each
 * delegation becomes its own durable task on the shared task API (CascadeTasks), so several run
 * at once, survive a dropped line and a hang-up, show on the task tray and notify. These are the
 * ports [AgentDesk] needs; `:live` adapts Bill's process runtime to them.
 */

/** Where [DelegationBridge] hands delegations when parallel agents are on ([AgentDesk] implements it). */
interface DelegationAgents {
    /** A delegation ([delegationId] null: a typed request) with its prompt rebuilt from the transcript. */
    fun onDelegation(delegationId: String?, prompt: DelegationPrompt)

    /** A delegation is being handled or a task is still running (no idle hang-up, music may play). */
    val working: Boolean
}

/** The live voice as [AgentDesk] talks to it (the current session's [DelegationBridge]). */
interface AgentVoice {
    /** Spoken: `session.commentary.append` on [delegationId], or an instruction to say it when null. */
    fun say(delegationId: String?, text: String)

    /** Quiet progress (`session.thinking.append`) on a delegation of this session. */
    fun think(delegationId: String, text: String)

    /** `session.instructions.append`. */
    fun instruct(text: String)
}

/** The task side GPT-Live drives: the shared task API ([CascadeTasks]) on Bill's process runtime. */
interface DelegationTasks {
    suspend fun start(brief: String, label: String, key: String): TaskReply
    suspend fun update(taskId: String, text: String, mode: UpdateMode, key: String): TaskReply

    /** [taskId] or [CascadeTasks.ALL]. */
    suspend fun cancel(taskId: String, key: String): TaskReply

    /** The user was told [taskId]'s result or request: read, never replayed later. */
    suspend fun heard(taskId: String)

    /** Every task, oldest first, from the phone's event log only (never Hermes). */
    fun status(): List<TaskView>

    /** Task [taskId]'s whole result (no side effect), or null. */
    fun result(taskId: String): String?

    /** What the user was not told yet (after the store is read back). */
    suspend fun unheard(): List<TaskNotice>

    /** Results, requests and withdrawals as they happen. */
    val notices: Flow<TaskNotice>

    /** The task table whenever it changes (tool steps, states). */
    val views: Flow<List<TaskView>>

    companion object {
        /** The real thing: [core]'s task API and actor state. */
        fun of(core: CascadeCore): DelegationTasks = object : DelegationTasks {
            override suspend fun start(brief: String, label: String, key: String) = core.tasks.startTask(brief, label, null, key)
            override suspend fun update(taskId: String, text: String, mode: UpdateMode, key: String) = core.tasks.updateTask(taskId, text, mode, key)
            override suspend fun cancel(taskId: String, key: String) = core.tasks.cancelTask(taskId, key)
            override suspend fun heard(taskId: String) {
                core.tasks.heard(taskId)
            }
            override fun status(): List<TaskView> = core.tasks.status()
            override fun result(taskId: String): String? = core.actor.state.value.tasks[taskId]?.result
            override suspend fun unheard(): List<TaskNotice> = core.tasks.unheard()
            override val notices: Flow<TaskNotice> = core.tasks.notices
            override val views: Flow<List<TaskView>> = core.actor.state.map { CascadeTasks.views(it) }.distinctUntilChanged()
        }
    }
}

/** Names a task for its card (2–4 words). */
fun interface TaskLabeler {
    suspend fun label(prompt: DelegationPrompt): String
}

/** Plain-words rules for labels and briefs (no model). */
object AgentText {
    private val WHITESPACE = Regex("\\s+")
    private val LEAD = Regex(
        "^(?:(?:hey|ok|okay|hi|so|right|um|uh|erm|well|and|also|hermes|please|then)[,.!]?\\s+)*" +
            "(?:(?:can|could|would|will) you(?: please)?|i (?:need|want|would like|'d like) you to|i need|i want|please|go and|let's|lets)?\\s*",
        RegexOption.IGNORE_CASE,
    )
    private val STOP = setOf("a", "an", "the", "me", "for", "to", "of", "on", "in", "my", "our", "us", "it", "what", "whether", "if", "how", "is", "are", "and", "with", "about")

    /** The heuristic label: the request's first content words after the polite lead-in, capitalised. */
    fun heuristicLabel(text: String): String {
        val clean = text.replace(WHITESPACE, " ").trim().replace(LEAD, "")
        val words = clean.split(' ').map { it.trim(',', '.', '?', '!', '"', '\'', ':', ';') }.filter { it.isNotEmpty() }
        val picked = buildList {
            for (w in words) {
                if (size >= 3) break
                if (isEmpty() || w.lowercase() !in STOP) add(w)
            }
        }
        val label = picked.joinToString(" ").ifEmpty { return "Task" }
        return label.replaceFirstChar { it.uppercaseChar() }
    }

    /** A writer's answer as a label: 2–4 words, no quotes or full stop; null when unusable. */
    fun cleanLabel(raw: String?): String? {
        val line = raw?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() } ?: return null
        val words = line.removePrefix("LABEL:").removePrefix("Label:").trim().trim('"', '\'', '.', '*').split(WHITESPACE).filter { it.isNotEmpty() }
        if (words.isEmpty() || words.size > 6) return null
        return words.take(4).joinToString(" ").replaceFirstChar { it.uppercaseChar() }.take(MAX_LABEL_CHARS)
    }

    /**
     * The agent brief for a delegation: what the user last said, then the recent spoken
     * conversation (bounded) so "check that one" still makes sense to a fresh agent session.
     */
    fun brief(prompt: DelegationPrompt): String {
        val request = prompt.prompt.trim()
        val context = prompt.context.trim()
        if (context.isEmpty() || context == "User: $request") return request
        return "$request\n\n[From a live voice call. Recent spoken conversation, newest last:\n${context.takeLast(MAX_CONTEXT_CHARS)}]"
    }

    /** A result as the voice should get it: speakable, at most [EXCERPT_CHARS]. */
    fun excerpt(text: String): String {
        val speakable = Commentary.speakable(text)
        return if (speakable.length <= EXCERPT_CHARS) speakable else speakable.take(EXCERPT_CHARS).trimEnd() + "…"
    }

    const val MAX_LABEL_CHARS = 40
    const val MAX_CONTEXT_CHARS = 2_000
    const val EXCERPT_CHARS = 600
}

/** The label from the writer model (DeepSeek Flash, the cascade's writer) within [timeoutMs], else [AgentText.heuristicLabel]. */
class WriterLabeler(
    private val write: suspend (system: String, prompt: String) -> String?,
    private val timeoutMs: Long = 2_500L,
) : TaskLabeler {
    override suspend fun label(prompt: DelegationPrompt): String {
        val written = runCatching {
            kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { write(SYSTEM, "The request: \"${prompt.prompt.take(600)}\"") }
        }.getOrNull()
        return AgentText.cleanLabel(written) ?: AgentText.heuristicLabel(prompt.prompt)
    }

    companion object {
        const val SYSTEM =
            "Name the task an AI agent is about to do for this spoken request, in 2 to 4 words, e.g. \"Stock check\" or \"Safilo reorder email\". " +
                "Answer with the name only."
    }
}
