package com.folio.hermes.cascade

/*
 * The CascadeActor's state (R-ARC-1): everything mutable about a call, as one immutable value.
 * Only `reduce` makes a new one. Frozen (R-ARC-4): additive changes only, with a hub-approved
 * change note. Fields marked "phase 1" are defined now and filled by the named slice.
 */

/** One running action: a committed turn being carried out (the C1 `execute`), step by step. */
data class ActionState(
    val turnId: String,
    /** The full verbatim text (pending + heard; the accurate pass's text once it is in): Hermes always gets all of it (R-CTX-3). */
    val text: String,
    val decision: Decision,
    val speechEndAt: Long,
    /** The decision-log record so far (completed when the action ends). */
    val record: DecisionRecord,
    /** Sent again from the outbox. */
    val resend: Boolean = false,
    /** The reply utterance this action speaks into. */
    val utteranceId: Long = 0L,
    /** The first stock slot and line played (`said`, for the writer's "carry on" prompt). */
    val slot: String? = null,
    val line: String? = null,
    val writerRan: Boolean = false,
    val pieces: Int = 0,
    /** Handler-defined step name (for tests and the decision log). */
    val stage: String = "",
    /** The correlation key of the result this action waits for (`writer:<seq>`, `plan:<seq>`, `busy:<seq>`, `interrupt:<taskId>`). */
    val waitingOn: String? = null,
    /** Gates that must open before the action starts (phase 1: `accurate` for R-STT-2, `key_doubt` for R-STT-5). */
    val gates: Set<String> = emptySet(),
    /** The transcript the action uses (phase 1, S-A/S-C: repaired text, uncertain words). */
    val transcript: Transcript? = null,
    /** Handler scratch values (small strings only). */
    val data: Map<String, String> = emptyMap(),
)

/** Committed turns are carried out one at a time, in order (C1's `executing` lock). */
data class ActionQueue(val active: ActionState? = null, val waiting: List<ActionState> = emptyList())

/** Who a writer stream speaks for. */
sealed interface WriterOwner {
    data class Action(val turnId: String) : WriterOwner

    /** A finished task's result; [slot]/[line] are its stock lead-in. */
    data class Result(val taskId: String, val slot: String, val line: String?) : WriterOwner
}

/** One writer stream into utterance [utteranceId]. [lead] (slot, line) goes in with the first sentence. */
data class WriterJob(
    val seq: Long,
    val utteranceId: Long,
    val owner: WriterOwner,
    val lead: Pair<String, String>? = null,
    val pieces: Int = 0,
    /** Slot to play if the writer fails with nothing spoken (null: nothing). */
    val failSlot: String? = null,
)

/** The piece Bill holds the floor with: being fetched, or playing. */
data class Playback(val piece: Piece, val playing: Boolean = false)

/** One user turn in `recent`: what they said and the utterance Bill answered with (only what was heard counts). */
data class RecentTurn(val user: String, val utteranceId: Long)

/** Sending held turns again after reconnecting (one at a time, in order). */
data class FlushState(
    /** The turn being sent. */
    val turnId: String,
    /** The resend's Jev call, while it is out. */
    val jevSeq: Long? = null,
    /** Turns sent so far in this flush. */
    val sent: Int = 0,
    /** The resend's Jev request and when it went out (for the decision log). */
    val request: JevRequest? = null,
    val startedAt: Long = 0L,
)

data class ConnectionState(
    /** The phone's network (R-OFF). Starts online; the `Network` port corrects it at start. */
    val online: Boolean = true,
    val hermesUp: Boolean = false,
    /** Phase 1 (S-B): memo mode is on (offline during a call, or started offline; R-OFF-1/2). */
    val memo: Boolean = false,
    /**
     * The `Network` port's last word (additive, test-shape slice). [online] also turns false when a
     * request fails as offline while the port still says online; then the outbox retries on a timer
     * (`CascadeSettings.offlineRetryMs`), since no network change will come to flush it.
     */
    val portOnline: Boolean = true,
)

/** Decision-log bookkeeping: records waiting for their first audio. */
data class DecisionState(
    /** Executed turns whose first audio has not played yet: record and speech-end time, oldest first. */
    val awaitingAudio: List<Pair<DecisionRecord, Long>> = emptyList(),
    /** First audio time per turn id, oldest first (at most 64). */
    val audioAt: List<Pair<String, Long>> = emptyList(),
    /** Turn ids already added to `recent` (at most 64). */
    val remembered: List<String> = emptyList(),
)

/** Phrase-slot rotations (loaded at start, saved per play) and the seed for reshuffles. */
data class RotationState(val rotations: Map<String, Rotation> = emptyMap(), val seed: Long = 1L)

/** The fast-pass words since the last commit, and transcripts per committed turn (R-STT). */
data class TranscriptState(
    val words: List<Word> = emptyList(),
    /** Phase 1 (S-A/S-C): the merged transcript per turn id (accurate or fast, with uncertain words). */
    val byTurn: Map<String, Transcript> = emptyMap(),
    /** Hold-to-talk is pressed (R-STT-8). */
    val holdToTalk: Boolean = false,
    /** Hold-to-talk was released mid-speech: the `SpeechEnded` that follows commits at once (R-STT-8). */
    val released: Boolean = false,
)

