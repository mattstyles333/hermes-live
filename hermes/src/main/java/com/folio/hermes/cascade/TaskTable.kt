package com.folio.hermes.cascade

import com.folio.hermes.BusyMode
import com.folio.hermes.HermesConversation
import com.folio.hermes.SubmitStatus
import com.folio.hermes.TurnStatus
import com.folio.hermes.TurnUpdate

/** One entry in a task's event log (what status is answered from; R-TASK-3). */
sealed interface TaskEvent {
    val atMs: Long

    /** Text went to the task: `start`, `queue`, `redirect` or `interrupt_first`. */
    data class Submitted(override val atMs: Long, val how: String) : TaskEvent
    data class Started(override val atMs: Long) : TaskEvent
    data class Tool(override val atMs: Long, val label: String, val done: Boolean, val summary: String?) : TaskEvent
    data class Interim(override val atMs: Long, val text: String) : TaskEvent
    data class Waiting(override val atMs: Long, val what: String) : TaskEvent
    data class Finished(override val atMs: Long, val status: TurnStatus) : TaskEvent
    data class Failed(override val atMs: Long) : TaskEvent
    data class Cancelled(override val atMs: Long) : TaskEvent
}

/** A task as the phone knows it. [progress] is the tail of the reply streamed so far. */
data class TaskSnapshot(
    val id: String,
    val label: String,
    val state: TaskState,
    val startedAtMs: Long,
    val events: List<TaskEvent>,
    val progress: String,
    val result: String?,
    val unread: Boolean,
    val waitingOnUser: Boolean,
    /** No `prompt.submit` reply yet (the session may still be opening). */
    val starting: Boolean = false,
    /** The server queued the last text behind a running turn and it has not started. */
    val queuedAtServer: Boolean = false,
    val finishedAtMs: Long? = null,
    /** `tool.complete`s so far. */
    val steps: Int = 0,
    val currentTool: String? = null,
    /** Last five tool calls. */
    val recentTools: List<ToolUi> = emptyList(),
    val lastEventAtMs: Long = startedAtMs,
    /** Queued updates that have not started yet. */
    val pendingUpdates: Int = 0,
) {
    /** A short, content-free description of the last thing that happened (for Jev's `last_event`). */
    val lastEvent: String
        get() = when (val e = events.lastOrNull()) {
            null -> "queued"
            is TaskEvent.Submitted -> "sent (${e.how})"
            is TaskEvent.Started -> "working"
            is TaskEvent.Tool -> if (e.done) "finished ${e.label}" else "using ${e.label}"
            is TaskEvent.Interim -> "working"
            is TaskEvent.Waiting -> "waiting on the user (${e.what})"
            is TaskEvent.Finished -> e.status.name.lowercase()
            is TaskEvent.Failed -> "failed"
            is TaskEvent.Cancelled -> "cancelled"
        }

    fun brief(nowMs: Long): TaskBrief = TaskBrief(id, label, state, lastEvent, ((nowMs - startedAtMs) / 1000).coerceAtLeast(0))
}

/** A finished turn's answer (or failure) for the speech queue. */
data class TaskResult(val taskId: String, val label: String, val text: String, val ok: Boolean)

/** An approval, clarify or withdrawal from a task, for the app's cards (tap only, as before). */
data class TaskRequest(val taskId: String, val label: String, val update: TurnUpdate)

sealed interface StartResult {
    data class Started(val task: TaskSnapshot) : StartResult

    /** [CascadeSettings.taskCap] tasks are already running; nothing was sent. */
    data class AtCap(val running: Int) : StartResult
}

sealed interface UpdateResult {
    /** Sent; [how] is `queue`, `redirect` (server busy mode) or `interrupt_first`. */
    data class Sent(val how: String) : UpdateResult
    data object NotFound : UpdateResult
    data class AtCap(val running: Int) : UpdateResult
}

enum class CancelResult { Cancelled, AlreadyFinished, NotFound }

/** How text reaches a task's session. */
enum class RunKind(val how: String) { Start("start"), Queue("queue"), Redirect("redirect"), InterruptFirst("interrupt_first") }

/**
 * One submitted turn: its [kind], and whether it still counts as a pending update.
 *
 * Durability (R-DUR-1/2): [turnId] is the user turn it carries out (a resend of that turn never
 * submits it again), [text] is what goes to Hermes, kept until the run is [accepted] so a
 * restart can send it again if Hermes never got it. [opening]: the task's session is being
 * created and the prompt has not left yet (session first, then persist, then submit). The
 * [voiceContext] rides along in memory only.
 */
