package com.folio.hermes.cascade

import com.folio.hermes.cascade.routing.CascadeBrain
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
    /** The held pieces, oldest first (the faint stack above the caption; CEO §3). */
    val heldPieces: List<String> = emptyList(),
    /** Words of the last transcript with confidence and alternatives (uncertain ones underlined; R-STT-4). */
    val words: List<Word> = emptyList(),
    /** Which pass [words] came from (R-STT-2). */
    val pass: TranscriptPass = TranscriptPass.Fast,
    /** The words were heard offline (memo mode). */
    val offline: Boolean = false,
    /** Hold-to-talk is pressed: no pause ring (R-STT-8). */
    val holdToTalk: Boolean = false,
    /**
     * The committed turn [words] belong to: pass it back in `Event.WordChosen` with the word's
     * index in [words]. Null while the user is speaking (the words are the live ones).
     */
    val turnId: String? = null,
)

data class JevUi(
    val inFlight: Boolean = false,
    /** Last answer by question id (`turn`, `action`, `task`, `split`, `clarity`, `line`, `enough`, `update_mode`). */
    val answers: Map<String, JevPick> = emptyMap(),
    val latencyMs: Long? = null,
) {
    val turn: JevPick? get() = answers[CascadeBrain.TURN]
    val action: JevPick? get() = answers[CascadeBrain.ACTION]
    val task: JevPick? get() = answers[CascadeBrain.TASK]
    val line: JevPick? get() = answers[CascadeBrain.LINE]
    val enough: JevPick? get() = answers[CascadeBrain.ENOUGH]
    val updateMode: JevPick? get() = answers[CascadeBrain.UPDATE_MODE]
    val split: JevPick? get() = answers[CascadeBrain.SPLIT]

    /** Jev's clarity verdict (R-STT-5): `clear`, `minor_doubt`, `key_doubt` or `garbled`. */
    val clarity: JevPick? get() = answers[CascadeBrain.CLARITY]
}

data class WriterUi(val streaming: Boolean = false, val sentences: List<String> = emptyList())

data class BillUi(
    val speaking: Boolean = false,
    /** Slot of the stock line playing, null for a written line. */
    val slot: String? = null,
    /** Output level 0..1, fed by the player (`CascadeActor.outputLevel`). */
    val level: Float = 0f,
    /** The sentence playing now (the Ember caption), null when quiet. */
    val text: String? = null,
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
    /** The brief sent to the agent (CEO §2: on the card at once). */
    val brief: String? = null,
    /** ≤ 12 words, spoken after `Accepted` (R-TASK-2). */
    val summary: String? = null,
    /** Sending (spinner) until Hermes accepts; NotSent after two failed submits ("tap to retry"; R-DUR-4). */
    val send: SendState = SendState.Sending,
    /** A finished result not heard yet. */
    val unread: Boolean = false,
    /** Dictated offline (memo mode). */
    val offline: Boolean = false,
    /** The full result (the sheet); [resultPreview] is its start. */
    val result: String? = null,
)

data class ConnectionUi(
    val hermesUp: Boolean = false,
    /** Turns held for the network ("offline · N held"). */
    val outbox: Int = 0,
    val online: Boolean = true,
    /** Memo mode banner ("Memo · offline · N held"; R-OFF-1). */
    val memo: Boolean = false,
)

/** A turn held for the network (the offline pill; held turns fly out of it on reconnect). */
data class HeldTurnUi(val turnId: String, val text: String, val wallMs: Long)

/** An offline dictation session (R-OFF-3). */
data class MemoSessionUi(val id: String, val startedWallMs: Long, val endedWallMs: Long, val turns: Int)

/** CEO mode on the call screen (phase 1: S-A, S-B). */
data class CeoUi(
    val heldTurns: List<HeldTurnUi> = emptyList(),
    val memoSessions: List<MemoSessionUi> = emptyList(),
    /** Task ids with a result not heard yet. */
    val unreadResults: List<String> = emptyList(),
    val conversationId: String? = null,
    /** The opening briefing's items (R-CONV-4). */
    val briefing: List<String> = emptyList(),
)

/** The Resume card (R-CONV-2). Null: nothing to resume. */
data class ResumeUi(
    val conversationId: String,
    val lastEndedWallMs: Long,
    val unheardResults: Int,
    val heldTurns: Int,
    val waitingTasks: Int,
    val defaultChoice: ResumeChoice,
)

/**
 * Everything the call screen shows (S-D draws it). Immutable; projected from the actor's
 * [State] after every event (no extra requests). Frozen (R-ARC-4): additive changes only.
 */
data class CascadeUiState(
    val turn: TurnUi = TurnUi(),
    val jev: JevUi = JevUi(),
    val writer: WriterUi = WriterUi(),
    val bill: BillUi = BillUi(),
    val tasks: List<TaskUi> = emptyList(),
    val connection: ConnectionUi = ConnectionUi(),
    val render: RenderProgress = RenderProgress(),
    val ceo: CeoUi = CeoUi(),
    val resume: ResumeUi? = null,
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

    // Phase 1 (defined in S0, emitted by the owning slice).
    /** A turn went to the outbox (S-B). */
    data class TurnHeld(val turnId: String) : CascadeEvent

    /** A held turn was sent on reconnect: it flies out of the pill (S-B). */
    data class HeldSent(val turnId: String) : CascadeEvent

    /** Hermes accepted the task's submit: the card stops spinning; the summary may play (R-DUR-2; S-A). */
    data class TaskAccepted(val id: String) : CascadeEvent

    /** Two submits failed: the card turns red, "Not sent: tap to retry" (R-DUR-4; S-B). */
    data class TaskNotSent(val id: String) : CascadeEvent

    /** Jev doubted the transcript (`minor_doubt`, `key_doubt`, `garbled`; R-STT-5; S-A). */
    data class ClarityDoubt(val turnId: String, val clarity: String) : CascadeEvent

    /** Memo mode started or ended (R-OFF-1; S-B). */
    data class Memo(val on: Boolean) : CascadeEvent

    /** The opening briefing starts with [items] items (R-CONV-4; S-B). */
    data class Briefing(val items: Int) : CascadeEvent
}

/** The one stream of [CascadeEvent]s (the actor's `Emit` effects). Never blocks; a slow collector drops the oldest. */
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
