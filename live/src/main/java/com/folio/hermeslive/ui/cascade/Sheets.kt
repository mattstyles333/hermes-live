package com.folio.hermeslive.ui.cascade

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.folio.hermes.cascade.DecisionRecord
import com.folio.hermes.cascade.LoggedAnswer
import com.folio.hermes.cascade.SendState
import com.folio.hermes.cascade.TaskTap
import com.folio.hermes.cascade.TaskUiStatus
import com.folio.hermes.cascade.UpdateMode
import com.folio.hermes.cascade.routing.CascadeBrain
import com.folio.hermeslive.ui.BodyStyle
import com.folio.hermeslive.ui.EndRed
import com.folio.hermeslive.ui.Ember
import com.folio.hermeslive.ui.Hairline
import com.folio.hermeslive.ui.HeadingStyle
import com.folio.hermeslive.ui.LabelStyle
import com.folio.hermeslive.ui.MonoStyle
import com.folio.hermeslive.ui.Pill
import com.folio.hermeslive.ui.PrintInk
import com.folio.hermeslive.ui.PrintMute
import com.folio.hermeslive.ui.SmallStyle
import com.folio.hermeslive.ui.SurfaceHigh
import com.folio.hermeslive.ui.VoidBlack
import com.folio.hermeslive.ui.fieldColors
import java.util.Locale

/**
 * A sheet drawn inside the screen (no window, no popup: it shows in renders and tests). A tap
 * on the scrim closes it.
 */
@Composable
internal fun BoxScope.SheetFrame(tag: String, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Box(
        Modifier.matchParentSize().background(VoidBlack.copy(alpha = 0.6f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = "Close", onClick = onClose)
            .testTag("$tag-scrim"),
    )
    Column(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.72f)
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .background(SurfaceHigh)
            .border(1.dp, Hairline, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .pointerInput(Unit) { detectTapGestures { } } // taps on the panel do not reach the scrim
            .padding(horizontal = 20.dp, vertical = 14.dp)
            .testTag(tag),
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(Hairline))
        Spacer(Modifier.height(10.dp))
        content()
    }
}

/** What the task sheet can do. */
internal class SheetActions(
    val close: () -> Unit,
    val cancel: (String, String) -> Unit,
    val tap: (String, TaskTap) -> Unit,
)

/** The task sheet: live step timeline, the partial text tail, the full result, Cancel, Tell it…, Redirect. */
@Composable
internal fun ColumnScope.TaskSheetContent(card: TaskCard, nowMs: Long, actions: SheetActions) {
    Probe("sheet")
    val task = card.task
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(task.label, style = HeadingStyle, color = PrintInk, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).semantics { heading() })
        Text(
            "Close", style = SmallStyle, color = PrintMute,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = actions.close).padding(8.dp).testTag("sheet-close"),
        )
    }
    Text(
        "${statusWord(task.status)} · ${shortElapsed(taskElapsedMs(task, nowMs))}" + (sendLine(task)?.let { " · $it" } ?: ""),
        style = SmallStyle, color = if (task.send == SendState.NotSent) EndRed else PrintMute,
        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp).testTag("sheet-status"),
    )
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
        task.brief?.let {
            Section("BRIEF")
            Text(it, style = SmallStyle, color = PrintInk, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
        Section("STEPS")
        if (task.recentTools.isEmpty()) {
            Text(if (task.status == TaskUiStatus.Queued) "Not started yet." else "No tools yet.", style = SmallStyle, color = PrintMute)
        }
        task.recentTools.forEach { tool ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                val running = tool.endedAtMs == null
                Box(Modifier.size(7.dp).clip(CircleShape).background(if (running) Ember else PrintMute))
                Text(prettyTool(tool.label), style = SmallStyle, color = PrintInk, modifier = Modifier.weight(1f).padding(start = 10.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "+" + elapsed(tool.startedAtMs - task.startedAtMs) + " · " + (tool.endedAtMs?.let { seconds(it - tool.startedAtMs) } ?: "running"),
                    style = SmallStyle.copy(fontSize = 11.sp, fontFeatureSettings = "tnum"), color = PrintMute,
                )
            }
        }
        if (task.partialText.isNotBlank() && task.status != TaskUiStatus.Done) {
            Section("SO FAR")
            Text("…" + task.partialText.trim(), style = MonoStyle.copy(fontSize = 12.sp), color = PrintMute, modifier = Modifier.testTag("sheet-partial"))
        }
        val result = task.result ?: task.resultPreview
        if (result != null) {
            Section("RESULT")
            Text(result, style = BodyStyle, color = PrintInk, modifier = Modifier.testTag("sheet-result"))
        }
    }
    SheetButtons(card, actions)
}