data class TaskRun(
    val kind: RunKind,
    val pending: Boolean,
    val turnId: String? = null,
    val text: String? = null,
    val accepted: Boolean = false,
    val opening: Boolean = false,
    val voiceContext: String = "",
)

/** Whether Hermes has the task's text (CEO §1: the card shows "sending" until `Accepted`; R-DUR-2/4). */
enum class SendState { Sending, Accepted, NotSent }

/**
 * One voice task (immutable). The C1 `TaskBoard` fields, plus the CEO fields phase 1 fills:
 * [brief] (the agent prompt), [summary] (≤ 12 words, spoken after `Accepted`), [turnId] (the
 * turn that started it, for dedupe), [storedSessionId] (for `session.resume`), [send] and
 * [offline] (dictated offline).
 */
data class TaskRecord(
    val id: String,
    val label: String,
    val startedAt: Long,
    val state: TaskState = TaskState.Running,
    val cancelled: Boolean = false,
    val active: Int = 0,
    val lastFailed: Boolean = false,
    val events: List<TaskEvent> = emptyList(),
    val progress: String = "",
    val result: String? = null,
    val unread: Boolean = false,
    val waiting: Boolean = false,
    val accepted: Boolean = false,
    val queuedAtServer: Boolean = false,
    val finishedAt: Long? = null,
    val steps: Int = 0,
    val currentTool: String? = null,
    val tools: List<ToolUi> = emptyList(),
    val lastEventAt: Long = startedAt,
    val pendingUpdates: Int = 0,
    /** Turns submitted and not ended, by run id (R-ARC-2 correlation). */
    val runs: Map<Long, TaskRun> = emptyMap(),
    val brief: String? = null,
    val summary: String? = null,
    val turnId: String? = null,
    val storedSessionId: String? = null,
    val send: SendState = SendState.Sending,
    val submitAttempts: Int = 0,
    val offline: Boolean = false,
    /** Waiting on the phone for a free slot: nothing was sent yet (R-TASK-1; the card reads Queued). */
    val queued: Boolean = false,
    /** Set by crash recovery only (never stored): its prompt never left, so start it again once restored. */
    val relaunch: Boolean = false,
) {
    fun log(event: TaskEvent): TaskRecord = copy(events = (events + event).takeLast(TaskTable.MAX_EVENTS), lastEventAt = event.atMs)

    fun snapshot(): TaskSnapshot = TaskSnapshot(
        id, label, state, startedAt, events, progress, result, unread, waiting,
        starting = !accepted, queuedAtServer = queuedAtServer, finishedAtMs = finishedAt, steps = steps,
        currentTool = currentTool, recentTools = tools, lastEventAtMs = lastEventAt, pendingUpdates = pendingUpdates,
    )
}

/** What a task-table operation produced, besides the new table. */
data class TaskStep<R>(
    val table: TaskTable,
    val value: R,
    val effects: List<Effect> = emptyList(),
    val events: List<CascadeEvent> = emptyList(),
    val results: List<TaskResult> = emptyList(),
    val requests: List<TaskRequest> = emptyList(),
    /** `cascade.task` reports: (event, extra attributes). */
    val reports: List<Pair<String, Map<String, Any?>>> = emptyList(),
)

/**
 * Several Hermes tasks in parallel (R-TASK-1), as a pure value owned by the actor (from the C1
 * `TaskBoard`). Each task is its own Hermes session; effects open and submit, results come back
 * as [TurnUpdate]s per run id.
 *
 * - [start] submits with `surface: "voice-live"` and the voice context. At most `cap` run at once.
 * - [reopen] + [launch] update a task: `queue` sends `prompt.submit {queued: true}`; `redirect`
 *   follows the server's busy mode when it is `interrupt` or `steer`, else interrupts and then
 *   submits ([kindFor]). Never changes server config.
 * - [snapshot] / [snapshots] / [briefs] answer status from the event log only: they never touch
 *   the network, so asking how a task is going can never redirect it (R-TASK-3).
 * - [cancel] is `session.interrupt`; the task stays `cancelled` and its interrupted ending is not a result.
 * - A queued update yields two results (the running turn's, then the queued one's); a redirect
 *   yields one (the running flow owns it); an interrupted ending is never a result.
 */
