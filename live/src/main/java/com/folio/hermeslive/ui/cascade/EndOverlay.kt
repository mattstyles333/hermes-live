package com.folio.hermeslive.ui.cascade

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.State
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.folio.hermeslive.ui.BodyStyle
import com.folio.hermeslive.ui.Ember
import com.folio.hermeslive.ui.Hairline
import com.folio.hermeslive.ui.LabelStyle
import com.folio.hermeslive.ui.Pill
import com.folio.hermeslive.ui.PrintInk
import com.folio.hermeslive.ui.PrintMute
import com.folio.hermeslive.ui.SmallStyle
import com.folio.hermeslive.ui.StatusStyle
import com.folio.hermeslive.ui.Surface

/** The longest step the end clock takes per frame (ms): longer frames are stalls. */
private const val MAX_STEP_MS = 50.0

/**
 * Runs the end clock: [ms] counts from the first frame after the end, at the scene's speed
 * (1 / the animator duration scale), at most [MAX_STEP_MS] per frame. The summary leaves by
 * itself ([leave]); the other outcomes stop the clock once settled (the screen then stays until
 * Close or Reconnect).
 */
@Composable
internal fun EndClock(run: EndRun, ms: MutableLongState, motion: CascadeMotion, leave: () -> Unit) {
    LaunchedEffect(run) {
        var last = withFrameNanos { it }
        var elapsed = 0.0
        val settled = maxOf(EndTimeline.windMs(run.reduced), EndTimeline.cardAtMs(run.reduced) + EndTimeline.cardMs(run.reduced))
        while (true) {
            val now = withFrameNanos { it }
            // A stalled frame (the hang-up's own work, a GC) pauses the transition instead of skipping it.
            elapsed += ((now - last) / 1_000_000.0).coerceAtMost(MAX_STEP_MS) / motion.durationScale
            last = now
            ms.longValue = elapsed.toLong()
            if (run.leavesAt(ms.longValue)) {
                leave()
                break
            }
            if (run.outcome != EndOutcome.Summary && ms.longValue >= settled) break
        }
    }
}

/**
 * What the end shows over the scene: the "still working" line under the row, the summary card
 * on the orb (a tap anywhere skips it), or the calm "Call dropped" card. Every alpha and scale is
 * read from [ms] in the draw phase, so a frame of the transition is a pure function of [ms].
 */
@Composable
internal fun BoxScope.EndStatus(run: EndRun, ms: State<Long>, tasks: State<TaskCards>, actions: EndActions) {
    val reduced = run.reduced
    val running = EndSummary.runningOf(tasks.value.list.map { it.task })
    val summary = run.summary.copy(running = running)
    BoxWithConstraints(Modifier.matchParentSize()) {
        val orbY = maxHeight * ORB_Y
        when (run.outcome) {
            EndOutcome.StillWorking -> Column(
                Modifier.align(Alignment.TopCenter).offset(y = orbY + END_ROW_DY.dp + 34.dp).fillMaxWidth().padding(horizontal = 24.dp)
                    .graphicsLayer { alpha = lateFade(EndTimeline.wind(ms.value, reduced)) }
                    .testTag("end-still-working"),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    summary.line(), style = SmallStyle.copy(fontSize = 14.sp, fontFeatureSettings = "tnum"), color = PrintInk, textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("end-line"),
                )
                Text(
                    if (running > 0) "They keep working after the call. You'll get a notification." else "All done. Results are in the tray.",
                    style = SmallStyle.copy(fontSize = 12.sp), color = PrintMute, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp),
                )
            }
            EndOutcome.Summary -> {
                // A tap anywhere skips the hold (no ripple: the card is the feedback).
                Box(
                    Modifier.matchParentSize()
                        .clickable(interactionSource = null, indication = null, onClickLabel = "Close") { actions.close() }
                        .testTag("end-skip"),
                )
                SummaryCard(summary, Modifier.align(Alignment.TopCenter).centredAt(orbY).graphicsLayer {
                    val c = EndTimeline.card(ms.value, reduced)
                    alpha = c
                    if (!reduced) {
                        scaleX = 0.86f + 0.14f * c
                        scaleY = scaleX
                    }
                })
            }
            EndOutcome.Dropped -> DroppedCard(
                summary, run.end.error, actions,
                Modifier.align(Alignment.TopCenter).offset(y = orbY + (if (running > 0) END_ROW_DY + 40f else 96f).dp).padding(horizontal = 24.dp)
                    .graphicsLayer { alpha = EndTimeline.card(ms.value, reduced) },
            )
        }
    }
}

/** Places the node so its vertical centre is at [y] from the top. */
private fun Modifier.centredAt(y: androidx.compose.ui.unit.Dp): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(constraints)
    layout(p.width, p.height) { p.place(0, (y.toPx() - p.height / 2f).toInt()) }
}

/** The second half of the wind-down: text comes in once the orb has mostly settled. */
private fun lateFade(w: Float): Float = ((w - 0.4f) / 0.6f).coerceIn(0f, 1f)

@Composable
private fun SummaryCard(summary: EndSummary, modifier: Modifier) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier.width(260.dp).clip(shape).background(Surface).border(1.dp, Hairline, shape).padding(vertical = 20.dp, horizontal = 22.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "Call ended. ${summary.durationMs?.let { "Lasted ${elapsed(it)}. " }.orEmpty()}${summary.detail()}."
                liveRegion = LiveRegionMode.Polite
            }
            .testTag("end-summary"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("CALL ENDED", style = LabelStyle.copy(fontSize = 11.sp), color = Ember, modifier = Modifier.semantics { heading() })
        summary.durationMs?.let {
            Text(elapsed(it), style = StatusStyle.copy(fontSize = 34.sp, lineHeight = 40.sp, fontFeatureSettings = "tnum"), color = PrintInk, modifier = Modifier.padding(top = 8.dp))
        }
        Text(summary.detail(), style = SmallStyle, color = PrintMute, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp).testTag("end-detail"))
    }
}

@Composable
private fun DroppedCard(summary: EndSummary, error: String?, actions: EndActions, modifier: Modifier) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(Surface).border(1.dp, Hairline, shape).padding(18.dp).testTag("end-dropped"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(PrintMute))
            Spacer(Modifier.width(8.dp))
            Text(
                summary.line(dropped = true), style = BodyStyle, color = PrintInk,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite; heading() }.testTag("end-line"),
            )
        }
        if (!error.isNullOrBlank()) {
            Text(error, style = SmallStyle, color = PrintMute, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp).testTag("end-error"))
        }
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill("Reconnect", filled = true, tag = "end-reconnect", onClick = actions.again)
            Pill("Close", filled = false, tag = "end-close", onClick = actions.close)
        }
    }
}

/** After a call that left tasks running: talk again, or close (home). */
@Composable
internal fun EndButtons(actions: EndActions, alpha: () -> Float) {
    Row(
        Modifier.fillMaxWidth().height(CONTROLS_HEIGHT).padding(bottom = 22.dp, top = 10.dp).graphicsLayer { this.alpha = alpha() },
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Pill("Talk again", filled = true, tag = "end-talk-again", onClick = actions.again)
        Pill("Close", filled = false, tag = "end-close", onClick = actions.close)
    }
}

/** What the end's buttons do; [close] and [again] are each sent at most once per end (independently). */
internal class EndActions(val close: () -> Unit, val again: () -> Unit)
