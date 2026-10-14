package com.folio.hermes.cascade

/**
 * The cascade's knobs, all defined up front for phase 1 (spec v2 §12 single-owner rule: S0 defines
 * the fields, S-E builds the Settings UI, slices only read them). User settings are clamped to
 * their ranges; the rest follow the spec and are shortened only by tests. Each field cites its R-id.
 *
 * Not here: the voice engine choice (`gpt-live` | `cascade`) and the OpenRouter key, which live
 * in `:live`'s `LiveSettings` and secure store (S-E).
 *
 * The actor reads this once per event; the turn-taking rules snapshot it once per pause.
 */
data class CascadeSettings(
    // ── turn-taking and routing (R-RT) ───────────────────────────────────────────────────────
    /** Silence before a turn is committed (Settings, 400–3000, default 1000). R-RT-1. */
    val pauseMs: Long = 1_000L,
    /** The early Jev call fires at min(this, pause / 2) of silence. R-RT-1. */
    val earlyCapMs: Long = 500L,
    /** One Jev call's budget. R-RT-2, R-OFF-5. */
    val jevTimeoutMs: Long = 2_500L,
    /** After keep_listening, ask once more when the silence has lasted this much longer. Spec v2 §0. */
    val holdRecheckMs: Long = 2_500L,
    /**
     * The hold-silence rule (2026-10-14): words still held after the keep_listening recheck (or
     * after an ignored blip) wait at most this much real silence. Then they commit if Jev's
     * latest `action` for them is at or above [actionFloor] (not `end_call`); otherwise Bill says
     * "Go on" once, and after this much more silence asks "go ahead?". Speech resets it.
     */
    val holdSilenceMs: Long = 4_000L,
    /** The floor for Jev's other answers (`line`, `clarity`, `split`; R-RT-3). `action` has its own: [actionFloor]. */
    val confidenceFloor: Double = 0.55,
    /**
     * Below this, `action` plays `clarify.confirm` and never acts (R-RT-3, CEO routing). 0.40 since
     * the fastline slice (2026-10-14): over the recorded Jev fixtures (88 cases) it takes friction
     * from 2/40 to 0/40 (tuned) and 9/48 to 8/48 (held out) with harmful unchanged at 1/40 and 1/48;
     * the cases between 0.40 and 0.55 were the right action, or (one) a status read instead of a reply.
     */
    val actionFloor: Double = 0.40,
    /**
     * `turn = done` is acted on only at or above this (spec v2 §14 item 16: measured over 176 runs,
     * 0 false "done" at 0.60 against 1 at 0.55; 4.6% of true "done" wait for the 2.5 s re-ask).
     */
    val turnDoneFloor: Double = 0.60,
    /**
     * A sure goodbye ends the turn (hub decision 2026-10-14): `turn = done` below [turnDoneFloor]
     * still counts as done when `action = end_call` is at or above this. Only with `turn = done`:
     * a goodbye Jev judged `ignore` (said to someone else) never hangs up. Short "cheers, bye"
     * turns no longer wait for the re-ask.
     */
    val endCallDoneFloor: Double = 0.85,
    /** Below this, `task` asks which one (unless there is exactly one candidate). R-RT-3. */
    val taskFloor: Double = 0.5,
    /** `ignore` is accepted at or above this. R-RT-3. */
    val ignoreFloor: Double = 0.4,
    /** `enough = line_only` skips the writer only at or above this. R-RT-6. */
    val lineOnlyFloor: Double = 0.7,
    /** An update redirects when the target is the newest task and younger than this. R-RT-7. */
    val redirectWindowMs: Long = 20_000L,
    /** An update redirects when Jev's `redirect` confidence is at least this. R-RT-7. */
    val redirectFloor: Double = 0.6,
    /** Without Jev (unavailable or offline), a turn commits at `pause + this`. R-RT-8. */
    val noJevCommitExtraMs: Long = 500L,
    /**
     * Held turns retry after this when a request failed as offline but the network port still
     * says online (or came back while the turn was being held): no network change would flush them.
     */
    val offlineRetryMs: Long = 10_000L,
    /** Each retry that fails as offline again doubles the wait, up to this (a long outage costs a few requests, not one per 10 s). */
    val offlineRetryMaxMs: Long = 300_000L,
    /** Offer Jev the `listen` backchannel lines (Settings, default off). */
    val backchannel: Boolean = false,
    /** Exchanges in Jev's `state.recent`. R-CTX-1. */
    val recentExchanges: Int = 6,
    /** Hard cap on held dictation before it commits anyway (v1 CEO §3). */
    val holdCapMs: Long = 180_000L,
    val holdCapWords: Int = 1_500,

    // ── tasks (R-TASK, R-DUR) ────────────────────────────────────────────────────────────────
    /** Voice tasks running at once (Settings, 1–6, default 4). R-TASK-1. */
    val maxTasks: Int = 4,
    /** Briefs a `split=several` turn may start at once; the rest queue. R-TASK-2. */
    val maxBriefsPerTurn: Int = 4,
    /** Submit attempts before the card shows "Not sent: tap to retry". R-DUR-4. */
    val submitAttempts: Int = 2,
    /** A result is spoken in at most this many sentences (headline plus any decision). R-TASK-5. */
    val resultSentences: Int = 2,

    // ── speaking (R-SPK) ─────────────────────────────────────────────────────────────────────
    /** User speech this long while Bill plays cuts him off. R-SPK-4. */
    val bargeInMs: Long = 250L,
    /** OpenRouter TTS voice (Settings). */
    val voice: String = "bill",
    /** OpenRouter TTS model (Settings). */
    val ttsModel: String = "elevenlabs/eleven-v4-turbo",
    /** TTS cache size (LRU). R-SPK-3. */
    val ttsCacheBytes: Long = 50L * 1024 * 1024,
    /** Phrase-bank render requests at once. R-SPK-1. */
    val renderConcurrency: Int = 4,
    /** Writer first-token budget, then one retry, then `wait.signal`, then the outbox. R-OFF-5. */
    val writerFirstTokenTimeoutMs: Long = 4_000L,
    /** TTS budget per clip. R-OFF-5. */
    val ttsTimeoutMs: Long = 4_000L,

    // ── speech-to-text (R-STT) ───────────────────────────────────────────────────────────────
    /** Wait at most this for the accurate pass, else use the fast text (`pass: fast`). R-STT-2. `:live` uses 2,500 with High accuracy (spec v2 §14 item 11). */
    val accuratePassWaitMs: Long = 1_500L,
    /**
     * Run the accurate pass on each committed turn (R-STT-2): the turn waits (at most
     * [accuratePassWaitMs]) for `Event.AccurateFinal`, and an ignored or unanswered turn drops its
     * audio. Off by default (no speech-to-text in tests); `:live` turns it on with a second-pass model.
     */
    val accuratePass: Boolean = false,
    /**
     * Fast-pass words below this confidence are uncertain (v1 CEO §7, R-STT-4). 0.45 is the best
     * F1 on the S-C corpus bench (`hermes-cascade-stt-bench-2026-10-13.md`).
     */
    val uncertainBelow: Double = 0.45,
    /**
     * R-STT-5 (hub decision 2026-10-14: a key doubt never blocks). The task starts at once; Bill
     * then asks one short question. The user's next committed turn counts as its answer only
     * within this long of the question (wall clock): a correction redirects the task, a yes does
     * nothing more. Was the 8 s wait before the task started.
     */
    val keyDoubtWaitMs: Long = 12_000L,
    /** Bill asks the key-doubt question only when Jev's `key_doubt` confidence is at least this; below it the notes go to the agent silently. */
    val keyDoubtAskFloor: Double = 0.6,
    /** User vocabulary for hotwords and writer repair (Settings). R-STT-6, R-STT-7. */
    val vocabulary: List<String> = emptyList(),
    /** Hold-to-talk: commit on release, no pause ring. R-STT-8. */
    val holdToTalk: Boolean = false,

    // ── context budget (R-CTX) ───────────────────────────────────────────────────────────────
    /** Cap on Jev's `state`, in characters. R-CTX-1. */
    val jevStateCapChars: Int = 4_000,
    /** Rolling conversation summary cap. R-CTX-2. */
    val conversationSummaryChars: Int = 600,
    /** The writer works in chunks of [writerChunkChars] above [writerChunkAboveChars]. R-CTX-3. */
    val writerChunkChars: Int = 20_000,
    val writerChunkAboveChars: Int = 24_000,
    /** `voice_context` sent with each task submit, in characters (v1 §9). */
    val voiceContextChars: Int = 6_000,

    // ── offline and memo mode (R-OFF) ────────────────────────────────────────────────────────
    /** Held turns with gaps under this form one dictation session. R-OFF-3. */
    val memoGapMs: Long = 120_000L,
    /** A dictation session holds at most this much speech. R-OFF-3. */
    val memoCapMs: Long = 3_600_000L,

    // ── conversations and resume (R-CONV) ────────────────────────────────────────────────────
    /** Ended conversations kept. R-CONV-1. */
    val conversationsKept: Int = 10,
    /** Default to Continue when the last call ended within this. R-CONV-2. */
    val continueWithinMs: Long = 1_800_000L,
    /** After hang-up, keep the gateway open this long while tasks run. R-CONV-3. */
    val offCallKeepAliveMs: Long = 1_800_000L,
    /** Items in an opening briefing. R-CONV-4. */
    val briefingItems: Int = 3,

    // ── privacy (R-Q-2) ──────────────────────────────────────────────────────────────────────
    /** Keep the on-device decision log (Settings, default on). */
    val decisionLog: Boolean = true,
) {
    val pause: Long get() = pauseMs.coerceIn(MIN_PAUSE_MS, MAX_PAUSE_MS)
    val early: Long get() = minOf(earlyCapMs, pause / 2)
    val taskCap: Int get() = maxTasks.coerceIn(1, 6)

    companion object {
        const val MIN_PAUSE_MS = 400L
        const val MAX_PAUSE_MS = 3_000L
    }
}