data class TaskTable(
    /** Every task, oldest first. */
    val tasks: List<TaskRecord> = emptyList(),
    val counter: Int = 0,
    val runCounter: Long = 0L,
) {
    operator fun get(id: String): TaskRecord? = tasks.firstOrNull { it.id == id }

    val runningCount: Int get() = tasks.count { it.state == TaskState.Running }

    /** True when [start] would refuse. */
    fun isFull(cap: Int): Boolean = runningCount >= cap.coerceIn(1, 6)

    fun snapshot(id: String): TaskSnapshot? = this[id]?.snapshot()

    fun snapshots(): List<TaskSnapshot> = tasks.map { it.snapshot() }

    /** Tasks for Jev's `state.tasks` and the writer. */
    fun briefs(now: Long): List<TaskBrief> = snapshots().map { it.brief(now) }

    private fun put(t: TaskRecord): TaskTable = copy(tasks = tasks.map { if (it.id == t.id) t else it })

    /** Start a task with [prompt] (written for the agent) under [label] (2–4 words). */
    fun start(prompt: String, label: String, voiceContext: String, cap: Int, now: Long, turnId: String? = null): TaskStep<StartResult> {
        val running = runningCount
        if (running >= cap.coerceIn(1, 6)) {
            return TaskStep(this, StartResult.AtCap(running), reports = listOf("at_cap" to mapOf("running" to running)))
        }
        val id = "t${counter + 1}"
        val record = TaskRecord(id, label, now, brief = prompt, turnId = turnId)
        val table = copy(tasks = tasks + record, counter = counter + 1)
        val launched = table.launch(id, prompt, voiceContext, RunKind.Start, now)
        return TaskStep(
            launched.table,
            StartResult.Started(launched.table.snapshot(id)!!),
            effects = launched.effects,
            events = listOf(CascadeEvent.TaskStarted(id)),
            reports = listOf("start" to mapOf("running" to launched.table.runningCount)),
        )
    }

    /**
     * First half of an update: find task [id]; a finished task takes the text as a follow-up turn
     * (and counts against the cap again). Then [launch] with [kindFor].
     */
    fun reopen(id: String, cap: Int): TaskStep<UpdateResult?> {
        val t = this[id] ?: return TaskStep(this, UpdateResult.NotFound)
        if (t.state == TaskState.Running) return TaskStep(this, null)
        val running = runningCount
        if (running >= cap.coerceIn(1, 6)) return TaskStep(this, UpdateResult.AtCap(running))
        return TaskStep(put(t.copy(state = TaskState.Running, cancelled = false, finishedAt = null)), null)
    }

    /**
     * Submit [text] to task [id] as [kind] (after [reopen]), for user turn [turnId] if any.
     *
     * A task with no stored session opens one first ([Effect.OpenTaskSession]); the submit
     * follows [sessionOpened], after the session id is stored, so a restart can always ask
     * Hermes whether the prompt arrived (exact idempotency, R-DUR-2).
     */
    fun launch(id: String, text: String, voiceContext: String, kind: RunKind, now: Long, turnId: String? = null): TaskStep<Long?> {
        val t = this[id] ?: return TaskStep(this, null)
        val pending = kind == RunKind.Queue && t.active > 0
        val runId = runCounter + 1
        val opening = kind == RunKind.Start && t.storedSessionId == null
        val run = TaskRun(kind, pending, turnId = turnId, text = text, opening = opening, voiceContext = voiceContext)
        val record = t.copy(
            pendingUpdates = t.pendingUpdates + if (pending) 1 else 0,
            active = t.active + 1,
            runs = t.runs + (runId to run),
            queued = false,
        ).log(TaskEvent.Submitted(now, kind.how))
        val effect = if (opening) Effect.OpenTaskSession(id, runId, t.label) else submit(record, runId, run)
        return TaskStep(copy(tasks = tasks.map { if (it.id == id) record else it }, runCounter = runId), runId, effects = listOf(effect))
    }

    /**
     * Task [id]'s session for run [runId] exists ([storedSessionId], or null when the port cannot
     * create one ahead and the submit creates it). The session id is stored by this step, so the
     * submit goes out after it is durable. A run cancelled meanwhile is dropped unsent.
     */
    fun sessionOpened(id: String, runId: Long, storedSessionId: String?): TaskStep<Unit> {
        val t = this[id] ?: return TaskStep(this, Unit)
        val run = t.runs[runId]?.takeIf { it.opening } ?: return TaskStep(this, Unit)
        if (t.cancelled || t.state != TaskState.Running) return forget(id, runId)
        val ready = run.copy(opening = false)
        val record = t.copy(storedSessionId = storedSessionId ?: t.storedSessionId, runs = t.runs + (runId to ready))
        return TaskStep(put(record), Unit, effects = listOf(submit(record, runId, ready)))
    }

    /** Drop run [runId] of task [id] as if it never went out (nothing reached Hermes): no ending, no result. */
    fun forget(id: String, runId: Long): TaskStep<Unit> {
        val t = this[id] ?: return TaskStep(this, Unit)
        val run = t.runs[runId] ?: return TaskStep(this, Unit)
        val record = t.copy(
            runs = t.runs - runId,
            active = (t.active - 1).coerceAtLeast(0),
            pendingUpdates = if (run.pending) (t.pendingUpdates - 1).coerceAtLeast(0) else t.pendingUpdates,
        )
        return TaskStep(put(record), Unit)
    }

    private fun submit(t: TaskRecord, runId: Long, run: TaskRun) = Effect.SubmitTask(
        taskId = t.id,
        runId = runId,
        label = t.label,
        storedSessionId = t.storedSessionId,
        text = run.text.orEmpty(),
        voiceContext = run.voiceContext,
        queued = run.kind == RunKind.Queue,
        interruptFirst = run.kind == RunKind.InterruptFirst,
        stopOnRedirect = run.kind == RunKind.Redirect,
    )

    /** Stop task [id] with `session.interrupt`. */
    fun cancel(id: String, now: Long): TaskStep<CancelResult> {
        val t = this[id] ?: return TaskStep(this, CancelResult.NotFound)
        if (t.state != TaskState.Running) return TaskStep(this, CancelResult.AlreadyFinished)
        val record = t.copy(cancelled = true, state = TaskState.Cancelled, finishedAt = now, currentTool = null).log(TaskEvent.Cancelled(now))
        return TaskStep(
            put(record),
            CancelResult.Cancelled,
            effects = listOf(Effect.InterruptTask(id)),
            events = listOf(CascadeEvent.TaskCancelled(id)),
        )
    }

    /** The interrupt for [id] came back; with no session yet ([ok] false) our side stops so nothing is submitted later. */
    fun interrupted(id: String, ok: Boolean): TaskStep<Unit> = TaskStep(
        this,
        Unit,
        effects = if (ok) emptyList() else listOf(Effect.CancelTaskRuns(id)),
        reports = listOf("cancel" to mapOf("running" to runningCount)),
    )

    /** The result of [id] was read out. */
    fun markRead(id: String): TaskTable = this[id]?.let { put(it.copy(unread = false)) } ?: this

    /** Hermes minted a stored session id for [id] (persist it for `session.resume`; R-DUR-3). */
    fun bound(id: String, storedSessionId: String): TaskTable = this[id]?.let { put(it.copy(storedSessionId = storedSessionId)) } ?: this

    /** One update from run [runId] of task [id]. A run that is not ours (ended, cancelled) is stale and dropped. */
    fun update(id: String, runId: Long, update: TurnUpdate, now: Long): TaskStep<Unit> {
        var t = this[id] ?: return TaskStep(this, Unit)
        var run = t.runs[runId] ?: return TaskStep(this, Unit)
        var result: TaskResult? = null
        var request: TaskRequest? = null
        var event: CascadeEvent? = null
        if (update is TurnUpdate.Started || update is TurnUpdate.Completed || update is TurnUpdate.Failed) {
            val settled = settle(t, run)
            t = settled.first
            run = settled.second
        }
        when (update) {
            is TurnUpdate.Accepted -> {
                // Hermes has this run's text: it never needs sending again.
                run = run.copy(accepted = true, text = null)
                t = t.copy(
                    accepted = true,
                    queuedAtServer = update.status == SubmitStatus.Queued && run.kind == RunKind.Start,
                    lastEventAt = now,
                    send = SendState.Accepted,
                )
            }
            is TurnUpdate.Started -> t = t.copy(queuedAtServer = false, progress = "").log(TaskEvent.Started(now))
            is TurnUpdate.Text -> t = t.copy(progress = update.text.takeLast(PROGRESS_CHARS), lastEventAt = now)
            is TurnUpdate.Interim -> t = t.log(TaskEvent.Interim(now, update.text.take(PROGRESS_CHARS)))
            is TurnUpdate.ToolStarted -> {
                t = t.copy(waiting = false, currentTool = update.label, tools = (t.tools + ToolUi(update.label, now, null)).takeLast(RECENT_TOOLS))
                    .log(TaskEvent.Tool(now, update.label, false, null))
                event = CascadeEvent.ToolStarted(t.id, update.label)
            }
            is TurnUpdate.ToolCompleted -> {
                val label = HermesConversation.toolLabel(update.name)
                val open = t.tools.indexOfLast { it.label == label && it.endedAtMs == null }
                t = t.copy(
                    steps = t.steps + 1,
                    currentTool = if (t.currentTool == label) null else t.currentTool,
                    tools = if (open >= 0) t.tools.mapIndexed { i, tool -> if (i == open) tool.copy(endedAtMs = now) else tool } else t.tools,
                ).log(TaskEvent.Tool(now, label, true, update.summary))
                event = CascadeEvent.ToolFinished(t.id)
            }
            is TurnUpdate.ApprovalNeeded -> {
                t = t.copy(waiting = true).log(TaskEvent.Waiting(now, "approval"))
                request = TaskRequest(t.id, t.label, update)
            }
            is TurnUpdate.ClarifyNeeded -> {
                t = t.copy(waiting = true).log(TaskEvent.Waiting(now, "clarify"))
                request = TaskRequest(t.id, t.label, update)
            }
            is TurnUpdate.RequestWithdrawn -> {
                t = t.copy(waiting = false)
                request = TaskRequest(t.id, t.label, update)
            }
            is TurnUpdate.Warning -> Unit
            is TurnUpdate.Completed -> {
                t = t.log(TaskEvent.Finished(now, update.status)).copy(waiting = false, currentTool = null)
                when {
                    t.cancelled -> Unit
                    update.status == TurnStatus.Interrupted -> Unit // a redirect or our own interrupt, never a result
                    update.status == TurnStatus.Error -> {
                        t = t.copy(lastFailed = true)
                        if (t.active <= 1) result = TaskResult(t.id, t.label, "", ok = false)
                    }
                    else -> {
                        t = t.copy(lastFailed = false, result = update.text, unread = true)
                        result = TaskResult(t.id, t.label, update.text, ok = true)
                    }
                }
            }
            is TurnUpdate.Failed -> {
                t = t.log(TaskEvent.Failed(now)).copy(currentTool = null)
                if (!t.cancelled) {
                    t = t.copy(lastFailed = true)
                    if (t.active <= 1) result = TaskResult(t.id, t.label, "", ok = false)
                }
            }
        }
        t = t.copy(runs = t.runs + (runId to run))
        val table = put(t)
        val reports = result?.let { listOf((if (it.ok) "done" else "failed") to mapOf<String, Any?>("running" to table.runningCount)) }.orEmpty()
        return TaskStep(table, Unit, events = listOfNotNull(event), results = listOfNotNull(result), requests = listOfNotNull(request), reports = reports)
    }

    /** Run [runId] of task [id] ended (completed, failed or cancelled). */
    fun runEnded(id: String, runId: Long, now: Long): TaskStep<Unit> {
        val t0 = this[id] ?: return TaskStep(this, Unit)
        val run = t0.runs[runId] ?: return TaskStep(this, Unit)
        var t = settle(t0, run).first.let { it.copy(runs = it.runs - runId, active = it.active - 1) }
        var finished: TaskState? = null
        if (t.active <= 0 && t.state == TaskState.Running) {
            val state = when {
                t.cancelled -> TaskState.Cancelled
                t.lastFailed -> TaskState.Failed
                else -> TaskState.Done
            }
            t = t.copy(state = state, finishedAt = now, currentTool = null, queuedAtServer = false)
            finished = state
        }
        val event = when (finished) {
            TaskState.Done -> CascadeEvent.TaskFinished(id, ok = true)
            TaskState.Failed -> CascadeEvent.TaskFinished(id, ok = false)
            else -> null
        }
        return TaskStep(put(t), Unit, events = listOfNotNull(event))
    }

    /** A queued update started or ended: it is no longer pending. */
    private fun settle(t: TaskRecord, run: TaskRun): Pair<TaskRecord, TaskRun> =
        if (run.pending) t.copy(pendingUpdates = (t.pendingUpdates - 1).coerceAtLeast(0)) to run.copy(pending = false) else t to run

    companion object {
        const val MAX_EVENTS = 50
        const val PROGRESS_CHARS = 600
        const val RECENT_TOOLS = 5

        /** How a redirect is sent, given the server's busy mode (never changes server config). */
        fun kindFor(mode: UpdateMode, busy: BusyMode?): RunKind = when (mode) {
            UpdateMode.Queue -> RunKind.Queue
            UpdateMode.Redirect -> if (busy == BusyMode.Interrupt || busy == BusyMode.Steer) RunKind.Redirect else RunKind.InterruptFirst
        }
    }
}
