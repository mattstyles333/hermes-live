package com.folio.hermes.live

import com.folio.hermes.cascade.CascadeTasks
import com.folio.hermes.cascade.TaskBrief
import com.folio.hermes.cascade.TaskState
import com.folio.hermes.cascade.TaskView
import com.folio.hermes.cascade.TurnContext
import com.folio.hermes.cascade.UpdateMode
import com.folio.hermes.cascade.routing.CascadeBrain
import com.folio.hermes.cascade.routing.JevQuestions
import com.folio.hermes.openrouter.JevAnswer
import com.folio.hermes.openrouter.JevQuestion
import com.folio.hermes.openrouter.OpenRouterResult
import kotlinx.serialization.json.JsonObject

/** What a GPT-Live delegation asks of the agents. */
sealed interface AgentIntent {
    /** Real work: a new task. */
    data object New : AgentIntent

    /** "How's the stock check going?" ([taskId] null: all of them). Read from the phone, never Hermes. */
    data class Status(val taskId: String?) : AgentIntent

    /** Stop [taskId], or every task with [CascadeTasks.ALL]. */
    data class Cancel(val taskId: String) : AgentIntent

    /** New information for a running task. */
    data class Update(val taskId: String, val mode: UpdateMode) : AgentIntent

    /** Read a finished task's result again. */
    data class Read(val taskId: String) : AgentIntent

    /** The request names no task clearly: ask [question]. */
    data class Ask(val question: String) : AgentIntent
}

/**
 * Classifies a delegation's rebuilt prompt against the open tasks. The model delegates "cancel
 * the stock check" and "how's it going" like any request (the session note asks it to), and this
 * decides on the phone what to do, so Hermes's routing never changes.
 */
fun interface AgentIntents {
    suspend fun classify(prompt: DelegationPrompt, tasks: List<TaskView>): AgentIntent

    companion object {
        /** Tasks a request can be about: open ones, plus finished ones the user may still ask after. */
        fun candidates(tasks: List<TaskView>): List<TaskView> =
            tasks.filter { it.status in OPEN || it.unread || it.elapsedS < RECENT_S }.takeLast(MAX_CANDIDATES)

        val OPEN = setOf("queued", "running", "waiting_on_user", "not_sent")
        const val RECENT_S = 30 * 60L
        const val MAX_CANDIDATES = 6
    }
}

/**
 * Jev (System One, the cascade's measured questions: ACTION, TASK, UPDATE_MODE) classifies the
 * request; its failure, a timeout or no key falls back to [HeuristicAgentIntents]. No task to be
 * about: no call, a new task.
 */
class JevAgentIntents(
    private val jev: suspend (state: JsonObject, questions: Map<String, JevQuestion>, timeoutMs: Long) -> OpenRouterResult<Map<String, JevAnswer>>,
    private val timeoutMs: Long = 2_500L,
    private val fallback: AgentIntents = HeuristicAgentIntents,
) : AgentIntents {
    override suspend fun classify(prompt: DelegationPrompt, tasks: List<TaskView>): AgentIntent {
        val candidates = AgentIntents.candidates(tasks)
        if (candidates.isEmpty()) return AgentIntent.New
        val ctx = TurnContext(
            heard = prompt.prompt,
            pending = emptyList(),
            silenceMs = 1_000,
            recent = emptyList(),
            tasks = candidates.map { TaskBrief(it.id, it.label, stateOf(it), it.lastEvent, it.elapsedS) },
            speaking = false,
        )
        val questions = linkedMapOf<String, JevQuestion>()
        questions[CascadeBrain.ACTION] = JevQuestions.ACTION
        questions[CascadeBrain.TASK] = JevQuestion(
            JevQuestions.TASK,
            linkedMapOf<String, String>().apply {
                candidates.forEach { put(it.id, "${it.label} (${it.status})") }
                put(CascadeBrain.NONE, JevQuestions.TASK_NONE)
            },
        )
        if (candidates.any { it.status == "running" }) questions[CascadeBrain.UPDATE_MODE] = JevQuestions.UPDATE_MODE
        val answers = runCatching { jev(CascadeBrain.state(ctx), questions, timeoutMs) }.getOrNull()?.valueOrNull()
            ?: return fallback.classify(prompt, tasks)
        return decide(answers, prompt, candidates)
    }

    /** Jev's answers through floors: a wrong cancel is worse than a stray task, so it needs more. */
    internal fun decide(answers: Map<String, JevAnswer>, prompt: DelegationPrompt, candidates: List<TaskView>): AgentIntent {
        val action = answers[CascadeBrain.ACTION] ?: return AgentIntent.New
        val taskAnswer = answers[CascadeBrain.TASK]
        val task = taskAnswer?.choice?.takeIf { c -> c != CascadeBrain.NONE && candidates.any { it.id == c } && taskAnswer.confidence >= TASK_FLOOR }
        val open = candidates.filter { it.status in AgentIntents.OPEN }
        return when (action.choice) {
            CascadeBrain.STATUS -> if (action.confidence >= ACTION_FLOOR) AgentIntent.Status(task) else AgentIntent.New
            CascadeBrain.CANCEL_TASK -> when {
                action.confidence < CANCEL_FLOOR -> AgentIntent.New
                task != null -> AgentIntent.Cancel(task)
                HeuristicAgentIntents.saysAll(prompt.prompt) && open.isNotEmpty() -> AgentIntent.Cancel(CascadeTasks.ALL)
                open.size == 1 -> AgentIntent.Cancel(open.single().id)
                open.isEmpty() -> AgentIntent.Ask("Nothing is running right now, so there is nothing to stop.")
                else -> AgentIntent.Ask(HeuristicAgentIntents.which(open, "stop"))
            }
            CascadeBrain.UPDATE_TASK -> {
                val target = task ?: open.singleOrNull()?.id
                if (action.confidence < ACTION_FLOOR || target == null) {
                    AgentIntent.New
                } else {
                    val mode = if (answers[CascadeBrain.UPDATE_MODE]?.choice == CascadeBrain.REDIRECT) UpdateMode.Redirect else UpdateMode.Queue
                    AgentIntent.Update(target, mode)
                }
            }
            CascadeBrain.READ_RESULT -> if (task != null && action.confidence >= ACTION_FLOOR) AgentIntent.Read(task) else AgentIntent.New
            else -> AgentIntent.New
        }
    }

    private fun stateOf(v: TaskView): TaskState = when (v.status) {
        "done" -> TaskState.Done
        "failed" -> TaskState.Failed
        "cancelled" -> TaskState.Cancelled
        else -> TaskState.Running
    }

    companion object {
        /** The cascade's `actionFloor` (CEO routing). */
        const val ACTION_FLOOR = 0.40
        const val CANCEL_FLOOR = 0.60
        const val TASK_FLOOR = 0.50
    }
}

