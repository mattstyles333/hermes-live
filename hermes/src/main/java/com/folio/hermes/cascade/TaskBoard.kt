package com.folio.hermes.cascade

import com.folio.hermes.BusyMode
import com.folio.hermes.HermesConfig
import com.folio.hermes.HermesConversation
import com.folio.hermes.HermesGateway
import com.folio.hermes.PromptSurface
import com.folio.hermes.SessionOptions
import com.folio.hermes.SubmitStatus
import com.folio.hermes.TurnStatus
import com.folio.hermes.TurnUpdate
import com.folio.hermes.telemetry.TelemetrySink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch

/** One entry in a task's event log (what [TaskBoard.snapshot] answers status from). */
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

    /** The call screen's view of this task. */
    fun toUi(): TaskUi = TaskUi(
        id = id,
        label = label,
        status = when (state) {
            TaskState.Done -> TaskUiStatus.Done
            TaskState.Failed -> TaskUiStatus.Failed
            TaskState.Cancelled -> TaskUiStatus.Cancelled
            TaskState.Running -> when {
                waitingOnUser -> TaskUiStatus.Waiting
                starting -> TaskUiStatus.Starting
                queuedAtServer -> TaskUiStatus.Queued
                else -> TaskUiStatus.Running
            }
        },
        startedAtMs = startedAtMs,
        finishedAtMs = finishedAtMs,
        steps = steps,
        currentTool = currentTool,
        recentTools = recentTools,
        lastEventAtMs = lastEventAtMs,
        partialText = progress.takeLast(200),
        resultPreview = result?.take(280),
        pendingUpdates = pendingUpdates,
    )
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

/**
 * Several Hermes tasks in parallel (U4), one [HermesConversation] (its own session) each, over the
 * one gateway socket.
 *
 * - [start] opens a session (`session.create` on first use) and submits with
 *   `surface: "voice-live"` and the voice context. At most [maxTasks] run at once: the slot is
 *   taken before anything is sent, so two starts can't both slip under the cap.
 * - [update] `queue` sends `prompt.submit {queued: true}`; `redirect` follows the server's busy
 *   mode when it is `interrupt` or `steer`, else interrupts and then submits. Never changes server config.
 * - [snapshot] / [snapshots] answer status **from the event log only**: they are not suspend
 *   functions and never touch the network, so asking how a task is going can never redirect it.
 * - [cancel] is `session.interrupt`; the task stays `cancelled` and its interrupted ending is not a result.
 * - Every turn's flow is collected here. A queued update yields two results (the running turn's,
 *   then the queued one's); a redirect yields one (the running flow owns it); an interrupted
 *   ending is never a result.
 *
 * Thread-safe; [results] and [requests] are single-consumer flows. [scope] should carry a
 * `SupervisorJob`, so one task's failure never cancels the others.
 */
