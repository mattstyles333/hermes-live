package com.folio.hermeslive.call

/** How Hermes Live holds audio focus. */
enum class FocusMode {
    /** `AUDIOFOCUS_GAIN_TRANSIENT`: other players pause. */
    Hold,

    /** `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`: other players keep going, quieter. */
    Duck,

    /** No focus: other players resume at full volume. */
    Release,
}

/** What the call looks like right now, as far as music is concerned. */
data class GapSignals(
    /** The user is talking (mic energy or an input transcript delta). */
    val userSpeaking: Boolean = false,
    /** Hermes's voice is audible (remote audio level or an output transcript delta). */
    val remoteSpeaking: Boolean = false,
    /** A delegation is with Hermes and has not settled. */
    val delegationPending: Boolean = false,
    /** The route is a headset (wired, USB, Bluetooth, hearing aid), not the phone's speaker or earpiece. */
    val headset: Boolean = false,
    /** Another call (cellular or VoIP) owns the audio: hold nothing. */
    val suspended: Boolean = false,
)

/** Settings and timings for [GapMusicPolicy]. */
data class GapSettings(
    /** "Music in the gaps". Off: hold focus for the whole call (other players stay paused). */
    val enabled: Boolean = true,
    /** On the speaker, duck music in the gaps instead of resuming it, so less of it reaches the mic. */
    val duckOnSpeaker: Boolean = true,
    /** Nobody may have spoken for this long before the music comes back. */
    val quietBeforeReleaseMs: Long = 1_500L,
    /** Once music is back, keep it at least this long before pausing again, unless someone speaks. */
    val minReleasedMs: Long = 4_000L,
    /** Take focus this long before a commentary is sent, so the answer never plays over music. */
    val commentaryLeadMs: Long = 300L,
)

/** One step of [GapMusicPolicy]: the focus to hold, and when to ask again if nothing changes. */
data class FocusDecision(val mode: FocusMode, val changed: Boolean, val recheckAtMs: Long?)

/**
 * "Music in the gaps": other players (Spotify etc.) pause while the user or Hermes talks and come
 * back while Hermes works on a delegation.
 *
 * Music is let back only when a delegation is pending AND nobody has spoken for
 * [GapSettings.quietBeforeReleaseMs]. Focus is taken back at once when the user speaks (barge-in
 * into the music), when Hermes's audio starts, and [GapSettings.commentaryLeadMs] before a
 * commentary is sent ([beforeCommentary]). Anything else that would pause the music again (the
 * delegation ended without a word) waits until the music has had [GapSettings.minReleasedMs], so
 * it does not flap. On the speaker with [GapSettings.duckOnSpeaker], the gap ducks instead of
 * releasing; on a headset the music comes back at full volume.
 *
 * Pure: no Android, the caller passes the clock. The caller applies [FocusDecision.mode].
 */
class GapMusicPolicy(private val settings: GapSettings = GapSettings()) {
    /** Focus the call holds now. A call starts by holding it. */
    var mode: FocusMode = FocusMode.Hold
        private set

    private var lastSpeechAtMs: Long = Long.MIN_VALUE / 2
    private var gapStartedAtMs: Long = Long.MIN_VALUE / 2

    /** True when the current mode lets other players be heard. */
    val musicAllowed: Boolean get() = mode != FocusMode.Hold

    /** Re-evaluate with the latest [signals] at [nowMs]. */
    fun update(signals: GapSignals, nowMs: Long): FocusDecision {
        if (signals.suspended) return set(FocusMode.Release, nowMs, null)
        if (!settings.enabled) return set(FocusMode.Hold, nowMs, null)
        val speech = signals.userSpeaking || signals.remoteSpeaking
        if (speech) {
            lastSpeechAtMs = nowMs
            return set(FocusMode.Hold, nowMs, null)
        }
        val gapMode = if (!signals.headset && settings.duckOnSpeaker) FocusMode.Duck else FocusMode.Release
        val quietUntil = lastSpeechAtMs + settings.quietBeforeReleaseMs
        if (signals.delegationPending) {
            return if (nowMs >= quietUntil) set(gapMode, nowMs, null) else set(mode.keepHoldOr(), nowMs, quietUntil)
        }
        // Not in a gap: the music should pause again, but not sooner than minReleasedMs after it came back.
        if (mode != FocusMode.Hold) {
            val earliest = gapStartedAtMs + settings.minReleasedMs
            if (nowMs < earliest) return set(gapMode, nowMs, earliest)
        }
        return set(FocusMode.Hold, nowMs, null)
    }

    /**
     * A commentary is about to be sent: hold focus now and restart the quiet clock. Returns how
     * long to wait before sending (the lead when music was playing, else 0).
     */
    fun beforeCommentary(nowMs: Long): Long {
        lastSpeechAtMs = nowMs
        if (!settings.enabled || mode == FocusMode.Hold) {
            mode = if (settings.enabled) FocusMode.Hold else mode
            return 0L
        }
        mode = FocusMode.Hold
        return settings.commentaryLeadMs
    }

    private fun FocusMode.keepHoldOr(): FocusMode = if (this == FocusMode.Hold) FocusMode.Hold else this

    private fun set(next: FocusMode, nowMs: Long, recheckAtMs: Long?): FocusDecision {
        val changed = next != mode
        if (changed && mode == FocusMode.Hold) gapStartedAtMs = nowMs
        mode = next
        return FocusDecision(next, changed, recheckAtMs)
    }
}