/** Words-only fallback: clear stop and status phrasings, with a task named by its label words. Anything else is new work. */
object HeuristicAgentIntents : AgentIntents {
    private val CANCEL = Regex("\\b(cancel|stop|kill|scrap|abort|drop|forget about|never mind|call off)\\b", RegexOption.IGNORE_CASE)
    private val STATUS = Regex(
        "\\b(how(?:'s| is| are)\\b.{0,40}\\b(going|getting on|doing|coming along)|status|progress|any (?:news|update)|(?:finished|done) yet|still (?:running|working))\\b",
        RegexOption.IGNORE_CASE,
    )
    private val ALL = Regex("\\b(everything|all of them|all the tasks|them all|the lot|all tasks|every task)\\b", RegexOption.IGNORE_CASE)
    private val WORD = Regex("[A-Za-z0-9]{4,}")

    override suspend fun classify(prompt: DelegationPrompt, tasks: List<TaskView>): AgentIntent {
        val candidates = AgentIntents.candidates(tasks)
        if (candidates.isEmpty()) return AgentIntent.New
        val text = prompt.prompt
        val named = named(text, candidates)
        val open = candidates.filter { it.status in AgentIntents.OPEN }
        return when {
            CANCEL.containsMatchIn(text) && text.split(' ').size <= MAX_COMMAND_WORDS -> when {
                named != null -> AgentIntent.Cancel(named.id)
                saysAll(text) && open.isNotEmpty() -> AgentIntent.Cancel(CascadeTasks.ALL)
                open.size == 1 -> AgentIntent.Cancel(open.single().id)
                open.isEmpty() -> AgentIntent.New
                else -> AgentIntent.Ask(which(open, "stop"))
            }
            STATUS.containsMatchIn(text) && text.split(' ').size <= MAX_COMMAND_WORDS -> AgentIntent.Status(named?.id)
            else -> AgentIntent.New
        }
    }

    fun saysAll(text: String): Boolean = ALL.containsMatchIn(text)

    /** The one candidate whose label words (4+ letters) appear in [text]. */
    fun named(text: String, candidates: List<TaskView>): TaskView? {
        val words = WORD.findAll(text.lowercase()).map { it.value }.toSet()
        val hits = candidates.filter { c -> WORD.findAll(c.label.lowercase()).any { it.value in words } }
        return hits.singleOrNull() ?: hits.lastOrNull { it.status in AgentIntents.OPEN }.takeIf { hits.size > 1 && hits.count { h -> h.status in AgentIntents.OPEN } == 1 }
    }

    /** A spoken question naming the open tasks ("Which task should I stop: 'A' or 'B'?"). */
    fun which(open: List<TaskView>, verb: String): String {
        val names = open.map { "'${it.label}'" }
        val list = if (names.size <= 1) names.joinToString() else names.dropLast(1).joinToString(", ") + " or " + names.last()
        return "Which task should I $verb: $list?"
    }

    /** Longer requests are real work that happens to contain the word ("stop the delivery and email them"). */
    const val MAX_COMMAND_WORDS = 12
}
