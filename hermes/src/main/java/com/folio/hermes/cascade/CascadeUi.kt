package com.folio.hermes.cascade

import com.folio.hermes.openrouter.JevAnswer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Where the conversation is, for the call screen. */
enum class TurnPhase { Idle, UserSpeaking, Pausing, Deciding, Writing, BillSpeaking }

/** One Jev pick: the [choice], its [confidence] and the top three options by probability. */
data class JevPick(val choice: String?, val confidence: Double, val top: List<Pair<String, Double>>) {
    companion object {
        fun of(answer: JevAnswer): JevPick = JevPick(
            answer.choice,
            answer.confidence,
            answer.probabilities.entries.sortedByDescending { it.value }.take(3).map { it.key to it.value },
        )
    }
}

data class TurnUi(
    val phase: TurnPhase = TurnPhase.Idle,
    /** The live partial transcript (everything heard since the last committed turn). */
    val partial: String = "",
    /** Silence so far against `pause_ms`, 0..1. */
    val pauseProgress: Float = 0f,
    /** Words held by keep_listening. */
    val held: String = "",
)

data class JevUi(
    val inFlight: Boolean = false,
    /** Last answer by question id (`turn`, `action`, `task`, `line`, `enough`, `update_mode`). */
    val answers: Map<String, JevPick> = emptyMap(),
    val latencyMs: Long? = null,
) {
    val turn: JevPick? get() = answers[CascadeBrain.TURN]
    val action: JevPick? get() = answers[CascadeBrain.ACTION]
    val task: JevPick? get() = answers[CascadeBrain.TASK]
    val line: JevPick? get() = answers[CascadeBrain.LINE]
    val enough: JevPick? get() = answers[CascadeBrain.ENOUGH]
    val updateMode: JevPick? get() = answers[CascadeBrain.UPDATE_MODE]
}

data class WriterUi(val streaming: Boolean = false, val sentences: List<String> = emptyList())

data class BillUi(
    val speaking: Boolean = false,
    /** Slot of the stock line playing, null for a written line. */
    val slot: String? = null,
    /** Output level 0..1, fed by the player ([Cascade.outputLevel]). */
    val level: Float = 0f,
)

/** A task's status on the call screen. */
enum class TaskUiStatus { Starting, Running, Waiting, Queued, Done, Failed, Cancelled }

/** One tool call, for the task chip's timeline. */
data class ToolUi(val label: String, val startedAtMs: Long, val endedAtMs: Long?)

data class TaskUi(
    val id: String,
    val label: String,
    val status: TaskUiStatus,
    val startedAtMs: Long,
    val finishedAtMs: Long?,
    /** Tool calls completed. */
    val steps: Int,
    /** The tool running now, as a speakable label. */
    val currentTool: String?,
    /** Last five tool calls. */
    val recentTools: List<ToolUi>,
    val lastEventAtMs: Long,
    /** Tail of the reply streaming now (≤200 chars). */
    val partialText: String,
    /** Start of the result (≤280 chars). */
    val resultPreview: String?,
    /** Updates queued behind the current step. */
    val pendingUpdates: Int,
)

data class ConnectionUi(val hermesUp: Boolean = false, val outbox: Int = 0)

/** Everything the call screen shows. Immutable; derived from what the cascade already tracks (no extra requests). */
data class CascadeUiState(
    val turn: TurnUi = TurnUi(),
    val jev: JevUi = JevUi(),
    val writer: WriterUi = WriterUi(),
    val bill: BillUi = BillUi(),
    val tasks: List<TaskUi> = emptyList(),
    val connection: ConnectionUi = ConnectionUi(),
    val render: RenderProgress = RenderProgress(),
)

/** One-shot moments for animations. */
sealed interface CascadeEvent {
    data class JevFired(val early: Boolean) : CascadeEvent
    data class JevAnswered(val latencyMs: Long) : CascadeEvent
    data class TurnCommitted(val turnId: String) : CascadeEvent
    data class TaskStarted(val id: String) : CascadeEvent
    data class ToolStarted(val id: String, val label: String) : CascadeEvent
    data class ToolFinished(val id: String) : CascadeEvent
    data class TaskFinished(val id: String, val ok: Boolean) : CascadeEvent
    data class TaskCancelled(val id: String) : CascadeEvent
    data class UpdateSent(val id: String, val mode: UpdateMode) : CascadeEvent
    data class LinePlayed(val slot: String) : CascadeEvent
    data object UserCutIn : CascadeEvent
    data class ResultQueued(val id: String) : CascadeEvent
    data class ResultSpoken(val id: String) : CascadeEvent
    data object Offline : CascadeEvent
    data object Online : CascadeEvent
}

/** The one stream of [CascadeEvent]s, shared by the cascade's parts. Never blocks; a slow collector drops the oldest. */
class CascadeEventBus {
    private val flow = MutableSharedFlow<CascadeEvent>(extraBufferCapacity = 256, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<CascadeEvent> = flow.asSharedFlow()

    fun emit(event: CascadeEvent) {
        flow.tryEmit(event)
    }
}

/** The call screen's view of a task. */
fun TaskSnapshot.toUi(): TaskUi = TaskUi(
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