/** Jev's last answers, for the call screen. In flight comes from the turn state. */
data class JevStatus(val answers: Map<String, JevPick> = emptyMap(), val latencyMs: Long? = null)

/** A dictation session held offline: held turns with gaps under `memoGapMs` (R-OFF-3; phase 1, S-B). */
data class MemoSession(
    val id: String,
    val startedWallMs: Long,
    val endedWallMs: Long,
    val turnIds: List<String>,
    /** Speech so far (capped at `memoCapMs`). */
    val speechMs: Long = 0L,
)

/** CEO mode state not owned by the task table (phase 1). */
data class CeoState(
    /** Offline dictation sessions not yet sent (S-B). */
    val memoSessions: List<MemoSession> = emptyList(),
    /** The opening briefing's items (≤ `briefingItems`; R-CONV-4; S-B). */
    val briefing: List<String> = emptyList(),
)

/** Task results and requests that arrived with no call open; handled at the next start (C1 parity; S-E may notify instead, R-CONV-3). */
data class OffCallState(val results: List<TaskResult> = emptyList(), val requests: List<TaskRequest> = emptyList())

/** What the Resume card offers (R-CONV-2; phase 1, S-B). */
data class ResumeCandidate(
    val conversationId: String,
    val lastEndedWallMs: Long,
    val unheardResults: Int,
    val heldTurns: Int,
    val waitingTasks: Int,
    /** Continue if the last call ended within `continueWithinMs`, else New. */
    val defaultChoice: ResumeChoice,
)

/** A past conversation read back from the store, to reopen it ([Event.OpenConversation]). */
data class StoredConversation(val id: String, val summary: String, val recent: List<Exchange>)

/** The conversation this call belongs to (R-CONV-1; phase 1, S-B). */
data class ConversationState(
    val id: String? = null,
    /** Rolling summary (≤ `conversationSummaryChars`; R-CTX-2), written off the critical path. */
    val summary: String = "",
    val resume: ResumeCandidate? = null,
)

/**
 * The whole call. [nowMs], [wallMs] and [settings] are stamped by the actor from the `Clock` and
 * the settings provider before every `reduce`, so `reduce` itself never reads a clock.
 */
data class State(
    val nowMs: Long = 0L,
    val wallMs: Long = 0L,
    val settings: CascadeSettings = CascadeSettings(),
    val started: Boolean = false,
    /** The stored state was read back (first [Event.Start] or [Event.Restore]); recovery may run. */
    val restored: Boolean = false,
    /** VAD says the user is speaking. */
    val userSpeaking: Boolean = false,
    val turn: TurnState = TurnState(),
    val transcript: TranscriptState = TranscriptState(),
    val jev: JevStatus = JevStatus(),
    /** The pause ring: tick timer seq while it runs, when the silence began, and its fill 0..1. */
    val tickSeq: Long? = null,
    /** The outbox retry timer while held turns wait with the port online (additive). */
    val outboxRetrySeq: Long? = null,
    /** The last retry wait (0: none yet); doubles per failed retry, back to 0 on a network change from the port. */
    val outboxRetryDelayMs: Long = 0L,
    val tickBegan: Long = 0L,
    val pauseProgress: Float = 0f,
    val actions: ActionQueue = ActionQueue(),
    val writers: Map<Long, WriterJob> = emptyMap(),
    /** Correlation ids for writer, plan and busy-mode calls and other per-step timers. */
    val seq: Long = 0L,
    val writer: WriterUi = WriterUi(),
    val tasks: TaskTable = TaskTable(),
    val speech: SpeechQueueState = SpeechQueueState(),
    val playback: Playback? = null,
    val bill: BillUi = BillUi(),
    val recent: List<RecentTurn> = emptyList(),
    val outbox: OutboxState = OutboxState(),
    val flush: FlushState? = null,
    val connection: ConnectionState = ConnectionState(),
    val log: DecisionState = DecisionState(),
    /** Result utterances queued and not yet started, by task id. */
    val resultUtterances: Map<String, Long> = emptyMap(),
    val rotation: RotationState = RotationState(),
    val render: RenderProgress = RenderProgress(),
    val ceo: CeoState = CeoState(),
    val conversation: ConversationState = ConversationState(),
    val offCall: OffCallState = OffCallState(),
    /**
     * Small facts slices remember across events, by key (a turn id, `task:<id>`, a slice name):
     * "this turn's summary was spoken", "this run's line waits for Accepted". Stored with the
     * outbox, so a restart or a resend never repeats them. Use [Tx.mark] and friends.
     */
    val marks: Map<String, Set<String>> = emptyMap(),
) {
    /** True while Bill has something queued or playing (wait for this before closing the call). */
    val speaking: Boolean get() = !speech.isEmpty
}
