package com.folio.hermeslive.call

import com.folio.hermes.live.BridgeSettings

/**
 * The idle hang-up and the call cap for an engine without [com.folio.hermes.live.DelegationBridge]'s
 * timers (Grok Voice), on the same settings ([com.folio.hermeslive.data.LiveSettings.bridgeSettings]:
 * Settings › Hang up when quiet, 3 min by default with a 30 s warning; Settings › Call limit,
 * 30 min by default with a 1 min warning). Pure: the engine calls [tick] on its own beat with
 * whether anything is happening now.
 *
 * "Something is happening" ([tick]'s `busy`) is the engine's call. For Grok: the user speaking
 * (heard by the server or by the phone while the session is parked), Grok speaking or answering,
 * a task notice waiting to be spoken, or another phone call. **Running tasks do not count** (hub
 * decision, 1.3.0 integration): after the quiet time the call hangs up even with tasks running,
 * because the off-call service and the result notifications carry them; a result due to be spoken
 * counts as activity until it has been said. This differs from GPT-Live with parallel agents,
 * whose bridge re-arms its idle timer while agents work.
 */
class CallLimits(private val settings: BridgeSettings, startedAtMs: Long) {
    sealed interface Step {
        data object None : Step

        /** Quiet for `idleHangUpMs - idleWarningMs`: warn (an earcon and [idleWarning]). */
        data object IdleWarning : Step

        /** Something happened after the idle warning: take it back. */
        data object IdleWarningCleared : Step

        /** `capWarningMs` before the cap: warn ([CAP_WARNING]). */
        data object CapWarning : Step

        /** Hang up; [reason] is the `call.end` reason (`idle_timeout` or `call_cap`, as GPT-Live's). */
        data class HangUp(val reason: String) : Step
    }

    private val startedAt = startedAtMs
    private var lastActive = startedAtMs
    private var idleWarned = false
    private var capWarned = false

    /** The idle warning is showing. */
    val warnedIdle: Boolean get() = idleWarned

    /** The idle warning's words, with the seconds left. */
    val idleWarning: String get() = "It has gone quiet. Hanging up in ${(settings.idleWarningMs + 999) / 1000} seconds unless you speak."

    /** One step at most per call; a later [tick] catches up with the rest. */
    fun tick(now: Long, busy: Boolean): Step {
        val sinceStart = now - startedAt
        val cap = settings.callCapMs
        if (cap > 0 && sinceStart >= cap) return Step.HangUp(REASON_CAP)
        if (busy) {
            lastActive = now
            if (idleWarned) {
                idleWarned = false
                return Step.IdleWarningCleared
            }
        }
        val idle = settings.idleHangUpMs
        val quiet = now - lastActive
        if (idle > 0 && quiet >= idle) return Step.HangUp(REASON_IDLE)
        if (cap > 0 && !capWarned && settings.capWarningMs in 1 until cap && sinceStart >= cap - settings.capWarningMs) {
            capWarned = true
            return Step.CapWarning
        }
        if (idle > 0 && !idleWarned && settings.idleWarningMs in 1 until idle && quiet >= idle - settings.idleWarningMs) {
            idleWarned = true
            return Step.IdleWarning
        }
        return Step.None
    }

    companion object {
        const val REASON_IDLE = "idle_timeout"
        const val REASON_CAP = "call_cap"
        const val CAP_WARNING = "This call ends in 1 minute (call time limit)."
    }
}
