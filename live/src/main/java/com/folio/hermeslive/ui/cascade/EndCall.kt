package com.folio.hermeslive.ui.cascade

import androidx.compose.runtime.Immutable
import com.folio.hermes.cascade.TaskUi
import com.folio.hermes.cascade.TaskUiStatus
import kotlin.math.min

/**
 * Why a call ended. Every reason gets the same wind-down; only [dropped] ones end on the calm
 * "Call dropped" card instead of the goodbye.
 *
 * Bill's engine (`CascadeCallEngine`) raises only two of these today: [Error] (the call could
 * not start) and an end through `VoiceCall.end()`, which the screen sees as [EndButton] when its
 * own End button was tapped and [HungUp] otherwise (a spoken goodbye, the notification's End, the
 * tile and a headset hold all reach the engine the same way). Bill has no idle hang-up, call cap,
 * Bluetooth-lost end or mid-call drop (offline becomes memo mode); those values are here so an
 * engine that raises them gets the same transition.
 */
enum class EndReason(val dropped: Boolean = false) {
    EndButton,
    Goodbye,
    Notification,
    Headset,
    Tile,
    IdleHangUp,
    CallCap,
    BluetoothLost,

    /** Ended without a tap on this screen, by a path the engine does not name. */
    HungUp,
    Error(dropped = true),
    Dropped(dropped = true),
}

/** How a call ended, as the route hands it to the screen. [durationMs] null: it never went live. */
@Immutable
data class CallEnd(val reason: EndReason, val durationMs: Long? = null, val error: String? = null)

/** What the screen settles on after the wind-down. */
enum class EndOutcome {
    /** Tasks run on: the satellites settle into a "still working" row; the tray stays. */
    StillWorking,

    /** Nothing runs: a summary card, held [EndTimeline.HOLD_MS] (a tap skips), then home. */
    Summary,

    /** An error or a dropped line: the calm "Call dropped" card with Reconnect. */
    Dropped,
}

/** Where the end transition is at a moment. */
enum class EndPhase { WindingDown, Settled, Summary, Finished }

/** The call's numbers at its end: [durationMs] frozen, the rest from the task list. */
@Immutable
data class EndSummary(val durationMs: Long?, val running: Int, val done: Int, val unheard: Int) {
    /** "Call ended · 4:12 · 2 tasks still running", or "Call ended · 4:12". */
    fun line(dropped: Boolean = false): String = buildList {
        add(if (dropped) "Call dropped" else "Call ended")
        if (!dropped) durationMs?.let { add(elapsed(it)) }
        if (running > 0) add(if (running == 1) "1 task still running" else "$running tasks still running")
    }.joinToString(" · ")

    /** The summary card's detail: "2 tasks done · 1 result unheard", or "No tasks this call". */
    fun detail(): String {
        val parts = buildList {
            if (done > 0) add(if (done == 1) "1 task done" else "$done tasks done")
            if (unheard > 0) add(if (unheard == 1) "1 result unheard" else "$unheard results unheard")
        }
        return if (parts.isEmpty()) "No tasks this call" else parts.joinToString(" · ")
    }

    companion object {
        /**
         * [tasks] at the end; [callStartMs] on the tasks' clock (null: count every finished
         * task). [durationMs] comes from the engine, else the screen's clock.
         */
        fun of(tasks: List<TaskUi>, callStartMs: Long?, durationMs: Long?): EndSummary = EndSummary(
            durationMs = durationMs,
            running = runningOf(tasks),
            done = tasks.count { it.status == TaskUiStatus.Done && (callStartMs == null || (it.finishedAtMs ?: Long.MIN_VALUE) >= callStartMs) },
            unheard = tasks.count { it.status == TaskUiStatus.Done && it.unread },
        )

        fun runningOf(tasks: List<TaskUi>): Int = tasks.count { it.status.live }
    }
}

/**
 * One end transition: what it ends on is fixed when it starts (the running count still
 * updates live on screen). Pure: [phaseAt] and the [EndTimeline] curves give every moment from
 * the elapsed time, so renders and tests replay it exactly.
 */
@Immutable
class EndRun(val end: CallEnd, val summary: EndSummary, val reduced: Boolean) {
    val outcome: EndOutcome = when {
        end.reason.dropped -> EndOutcome.Dropped
        summary.running > 0 -> EndOutcome.StillWorking
        else -> EndOutcome.Summary
    }