class TaskBoard(
    private val scope: CoroutineScope,
    private val maxTasks: () -> Int,
    private val open: (label: String) -> HermesConversation,
    private val busyMode: suspend () -> BusyMode?,
    private val telemetry: TelemetrySink = TelemetrySink.Default,
    private val now: () -> Long = System::currentTimeMillis,
    /** Task events for the call screen; [Cascade] reads them from here. */
    val bus: CascadeEventBus = CascadeEventBus(),
) {
    private inner class Task(val id: String, val label: String, val conversation: HermesConversation, val startedAt: Long) {
        var state = TaskState.Running
        var cancelled = false
        var active = 0
        var lastFailed = false
        val events = ArrayDeque<TaskEvent>()
        val progress = StringBuilder()
        var result: String? = null
        var unread = false
        var waiting = false
        var accepted = false
        var queuedAtServer = false
        var finishedAt: Long? = null
        var steps = 0
        var currentTool: String? = null
        val tools = ArrayDeque<ToolUi>()
        var lastEventAt = startedAt
        var pendingUpdates = 0
        val jobs = mutableListOf<Job>()

        fun log(event: TaskEvent) {
            events.addLast(event)
            lastEventAt = event.atMs
            while (events.size > MAX_EVENTS) events.removeFirst()
        }

        fun snapshot() = TaskSnapshot(
            id, label, state, startedAt, events.toList(), progress.toString(), result, unread, waiting,
            starting = !accepted, queuedAtServer = queuedAtServer, finishedAtMs = finishedAt, steps = steps,
            currentTool = currentTool, recentTools = tools.toList(), lastEventAtMs = lastEventAt, pendingUpdates = pendingUpdates,
        )
    }

    /** One submitted turn: its [kind], and whether it still counts as a pending update. */
    private class TurnRun(val kind: Kind, var pending: Boolean)

    private enum class Kind(val how: String) { Start("start"), Queue("queue"), Redirect("redirect"), InterruptFirst("interrupt_first") }

    private val lock = Any()
    private val tasks = LinkedHashMap<String, Task>()
    private var counter = 0
    private val _snapshots = MutableStateFlow<List<TaskSnapshot>>(emptyList())
    private val resultChannel = Channel<TaskResult>(Channel.UNLIMITED)
    private val requestChannel = Channel<TaskRequest>(Channel.UNLIMITED)

    /** Every task, oldest first (for the call screen's chips). */
    val all: StateFlow<List<TaskSnapshot>> = _snapshots.asStateFlow()

    /** Finished turns (and failures), in order. */
    val results: Flow<TaskResult> = resultChannel.receiveAsFlow()

    /** Approvals, clarify questions and withdrawals, with the task label for the card. */
    val requests: Flow<TaskRequest> = requestChannel.receiveAsFlow()

    val runningCount: Int get() = synchronized(lock) { tasks.values.count { it.state == TaskState.Running } }

    /** True when [start] would refuse ([maxTasks] running). */
    val isFull: Boolean get() = runningCount >= maxTasks().coerceIn(1, 6)

    /** Start a task with [prompt] (written for the agent) under [label] (2–4 words). */
    fun start(prompt: String, label: String, voiceContext: String): StartResult {
        val task = synchronized(lock) {
            val running = tasks.values.count { it.state == TaskState.Running }
            if (running >= maxTasks().coerceIn(1, 6)) {
                report("at_cap", running)
                return StartResult.AtCap(running)
            }
            val id = "t${++counter}"
            Task(id, label, open(label), now()).also { tasks[id] = it }
        }
        bus.emit(CascadeEvent.TaskStarted(task.id))
        launchTurn(task, prompt, voiceContext, Kind.Start)
        report("start", runningCount)
        return StartResult.Started(snapshot(task.id)!!)
    }

    /** Pass [text] to task [id]. A finished task takes it as a follow-up turn (and counts against the cap again). */
    suspend fun update(id: String, text: String, mode: UpdateMode, voiceContext: String): UpdateResult {
        val task = synchronized(lock) {
            val t = tasks[id] ?: return UpdateResult.NotFound
            if (t.state != TaskState.Running) {
                val running = tasks.values.count { it.state == TaskState.Running }
                if (running >= maxTasks().coerceIn(1, 6)) return UpdateResult.AtCap(running)
                t.state = TaskState.Running
                t.cancelled = false
                t.finishedAt = null
            }
            t
        }
        val kind = when (mode) {
            UpdateMode.Queue -> Kind.Queue
            UpdateMode.Redirect -> {
                val busy = try {
                    busyMode()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                if (busy == BusyMode.Interrupt || busy == BusyMode.Steer) Kind.Redirect else Kind.InterruptFirst
            }
        }
        launchTurn(task, text, voiceContext, kind)
        bus.emit(CascadeEvent.UpdateSent(task.id, mode))
        report("update", runningCount, mapOf("how" to kind.how))
        return UpdateResult.Sent(kind.how)
    }

    /** Stop task [id] with `session.interrupt`. */
    suspend fun cancel(id: String): CancelResult {
        val task = synchronized(lock) {
            val t = tasks[id] ?: return CancelResult.NotFound
            if (t.state != TaskState.Running) return CancelResult.AlreadyFinished
            t.cancelled = true
            t.state = TaskState.Cancelled
            t.finishedAt = now()
            t.currentTool = null
            t.log(TaskEvent.Cancelled(t.finishedAt!!))
            t
        }
        publish()
        bus.emit(CascadeEvent.TaskCancelled(task.id))
        if (!task.conversation.interrupt()) {
            // No session yet (still opening): stop our side so nothing is submitted later.
            synchronized(lock) { task.jobs.toList() }.forEach { it.cancel() }
        }
        report("cancel", runningCount)
        return CancelResult.Cancelled
    }

    /** One task, from the phone's log only. Never touches the network. */
    fun snapshot(id: String): TaskSnapshot? = synchronized(lock) { tasks[id]?.snapshot() }

    /** Every task, from the phone's log only. Never touches the network. */
    fun snapshots(): List<TaskSnapshot> = synchronized(lock) { tasks.values.map { it.snapshot() } }

    /** Tasks for Jev's `state.tasks`. */
    fun briefs(): List<TaskBrief> = snapshots().map { it.brief(now()) }

    /** The result of [id] was read out. */
    fun markRead(id: String) {
        synchronized(lock) { tasks[id]?.unread = false }
        publish()
    }

    private fun launchTurn(task: Task, text: String, voiceContext: String, kind: Kind) {
        val run = synchronized(lock) {
            val pending = kind == Kind.Queue && task.active > 0
            if (pending) task.pendingUpdates++
            task.active++
            task.log(TaskEvent.Submitted(now(), kind.how))
            TurnRun(kind, pending)
        }
        publish()
        val job = scope.launch {
            try {
                task.conversation.turn(
                    text,
                    PromptSurface.VoiceLive,
                    voiceContext,
                    interruptFirst = kind == Kind.InterruptFirst,
                    queued = kind == Kind.Queue,
                ).transformWhile { update ->
                    emit(update)
                    // A redirect's answer arrives on the running turn's flow; this one stops here.
                    !(kind == Kind.Redirect && update is TurnUpdate.Accepted &&
                        (update.status == SubmitStatus.Redirected || update.status == SubmitStatus.Steered))
                }.collect { handle(task, run, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Anything unexpected ends this turn as a failure, never a silent "done".
                handle(task, run, TurnUpdate.Failed(e.javaClass.simpleName))
            } finally {
                turnEnded(task, run)
            }
        }
        synchronized(lock) { task.jobs += job }
    }

    private fun handle(task: Task, run: TurnRun, update: TurnUpdate) {
        var result: TaskResult? = null
        var request: TaskRequest? = null
        var event: CascadeEvent? = null
        synchronized(lock) {
            val at = now()
            if (update is TurnUpdate.Started || update is TurnUpdate.Completed || update is TurnUpdate.Failed) settle(task, run)
            when (update) {
                is TurnUpdate.Accepted -> {
                    task.accepted = true
                    task.queuedAtServer = update.status == SubmitStatus.Queued && run.kind == Kind.Start
                    task.lastEventAt = at
                }
                is TurnUpdate.Started -> {
                    task.queuedAtServer = false
                    task.progress.setLength(0)
                    task.log(TaskEvent.Started(at))
                }
                is TurnUpdate.Text -> {
                    task.progress.setLength(0)
                    task.progress.append(update.text.takeLast(PROGRESS_CHARS))
                    task.lastEventAt = at
                }
                is TurnUpdate.Interim -> task.log(TaskEvent.Interim(at, update.text.take(PROGRESS_CHARS)))
                is TurnUpdate.ToolStarted -> {
                    task.waiting = false
                    task.currentTool = update.label
                    task.tools.addLast(ToolUi(update.label, at, null))
                    while (task.tools.size > RECENT_TOOLS) task.tools.removeFirst()
                    task.log(TaskEvent.Tool(at, update.label, false, null))
                    event = CascadeEvent.ToolStarted(task.id, update.label)
                }
                is TurnUpdate.ToolCompleted -> {
                    val label = HermesConversation.toolLabel(update.name)
                    task.steps++
                    if (task.currentTool == label) task.currentTool = null
                    val open = task.tools.indexOfLast { it.label == label && it.endedAtMs == null }
                    if (open >= 0) task.tools[open] = task.tools[open].copy(endedAtMs = at)
                    task.log(TaskEvent.Tool(at, label, true, update.summary))
                    event = CascadeEvent.ToolFinished(task.id)
                }
                is TurnUpdate.ApprovalNeeded -> {
                    task.waiting = true
                    task.log(TaskEvent.Waiting(at, "approval"))
                    request = TaskRequest(task.id, task.label, update)
                }
                is TurnUpdate.ClarifyNeeded -> {
                    task.waiting = true
                    task.log(TaskEvent.Waiting(at, "clarify"))
                    request = TaskRequest(task.id, task.label, update)
                }
                is TurnUpdate.RequestWithdrawn -> {
                    task.waiting = false
                    request = TaskRequest(task.id, task.label, update)
                }
                is TurnUpdate.Warning -> Unit
                is TurnUpdate.Completed -> {
                    task.log(TaskEvent.Finished(at, update.status))
                    task.waiting = false
                    task.currentTool = null
                    when {
                        task.cancelled -> Unit
                        update.status == TurnStatus.Interrupted -> Unit // a redirect or our own interrupt, never a result
                        update.status == TurnStatus.Error -> {
                            task.lastFailed = true
                            if (task.active <= 1) result = TaskResult(task.id, task.label, "", ok = false)
                        }
                        else -> {
                            task.lastFailed = false
                            task.result = update.text
                            task.unread = true
                            result = TaskResult(task.id, task.label, update.text, ok = true)
                        }
                    }
                }
                is TurnUpdate.Failed -> {
                    task.log(TaskEvent.Failed(at))
                    task.currentTool = null
                    if (!task.cancelled) {
                        task.lastFailed = true
                        if (task.active <= 1) result = TaskResult(task.id, task.label, "", ok = false)
                    }
                }
            }
        }
        result?.let {
            resultChannel.trySend(it)
            report(if (it.ok) "done" else "failed", runningCount)
        }
        request?.let { requestChannel.trySend(it) }
        event?.let(bus::emit)
        publish()
    }

    /** A queued update started or ended: it is no longer pending. */
    private fun settle(task: Task, run: TurnRun) {
        if (run.pending) {
            run.pending = false
            task.pendingUpdates = (task.pendingUpdates - 1).coerceAtLeast(0)
        }
    }

    private fun turnEnded(task: Task, run: TurnRun) {
        val finished = synchronized(lock) {
            settle(task, run)
            task.active--
            if (task.active <= 0 && task.state == TaskState.Running) {
                task.state = when {
                    task.cancelled -> TaskState.Cancelled
                    task.lastFailed -> TaskState.Failed
                    else -> TaskState.Done
                }
                task.finishedAt = now()
                task.currentTool = null
                task.queuedAtServer = false
                task.state
            } else {
                null
            }
        }
        publish()
        when (finished) {
            TaskState.Done -> bus.emit(CascadeEvent.TaskFinished(task.id, ok = true))
            TaskState.Failed -> bus.emit(CascadeEvent.TaskFinished(task.id, ok = false))
            else -> Unit
        }
    }

    private fun publish() {
        _snapshots.value = snapshots()
    }

    private fun report(event: String, running: Int, extra: Map<String, Any?> = emptyMap()) {
        runCatching { telemetry.event("cascade.task", mapOf("event" to event, "running" to running) + extra, warn = event == "failed") }
    }

    companion object {
        private const val MAX_EVENTS = 50
        private const val PROGRESS_CHARS = 600
        private const val RECENT_TOOLS = 5

        /** The default session factory: one new Hermes session per task, titled with its label. */
        fun sessions(gateway: HermesGateway, config: HermesConfig): (String) -> HermesConversation =
            { label -> HermesConversation(gateway, config, SessionOptions(title = "Voice: $label")) }
    }
}