@Composable
private fun SheetButtons(card: TaskCard, actions: SheetActions) {
    val task = card.task
    var text by remember(task.id) { mutableStateOf("") }
    val live = card.cancellable
    if (live) {
        OutlinedTextField(
            value = text, onValueChange = { text = it }, singleLine = false, maxLines = 3,
            placeholder = { Text("Tell it…", style = BodyStyle, color = PrintMute) },
            colors = fieldColors(), textStyle = BodyStyle,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp).testTag("sheet-tell-text"),
        )
    }
    FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (live) {
            Pill("Tell it", filled = text.isNotBlank(), tag = "sheet-tell") {
                if (text.isNotBlank()) actions.tap(task.id, TaskTap.Tell(text.trim(), UpdateMode.Queue)).also { text = "" }
            }
            Pill("Redirect", filled = false, tag = "sheet-redirect") {
                if (text.isNotBlank()) actions.tap(task.id, TaskTap.Tell(text.trim(), UpdateMode.Redirect)).also { text = "" }
            }
            Box(
                Modifier.clip(RoundedCornerShape(50)).border(1.dp, EndRed.copy(alpha = 0.8f), RoundedCornerShape(50))
                    .clickable(role = Role.Button) { actions.cancel(task.id, task.label) }
                    .padding(horizontal = 16.dp, vertical = 10.dp).testTag("sheet-cancel"),
            ) { Text("Cancel", style = BodyStyle, color = EndRed) }
        }
        if (task.send == SendState.NotSent || task.status == TaskUiStatus.Failed) {
            Pill("Retry", filled = true, tag = "sheet-retry") { actions.tap(task.id, TaskTap.Retry) }
        }
        if (task.status == TaskUiStatus.Done) {
            Pill("▶ Play", filled = task.unread, tag = "sheet-play") { actions.tap(task.id, TaskTap.Play) }
            Pill("Read it all", filled = false, tag = "sheet-readall") { actions.tap(task.id, TaskTap.ReadAll) }
        }
    }
    if (live) {
        Text(
            "Tell it: after the current step. Redirect: change what it is doing now.",
            style = SmallStyle.copy(fontSize = 11.sp), color = PrintMute, modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = LabelStyle.copy(fontSize = 10.sp), color = Ember, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp).semantics { heading() })
}

private fun seconds(ms: Long): String = String.format(Locale.UK, "%.1fs", ms.coerceAtLeast(0) / 1000.0)

/** Jev's last decisions: top-3 probabilities as small bars, and the latency. */
@Composable
internal fun ColumnScope.DecisionsContent(decisions: List<DecisionRecord>, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Jev · last ${decisions.size} decisions", style = HeadingStyle, color = PrintInk, modifier = Modifier.weight(1f).semantics { heading() })
        Text("Close", style = SmallStyle, color = PrintMute, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onClose).padding(8.dp).testTag("decisions-close"))
    }
    Text("On this phone only. Newest first.", style = SmallStyle, color = PrintMute, modifier = Modifier.padding(bottom = 8.dp))
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
        if (decisions.isEmpty()) Text("No decisions yet.", style = SmallStyle, color = PrintMute)
        decisions.forEach { d -> DecisionRow(d) }
    }
}

@Composable
private fun DecisionRow(d: DecisionRecord) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).semantics(mergeDescendants = true) {}) {
        val head = listOfNotNull(d.route ?: d.outcome, d.slot, d.jevMs?.let { "$it ms" }).joinToString(" · ")
        Text(head, style = SmallStyle.copy(fontWeight = FontWeight.SemiBold), color = PrintInk)
        for (q in listOf(CascadeBrain.TURN, CascadeBrain.ACTION, CascadeBrain.TASK)) {
            val a = d.answers[q] ?: continue
            ProbBars(q, a)
        }
    }
}

/** The top three options of one answer as bars. */
@Composable
private fun ProbBars(question: String, a: LoggedAnswer) {
    val top = topThree(a)
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.Top) {
        Text(question.replace('_', ' '), style = SmallStyle.copy(fontSize = 11.sp), color = PrintMute, modifier = Modifier.width(52.dp))
        Column(Modifier.weight(1f)) {
            top.forEach { (option, p) ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "$question $option ${(p * 100).toInt()} percent" }) {
                    Box(Modifier.width(90.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Hairline)) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(p.toFloat().coerceIn(0.02f, 1f)).background(if (option == a.choice) Ember else PrintMute))
                    }
                    Text("$option ${confidence(p)}", style = SmallStyle.copy(fontSize = 11.sp), color = if (option == a.choice) PrintInk else PrintMute, modifier = Modifier.padding(start = 8.dp), maxLines = 1)
                }
            }
        }
    }
}

/** Top three by probability; the pick alone when no probabilities were logged. */
fun topThree(a: LoggedAnswer): List<Pair<String, Double>> =
    if (a.probabilities.isEmpty()) listOfNotNull(a.choice?.let { it to a.confidence })
    else a.probabilities.entries.sortedByDescending { it.value }.take(3).map { it.key to it.value }
