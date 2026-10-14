package com.folio.hermes.cascade

import com.folio.hermes.BusyMode
import com.folio.hermes.cascade.ports.HermesPort
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update

/**
 * Test driver: C1's `TaskBoard` API over the production [TaskTable] rules and the production
 * [TaskRunner] executor on a real [HermesPort], so `TaskBoardTest` keeps proving the wire
 * behaviour (status never submits, `queued: true`, busy-mode fallback, the cap).
 */
class TaskBoard(
    scope: CoroutineScope,
    private val maxTasks: () -> Int,
    port: HermesPort,
    private val now: () -> Long = System::currentTimeMillis,
    val bus: CascadeEventBus = CascadeEventBus(),
) {
    private val lock = Any()
    private var table = TaskTable()
    private var seq = 0L
    private val busy = HashMap<Long, CompletableDeferred<BusyMode?>>()
    private val interrupts = HashMap<String, CompletableDeferred<Unit>>()
    private val runner = TaskRunner(scope, port, ::post)
    private val resultChannel = Channel<TaskResult>(Channel.UNLIMITED)
    private val requestChannel = Channel<TaskRequest>(Channel.UNLIMITED)

    private val _version = MutableStateFlow(0L)

    /** Bumped after every table change: tests wait on it instead of polling. */
    val version: StateFlow<Long> = _version.asStateFlow()

    val results: Flow<TaskResult> = resultChannel.receiveAsFlow()
    val requests: Flow<TaskRequest> = requestChannel.receiveAsFlow()
    val runningCount: Int get() = synchronized(lock) { table.runningCount }
    val isFull: Boolean get() = synchronized(lock) { table.isFull(maxTasks()) }

    fun start(prompt: String, label: String, voiceContext: String): StartResult = apply { it.start(prompt, label, voiceContext, maxTasks(), now()) }

    suspend fun update(id: String, text: String, mode: UpdateMode, voiceContext: String): UpdateResult {
        apply { it.reopen(id, maxTasks()) }?.let { return it }
        val busyMode = if (mode == UpdateMode.Redirect) {
            val waiter = CompletableDeferred<BusyMode?>()
            val s = synchronized(lock) { (++seq).also { busy[it] = waiter } }
            runner.busyMode(s)
            waiter.await()
        } else {
            null
        }
        val kind = TaskTable.kindFor(mode, busyMode)
        apply { it.launch(id, text, voiceContext, kind, now()) }
        bus.emit(CascadeEvent.UpdateSent(id, mode))
        return UpdateResult.Sent(kind.how)
    }

    suspend fun cancel(id: String): CancelResult {
        val waiter = CompletableDeferred<Unit>()
        synchronized(lock) { interrupts[id] = waiter }
        val result = apply { it.cancel(id, now()) }
        if (result == CancelResult.Cancelled) waiter.await()
        return result
    }

    fun snapshot(id: String): TaskSnapshot? = synchronized(lock) { table.snapshot(id) }
    fun snapshots(): List<TaskSnapshot> = synchronized(lock) { table.snapshots() }
    fun briefs(): List<TaskBrief> = synchronized(lock) { table.briefs(now()) }
    fun markRead(id: String) {
        synchronized(lock) { table = table.markRead(id) }
        _version.update { it + 1 }
    }

    /**
     * Test fence: a [TaskResult] and a [CascadeEvent.TaskStarted] with task id [id] go out on
     * [results] and [bus], behind everything already sent there. Once a collector has seen both,
     * it has seen every result and event the board made before this call.
     */
    fun fence(id: String) {
        synchronized(lock) {
            bus.emit(CascadeEvent.TaskStarted(id))
            resultChannel.trySend(TaskResult(id, "", "", true))
        }
    }

    /** One table step; its outputs go out under the lock, so they leave in the order the table made them (as the actor's one loop does). */
    private fun <R> apply(op: (TaskTable) -> TaskStep<R>): R {
        val step = synchronized(lock) {
            op(table).also { step ->
                table = step.table
                step.events.forEach(bus::emit)
                step.results.forEach { resultChannel.trySend(it) }
                step.requests.forEach { requestChannel.trySend(it) }
            }
        }
        _version.update { it + 1 }
        step.effects.forEach(::run)
        return step.value
    }

    private fun run(effect: Effect) {
        when (effect) {
            is Effect.SubmitTask -> runner.submit(effect)
            is Effect.OpenTaskSession -> runner.open(effect)
            is Effect.InterruptTask -> runner.interrupt(effect.taskId)
            is Effect.CancelTaskRuns -> runner.cancelRuns(effect.taskId)
            else -> error("unexpected effect $effect")
        }
    }

    private fun post(event: Event) {
        when (event) {
            is Event.TaskUpdated -> apply { it.update(event.taskId, event.runId, event.update, now()) }
            is Event.TaskRunEnded -> apply { it.runEnded(event.taskId, event.runId, now()) }
            is Event.TaskSessionOpened -> apply { it.sessionOpened(event.taskId, event.runId, event.storedSessionId) }
            is Event.TaskInterrupted -> {
                apply { it.interrupted(event.taskId, event.ok) }
                synchronized(lock) { interrupts.remove(event.taskId) }?.complete(Unit)
            }
            is Event.BusyModeRead -> synchronized(lock) { busy.remove(event.seq) }?.complete(event.mode)
            else -> Unit
        }
    }
}