    fun phaseAt(ms: Long): EndPhase = when {
        ms < EndTimeline.windMs(reduced) -> EndPhase.WindingDown
        outcome == EndOutcome.Summary && ms >= EndTimeline.leaveAtMs(reduced) -> EndPhase.Finished
        outcome == EndOutcome.Summary -> EndPhase.Summary
        else -> EndPhase.Settled
    }

    /** The summary has been held long enough: leave for home. Only the summary leaves by itself. */
    fun leavesAt(ms: Long): Boolean = phaseAt(ms) == EndPhase.Finished

    /** What TalkBack hears once, when the transition starts. */
    val announcement: String get() = if (outcome == EndOutcome.Dropped) "Call dropped" else "Call ended"

    companion object {
        /**
         * The run for [end]: [tapped] turns an unnamed hang-up into [EndReason.EndButton] (the
         * screen saw its End button pressed).
         */
        fun start(end: CallEnd, tapped: Boolean, tasks: List<TaskUi>, callStartMs: Long?, nowMs: Long, reduced: Boolean): EndRun {
            val reason = if (tapped && end.reason == EndReason.HungUp) EndReason.EndButton else end.reason
            val duration = end.durationMs ?: callStartMs?.let { (nowMs - it).coerceAtLeast(0) }
            return EndRun(end.copy(reason = reason), EndSummary.of(tasks, callStartMs, duration), reduced)
        }
    }
}

/**
 * The end transition's timing (ms from the end). Reduce motion keeps the order and the hold
 * but uses short fades.
 *
 * - 0–[PRESS_MS]: the End button presses in.
 * - 0–[WIND_MS]: the orb winds down (voice rings stop, ember dims to a soft ink glow, it shrinks),
 *   the pause ring and the Jev strip fade, satellites leave their orbits for the row.
 * - [CARD_AT_MS]–+[CARD_MS]: the summary (or dropped) card collapses in from the orb.
 * - the summary holds [HOLD_MS], then the screen leaves for home (a tap leaves at once).
 */
object EndTimeline {
    const val PRESS_MS = 120L
    const val WIND_MS = 800L
    const val CARD_AT_MS = 600L
    const val CARD_MS = 400L
    const val HOLD_MS = 1_500L

    const val REDUCED_WIND_MS = 300L
    const val REDUCED_CARD_AT_MS = 250L
    const val REDUCED_CARD_MS = 200L

    fun windMs(reduced: Boolean): Long = if (reduced) REDUCED_WIND_MS else WIND_MS
    fun cardAtMs(reduced: Boolean): Long = if (reduced) REDUCED_CARD_AT_MS else CARD_AT_MS
    fun cardMs(reduced: Boolean): Long = if (reduced) REDUCED_CARD_MS else CARD_MS

    /** When the summary leaves for home: the card is fully in, then held. */
    fun leaveAtMs(reduced: Boolean): Long = cardAtMs(reduced) + cardMs(reduced) + HOLD_MS

    /** The wind-down, eased 0..1. Reduce motion: linear (a fade). */
    fun wind(ms: Long, reduced: Boolean): Float {
        val k = fraction(ms, 0, windMs(reduced))
        return if (reduced) k else easeInOutCubic(k)
    }

    /** The card coming in, eased 0..1. */
    fun card(ms: Long, reduced: Boolean): Float {
        val k = fraction(ms, cardAtMs(reduced), cardMs(reduced))
        return if (reduced) k else SceneModel.easeOutCubic(k)
    }

    /** The End button's press, 0..1 (it stays pressed). */
    fun press(ms: Long): Float = SceneModel.easeOutCubic(fraction(ms, 0, PRESS_MS))

    private fun fraction(ms: Long, from: Long, length: Long): Float =
        if (length <= 0) (if (ms >= from) 1f else 0f) else ((ms - from).toFloat() / length).coerceIn(0f, 1f)

    fun easeInOutCubic(k: Float): Float = if (k < 0.5f) 4f * k * k * k else 1f - (-2f * k + 2f).let { it * it * it } / 2f

    /** The "still working" row's spacing (dp) for [n] dots on a screen [widthDp] wide. */
    fun rowSpacingDp(n: Int, widthDp: Float): Float = if (n <= 1) 0f else min(64f, (widthDp - 64f) / (n - 1))
}
