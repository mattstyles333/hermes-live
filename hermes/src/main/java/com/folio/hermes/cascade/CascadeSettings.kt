package com.folio.hermes.cascade

/**
 * The cascade's knobs. User settings (pause, task cap, backchannel) are clamped to their ranges;
 * the rest follow the contract and are shortened only by tests.
 */
data class CascadeSettings(
    /** Silence before a turn is committed (Settings, 400–3000, default 1000). */
    val pauseMs: Long = 1_000L,
    /** Voice tasks running at once (Settings, 1–6, default 4). */
    val maxTasks: Int = 4,
    /** Offer Jev the `listen` backchannel lines (Settings, default off). */
    val backchannel: Boolean = false,
    /** Below this, `turn` means keep listening and `action`/`task` mean a clarifying question. */
    val confidenceFloor: Double = 0.55,
    /** `enough = line_only` skips the writer only at or above this. */
    val lineOnlyFloor: Double = 0.7,
    /** The early Jev call fires at min(this, pause / 2) of silence. */
    val earlyCapMs: Long = 500L,
    /** One Jev call's budget. */
    val jevTimeoutMs: Long = 2_500L,
    /** After keep_listening, ask once more when the silence has lasted this much longer. */
    val holdRecheckMs: Long = 2_500L,
    /** User speech this long while Bill plays cuts him off. */
    val bargeInMs: Long = 250L,
    /** Exchanges in `state.recent`. */
    val recentExchanges: Int = 6,
    /** Line ids in `state.recent_lines`. */
    val recentLines: Int = 5,
) {
    val pause: Long get() = pauseMs.coerceIn(MIN_PAUSE_MS, MAX_PAUSE_MS)
    val early: Long get() = minOf(earlyCapMs, pause / 2)
    val taskCap: Int get() = maxTasks.coerceIn(1, 6)

    companion object {
        const val MIN_PAUSE_MS = 400L
        const val MAX_PAUSE_MS = 3_000L
    }
}
