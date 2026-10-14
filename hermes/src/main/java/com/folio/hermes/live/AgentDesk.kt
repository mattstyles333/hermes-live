package com.folio.hermes.live

import com.folio.hermes.cascade.CascadeTasks
import com.folio.hermes.cascade.TaskNotice
import com.folio.hermes.cascade.TaskReply
import com.folio.hermes.cascade.TaskView
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Timings for [AgentDesk]'s injection rule (Grok's, on GPT-Live's signals). */
data class AgentDeskSettings(
    /** Quiet after the user's last words before a result may go in. */
    val settleMs: Long = 1_200L,
    /** Quiet after the voice's last words. */
    val voiceQuietMs: Long = 800L,
    /** The voice has this long to start answering the user's last turn before results may go in anyway (GrokSession.TURN_GRACE_MS). */
    val turnGraceMs: Long = 3_000L,
    /** After an injection, wait this long for the voice to start saying it before the next one. */
    val heardWaitMs: Long = 8_000L,
)

/** What the desk did, for the app's telemetry (no content). */
sealed interface AgentDeskEvent {
    data class Started(val taskId: String, val delegationId: String?, val queued: Boolean, val existing: Boolean) : AgentDeskEvent
    /** [intent]: status, cancel, update, read or ask. */
    data class Handled(val intent: String, val taskId: String?) : AgentDeskEvent
    data class Failed(val kind: String) : AgentDeskEvent
    data class Injected(val count: Int, val waitedMs: Long, val asCommentary: Int) : AgentDeskEvent
}

/**
 * The injection rule: one batch at a time, never over the user or over the voice. Results go in
 * only when the user is silent ([AgentDeskSettings.settleMs]), the voice is quiet, the voice has
 * answered the user's last turn (or [AgentDeskSettings.turnGraceMs] passed), and the previous
 * batch was taken up (the voice spoke after it) or [AgentDeskSettings.heardWaitMs] passed. Pure.
 */
class InjectionGate(private val settings: AgentDeskSettings = AgentDeskSettings()) {
    private var lastUserAt = Long.MIN_VALUE / 2
    private var lastVoiceAt = Long.MIN_VALUE / 2
    private var injectedAt: Long? = null
    private var takenUp = true

    fun user(now: Long) {
        lastUserAt = now
    }

    /** The voice spoke; true when this is its first output since the last injection (the batch was taken up). */
    fun voice(now: Long): Boolean {
        lastVoiceAt = now
        val at = injectedAt ?: return false
        if (takenUp || now < at) return false
        takenUp = true
        return true
    }

    fun injected(now: Long) {
        injectedAt = now
        takenUp = false
    }

    /** A batch went in and the voice has not taken it up yet (within [AgentDeskSettings.heardWaitMs]). */
    fun awaiting(now: Long): Boolean {
        val at = injectedAt ?: return false
        return !takenUp && now - at < settings.heardWaitMs
    }

    fun quiet(now: Long, userSpeaking: Boolean, voiceSpeaking: Boolean): Boolean {
        if (userSpeaking || voiceSpeaking) return false
        if (now - lastUserAt < settings.settleMs) return false
        if (now - lastVoiceAt < settings.voiceQuietMs) return false
        if (lastVoiceAt < lastUserAt && now - lastUserAt < settings.turnGraceMs) return false
        val at = injectedAt
        if (at != null && !takenUp && now - at < settings.heardWaitMs) return false
        return true
    }
}

/**
 * GPT-Live's parallel agents (gptlive-parallel slice). Each `session.delegation.created` becomes
 * its own durable task through the shared task API, keyed by the voice session and delegation id
 * (a replayed or doubled delegation never submits twice; another session's `del_1` is new work). Before that, [intents] decides whether the request is
 * about a task already running (status, cancel, update, read again); the session note at the
 * start of every session asks the model to delegate those as requests.
 *
 * To the voice: a start is a quiet `thinking.append` on its delegation; each running task's tool
 * steps go as `thinking.append` ("Task 'Stock check' is working: web search. Not done yet.");
 * results, questions and approvals wait in a queue until [InjectionGate] says the moment is
 * quiet, then go as `commentary.append` on the task's delegation when it belongs to this session,
 * else as `instructions.append`. A result is marked heard (never replayed) once the voice speaks
 * after it. The full text is on the task card.
 *
 * Lives for one call, across reconnects and on-demand reopens ([attach] / [detach]); tasks
 * outlive it. All methods on [scope]'s single thread (Main).
 */
class AgentDesk(
    private val scope: CoroutineScope,
    private val tasks: DelegationTasks,
    private val intents: AgentIntents,
    private val labeler: TaskLabeler,
    private val clock: () -> Long,
    private val settings: AgentDeskSettings = AgentDeskSettings(),
    private val listener: (AgentDeskEvent) -> Unit = {},
) : DelegationAgents {
    private val gate = InjectionGate(settings)
    private val jobs = mutableListOf<Job>()
    private var voice: AgentVoice? = null

    /**
     * Delegation ids are kept qualified by their voice session ("<session>:<id>"), because the
     * vendor's ids are only known to be unique within a session. [live]: the current session's
     * (commentary and thinking need one of these); [rawOf]: the vendor's own id for the wire.
     */
    private val live = hashSetOf<String>()
    private val rawOf = hashMapOf<String, String>()
    private var session = UUID.randomUUID().toString()
    private val seen = hashSetOf<String>()
    private val delegationOf = hashMapOf<String, String>()
    private val toolOf = hashMapOf<String, String>()
    private val queue = mutableListOf<TaskNotice>()
    private val queuedAt = hashMapOf<TaskNotice, Long>()
    private val told = mutableListOf<TaskNotice>()
    private var handling = 0
    private var stopped = false
    private var userSpeaking = false
    private var voiceSpeaking = false

    private val _pending = MutableStateFlow(0)
    private val _running = MutableStateFlow(0)

    /** Results and requests waiting to be told (an on-demand controller reopens the voice for these). */
    val pendingResults: StateFlow<Int> = _pending.asStateFlow()

    /** Tasks queued, running or waiting on the user. */
    val running: StateFlow<Int> = _running.asStateFlow()

    override val working: Boolean get() = handling > 0 || _running.value > 0

    /**
     * Nothing for the voice in flight: no delegation being handled, and no result it was given
     * and has not started saying. Running tasks don't count (the on-demand session may close
     * while they work).
     */
    fun settled(now: Long = clock()): Boolean = handling == 0 && !gate.awaiting(now)

    /** Start listening to the tasks. Results the user was not told yet (from before this call) are queued first. */
    fun start() {
        jobs += scope.launch(start = CoroutineStart.UNDISPATCHED) { tasks.notices.collect { enqueue(it) } }
        jobs += scope.launch(start = CoroutineStart.UNDISPATCHED) { tasks.views.collect { progress(it) } }
        jobs += scope.launch { runCatching { tasks.unheard() }.getOrDefault(emptyList()).forEach(::enqueue) }
    }

    /**
     * A voice session is live: [voice] speaks for it. [newSession]: its delegation ids are new
     * (reconnect, reopen); [sessionId] is the vendor's (`session.started`), else a fresh one.
     */
    fun attach(voice: AgentVoice, newSession: Boolean = true, sessionId: String? = null) {
        if (stopped) return
        this.voice = voice
        if (newSession) {
            live.clear()
            session = sessionId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        }
        voice.instruct(SESSION_NOTE)
        pump()
    }

    /** The voice session is gone (dropped or closed on demand). Results told but not yet spoken go back in the queue. */
    fun detach() {
        voice = null
        if (told.isNotEmpty()) {
            queue.addAll(0, told.filter { it !in queue })
            told.clear()
            _pending.value = queue.size
        }
    }

    /** The call is over. Tasks carry on (off-call service, notifications). */
    fun stop() {
        stopped = true
        voice = null
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    /** The user said something (transcript). */
    fun userWords(now: Long = clock()) = gate.user(now)

    /** The voice said something (transcript): a batch it was given has been taken up. */
    fun voiceWords(now: Long = clock()) {
        if (!gate.voice(now) || told.isEmpty()) return
        val batch = told.toList()
        told.clear()
        scope.launch { batch.map { it.taskId }.distinct().forEach { runCatching { tasks.heard(it) } } }
    }

    /** Who is audible now (engine tick): results go in only in a quiet moment. */
    fun signals(userSpeaking: Boolean, voiceSpeaking: Boolean) {
        this.userSpeaking = userSpeaking
        this.voiceSpeaking = voiceSpeaking
        pump()
    }

    override fun onDelegation(delegationId: String?, prompt: DelegationPrompt) {
        if (stopped) return
        val qualified = delegationId?.let { raw ->
            "$session:$raw".also {
                live += it
                rawOf[it] = raw
            }
        }
        if (qualified != null && !seen.add(qualified)) return
        handling++
        scope.launch {
            try {
                handle(qualified, prompt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                listener(AgentDeskEvent.Failed(e.javaClass.simpleName))
                say(delegationId, SORRY)
            } finally {
                handling--
                pump()
            }
        }
    }

    /** One line for a reopened session's history: the tasks still open, or null. */
    fun openTasksNote(): String? {
        val open = tasks.status().filter { it.status in AgentIntents.OPEN }
        if (open.isEmpty()) return null
        return "Tasks running: " + open.joinToString(", ") { "'${it.label}' (${plainStatus(it)})" } + "."
    }

    // ── internals ────────────────────────────────────────────────────────────────────────────

    private suspend fun handle(delegationId: String?, prompt: DelegationPrompt) {
        val key = delegationId ?: "text-${UUID.randomUUID()}"
        val views = tasks.status()
        when (val intent = intents.classify(prompt, views)) {
            AgentIntent.New -> startNew(delegationId, prompt, key)
            is AgentIntent.Status -> {
                listener(AgentDeskEvent.Handled("status", intent.taskId))
                say(delegationId, statusText(tasks.status(), intent.taskId))
            }
            is AgentIntent.Cancel -> {
                val reply = tasks.cancel(intent.taskId, key)
                listener(AgentDeskEvent.Handled("cancel", intent.taskId))
                say(delegationId, cancelText(reply, views))
            }
            is AgentIntent.Update -> {
                val label = labelOf(views, intent.taskId)
                listener(AgentDeskEvent.Handled("update", intent.taskId))
                when (val reply = tasks.update(intent.taskId, prompt.prompt, intent.mode, key)) {
                    is TaskReply.Updated -> {
                        if (delegationId != null) delegationOf[reply.taskId] = delegationId
                        think(delegationId, "Passed that to task '$label'. Not done yet.")
                    }
                    is TaskReply.TooLate -> say(delegationId, "Task '$label' had already finished.")
                    TaskReply.AtCap -> say(delegationId, "Every task slot is busy, so '$label' can't take that now. Try again when one finishes.")
                    else -> say(delegationId, SORRY)
                }
            }
            is AgentIntent.Read -> {
                listener(AgentDeskEvent.Handled("read", intent.taskId))
                val result = tasks.result(intent.taskId)
                if (result.isNullOrBlank()) {
                    say(delegationId, "Task '${labelOf(views, intent.taskId)}' has no result yet.")
                } else {
                    say(delegationId, "Task '${labelOf(views, intent.taskId)}' result: ${AgentText.excerpt(result)}")
                    tasks.heard(intent.taskId)
                }
            }
            is AgentIntent.Ask -> {
                listener(AgentDeskEvent.Handled("ask", null))
                say(delegationId, intent.question)
            }
        }
    }

    private suspend fun startNew(delegationId: String?, prompt: DelegationPrompt, key: String) {
        val label = labeler.label(prompt)
        when (val reply = tasks.start(AgentText.brief(prompt), label, key)) {
            is TaskReply.Started -> {
                if (delegationId != null) delegationOf[reply.taskId] = delegationId
                listener(AgentDeskEvent.Started(reply.taskId, delegationId, reply.queued, reply.existing))
                if (!reply.existing) {
                    val how = if (reply.queued) "It waits for a free slot while other tasks run." else "It is working now."
                    think(delegationId, "Started task '${reply.label}' as a background agent. $how Tell the user in a few words; don't guess the result, it will be reported when it is done.")
                }
            }
            else -> {
                listener(AgentDeskEvent.Failed(reply.javaClass.simpleName))
                say(delegationId, SORRY)
            }
        }
    }

    private fun progress(views: List<TaskView>) {
        _running.value = views.count { it.status in RUNNING }
        val v = voice
        for (t in views) {
            if (t.status != "running") {
                toolOf.remove(t.id)
                continue
            }
            val tool = t.currentTool ?: continue
            if (toolOf[t.id] == tool) continue
            toolOf[t.id] = tool
            val d = delegationOf[t.id]?.takeIf { it in live }?.let(rawOf::get) ?: continue
            v?.think(d, "Task '${t.label}' is working: $tool. Not done yet.")
        }
    }

    private fun enqueue(n: TaskNotice) {
        if (stopped) return
        if (n is TaskNotice.Withdrawn) {
            queue.removeAll { it.taskId == n.taskId && it is TaskNotice.NeedsYou }
            _pending.value = queue.size
            return
        }
        if (n in queue || n in told) return
        queue += n
        queuedAt[n] = clock()
        _pending.value = queue.size
        pump()
    }

    private fun pump() {
        val v = voice ?: return
        if (stopped || queue.isEmpty() || !gate.quiet(clock(), userSpeaking, voiceSpeaking)) return
        val batch = queue.toList()
        queue.clear()
        val now = clock()
        var asCommentary = 0
        var waited = 0L
        batch.forEach { n ->
            val d = delegationOf[n.taskId]?.takeIf { it in live }?.let(rawOf::get)
            if (d != null) {
                asCommentary++
                v.say(d, commentaryFor(n))
            } else {
                v.instruct(instructionFor(n))
            }
            waited = maxOf(waited, now - (queuedAt.remove(n) ?: now))
        }
        gate.injected(now)
        told += batch
        _pending.value = 0
        listener(AgentDeskEvent.Injected(batch.size, waited, asCommentary))
    }

    private fun say(delegationId: String?, text: String) {
        voice?.say(delegationId?.takeIf { it in live }?.let(rawOf::get), text)
    }

    private fun think(delegationId: String?, text: String) {
        val d = delegationId?.takeIf { it in live }?.let(rawOf::get) ?: return
        voice?.think(d, text)
    }

    companion object {
        val RUNNING = setOf("queued", "running", "waiting_on_user")
        const val SORRY = "Sorry, I could not reach Hermes for that request."

        /** Sent at the start of every voice session while agents are on. */
        const val SESSION_NOTE =
            "Agent cards: every request you delegate runs as its own background agent task, shown to the user as a card with a short name, " +
                "and several can run at once. When the user asks to stop, cancel, change or check on a task (\"cancel the stock check\", " +
                "\"how's the email going?\"), delegate that too, in their words: the app handles it. Progress arrives as thinking, results " +
                "arrive later named by their task. Never guess a result while a task works. Approvals are tapped on the screen, never given by voice."

        private fun labelOf(views: List<TaskView>, taskId: String): String =
            if (taskId == CascadeTasks.ALL) "all tasks" else views.firstOrNull { it.id == taskId }?.label ?: "that task"

        fun plainStatus(v: TaskView): String = when (v.status) {
            "queued" -> "waiting for a free slot"
            "running" -> v.currentTool?.let { "working: $it" } ?: "working"
            "waiting_on_user" -> "waiting for the user on the screen"
            "not_sent" -> "not sent yet, it goes when the connection is back"
            "done" -> "finished"
            "failed" -> "did not finish"
            "cancelled" -> "stopped"
            else -> v.status
        }

        /** The status answer, from the phone's task table only (never Hermes). */
        fun statusText(views: List<TaskView>, taskId: String?): String {
            val pick = if (taskId != null) views.filter { it.id == taskId } else AgentIntents.candidates(views)
            if (pick.isEmpty()) return "No tasks are running."
            return pick.joinToString(" ") { v ->
                val steps = if (v.status == "running" && v.steps > 0) ", ${v.steps} step${if (v.steps == 1) "" else "s"} so far" else ""
                "Task '${v.label}': ${plainStatus(v)}$steps."
            }
        }

        fun cancelText(reply: TaskReply, views: List<TaskView>): String = when (reply) {
            is TaskReply.Cancelled -> when (reply.taskIds.size) {
                0 -> "Nothing was running, so there was nothing to stop."
                1 -> "Stopped task '${labelOf(views, reply.taskIds.single())}'."
                else -> "Stopped all ${reply.taskIds.size} tasks."
            }
            is TaskReply.TooLate -> "Task '${labelOf(views, reply.taskId)}' had already finished."
            is TaskReply.NotFound -> "There is no such task."
            else -> SORRY
        }

        /** A notice as commentary on its own delegation (the voice paraphrases it). */
        fun commentaryFor(n: TaskNotice): String = when (n) {
            is TaskNotice.Finished -> when {
                n.ok && n.text.isNotBlank() -> "Task '${n.label}' finished: ${AgentText.excerpt(n.text)}"
                n.ok -> "Task '${n.label}' finished with nothing to report."
                else -> "Task '${n.label}' didn't finish. It can be retried from its card."
            }
            is TaskNotice.NeedsYou -> if (n.kind == "clarify") {
                "Task '${n.label}' has a question for you on the screen."
            } else {
                "Task '${n.label}' needs your approval on the screen: tap Approve or Deny."
            }
            is TaskNotice.Withdrawn -> "Task '${n.label}' no longer needs you."
        }

        /** A notice as an instruction (its delegation belongs to an earlier session, or there is none). */
        fun instructionFor(n: TaskNotice): String = when (n) {
            is TaskNotice.Finished -> when {
                n.ok && n.text.isNotBlank() ->
                    "Task '${n.label}' finished: ${AgentText.excerpt(n.text)}\nTell the user the headline in a sentence or two; the full answer is on the screen."
                n.ok -> "Task '${n.label}' finished with nothing to report. Tell the user in a few words."
                else -> "Task '${n.label}' didn't finish. Tell the user in a few words; they can retry it from its card."
            }
            is TaskNotice.NeedsYou -> if (n.kind == "clarify") {
                "Task '${n.label}' has a question for the user. It is on the screen: ask them to answer it there."
            } else {
                "Task '${n.label}' needs the user's approval. It is on the screen: ask them to tap Approve or Deny. Never approve anything yourself."
            }
            is TaskNotice.Withdrawn -> "Task '${n.label}' no longer needs the user."
        }
    }
}
