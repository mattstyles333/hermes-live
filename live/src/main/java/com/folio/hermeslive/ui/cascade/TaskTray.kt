package com.folio.hermeslive.ui.cascade

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.folio.hermes.cascade.SendState
import com.folio.hermes.cascade.TaskTap
import com.folio.hermes.cascade.TaskUi
import com.folio.hermes.cascade.TaskUiStatus
import com.folio.hermeslive.ui.BodyStyle
import com.folio.hermeslive.ui.EndRed
import com.folio.hermeslive.ui.Ember
import com.folio.hermeslive.ui.Hairline
import com.folio.hermeslive.ui.LabelStyle
import com.folio.hermeslive.ui.PrintInk
import com.folio.hermeslive.ui.PrintMute
import com.folio.hermeslive.ui.SmallStyle
import com.folio.hermeslive.ui.Surface
import com.folio.hermeslive.ui.SurfaceHigh
import kotlin.math.abs

/** How long a swiped card can be brought back before the cancel is sent. */
const val UNDO_MS = 3_000L

/** What the tray does with a card. */
internal class TrayActions(
    val open: (String) -> Unit,
    val retry: (String) -> Unit,
    val play: (String) -> Unit,
    val swipeCancel: (String, String) -> Unit,
    val undo: (String) -> Unit,
)

/**
 * The task tray: running cards (stable keys by task id), then a collapsible Done group.
 * Swiped cards leave the tray at once; the cancel is sent after [UNDO_MS] unless undone (the
 * timer lives at screen level, so it survives the card leaving composition).
 */
@Composable
internal fun TaskTray(
    cards: State<TaskCards>,
    now: State<Long>,
    memory: ScreenMemory,
    actions: TrayActions,
    motion: CascadeMotion,
    modifier: Modifier = Modifier,
) {
    Probe("tray")
    val list = cards.value.list
    val pending = memory.pendingCancels
    val active = list.filter { !it.finished && it.id !in pending }
    val done = list.filter { it.finished }
    Column(modifier.fillMaxWidth()) {
        UndoBar(memory, actions)
        Column(
            Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState()).testTag("tray"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (card in active) {
                androidx.compose.runtime.key(card.id) { EnteringCard(card, now, actions, motion, swipe = card.cancellable) }
            }
            if (done.isNotEmpty()) {
                DoneHeader(done, memory)
                if (memory.doneExpanded) {
                    for (card in done) {
                        androidx.compose.runtime.key(card.id) { TaskCardView(card, now, actions, motion) }
                    }
                }
            }
        }
    }
}

@Composable
private fun EnteringCard(card: TaskCard, now: State<Long>, actions: TrayActions, motion: CascadeMotion, swipe: Boolean) {
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(
        visibleState = visible,
        enter = if (motion.reduceMotion) fadeIn(tween(200)) else
            fadeIn(tween(250)) + slideInVertically(spring(dampingRatio = 0.62f, stiffness = 420f)) { it / 2 } +
                scaleIn(spring(dampingRatio = 0.62f, stiffness = 420f), initialScale = 0.96f),
        exit = fadeOut(),
    ) {
        if (swipe) SwipeToCancel(card, actions) { TaskCardView(card, now, actions, motion) } else TaskCardView(card, now, actions, motion)
    }
}

/**
 * Swipe a card sideways past a third of its width (or fling it) to cancel. The drag offset is
 * plain state written synchronously (no coroutine per delta, which could cancel the settle);
 * the cancel request is made at once, before any animation, so it cannot be lost.
 */
@Composable
private fun SwipeToCancel(card: TaskCard, actions: TrayActions, content: @Composable () -> Unit) {
    var drag by remember { mutableFloatStateOf(0f) }
    val width = remember { floatArrayOf(1f) }
    Box(
        Modifier.fillMaxWidth()
            .onSizeChanged { width[0] = it.width.toFloat().coerceAtLeast(1f) }
            .draggable(
                rememberDraggableState { delta -> drag += delta },
                Orientation.Horizontal,
                onDragStopped = { velocity ->
                    val far = abs(drag) > width[0] * 0.35f || (abs(velocity) > 1800f && abs(drag) > width[0] * 0.1f)
                    if (far) {
                        actions.swipeCancel(card.id, card.task.label)
                    } else {
                        animate(drag, 0f, animationSpec = spring()) { v, _ -> drag = v }
                    }
                },
            )
            .testTag("swipe-${card.id}"),
    ) {
        // What is behind the card while it slides.
        Text(
            "Cancel",
            style = SmallStyle, color = EndRed,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 18.dp).graphicsLayer { alpha = (abs(drag) / (width[0] * 0.35f)).coerceIn(0f, 1f) },
        )
        Box(Modifier.graphicsLayer { translationX = drag; alpha = 1f - (abs(drag) / width[0]).coerceIn(0f, 0.6f) }) { content() }
    }
}

@Composable
internal fun TaskCardView(card: TaskCard, now: State<Long>, actions: TrayActions, motion: CascadeMotion) {
    Probe("card:${card.id}")
    val task = card.task
    val shape = RoundedCornerShape(16.dp)
    val notSent = task.send == SendState.NotSent
    val border = when {
        notSent || task.status == TaskUiStatus.Failed -> EndRed.copy(alpha = 0.7f)
        task.status == TaskUiStatus.Done -> Success.copy(alpha = 0.33f)
        task.status == TaskUiStatus.Waiting -> PrintInk.copy(alpha = 0.55f)
        else -> Hairline
    }
    val progress by animateFloatAsState(taskProgress(task), tween(400), label = "progress")
    val barColor = if (task.status == TaskUiStatus.Done) Success else if (notSent || task.status == TaskUiStatus.Failed) EndRed else Ember
    val actionsList = buildList {
        add(CustomAccessibilityAction("Open details") { actions.open(task.id); true })
        if (card.cancellable) add(CustomAccessibilityAction("Cancel") { actions.swipeCancel(task.id, task.label); true })
        if (notSent || task.status == TaskUiStatus.Failed) add(CustomAccessibilityAction("Retry") { actions.retry(task.id); true })
        if (task.unread) add(CustomAccessibilityAction("Play result") { actions.play(task.id); true })
    }
    Box(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(Surface)
            .border(1.dp, border, shape)
            .drawBehind {
                val h = 2.dp.toPx()
                drawRect(barColor, Offset(0f, size.height - h), Size(size.width * progress, h), alpha = 0.7f)
            }
            .clickable(
                role = Role.Button,
                onClickLabel = if (notSent) "Retry sending" else "Open details",
            ) { if (notSent) actions.retry(task.id) else actions.open(task.id) }
            .semantics(mergeDescendants = true) {
                stateDescription = statusWord(task.status)
                customActions = actionsList
            }
            .testTag("card-${task.id}"),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(task, motion)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(task.label, style = BodyStyle.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp), color = PrintInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(taskLine(task), style = SmallStyle.copy(fontSize = 12.sp), color = PrintMute, maxLines = 1, overflow = TextOverflow.Ellipsis)
                sendLine(task)?.let {
                    Text(it, style = SmallStyle.copy(fontSize = 12.sp), color = if (notSent) EndRed else PrintMute, maxLines = 1, modifier = Modifier.testTag("send-${task.id}"))
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Elapsed(task, now)
                if (task.unread) {
                    Box(
                        Modifier.padding(top = 4.dp).size(30.dp).clip(CircleShape).background(SurfaceHigh).border(1.dp, Success.copy(alpha = 0.6f), CircleShape)
                            .clickable(role = Role.Button, onClickLabel = "Play result") { actions.play(task.id) }
                            .semantics { contentDescription = "Play result" }
                            .testTag("play-${task.id}"),
                        contentAlignment = Alignment.Center,
                    ) { Text("▶", color = Success, fontSize = 12.sp) }
                }
            }
        }
    }
}

/** Running time, ticking once a second: only this text recomposes. */
@Composable
private fun Elapsed(task: TaskUi, now: State<Long>) {
    Text(
        shortElapsed(taskElapsedMs(task, now.value)),
        style = SmallStyle.copy(fontSize = 11.sp, fontFeatureSettings = "tnum"),
        color = PrintMute,
        maxLines = 1,
    )
}

@Composable
private fun StatusDot(task: TaskUi, motion: CascadeMotion) {
    val color = when {
        task.send == SendState.NotSent || task.status == TaskUiStatus.Failed -> EndRed
        task.status == TaskUiStatus.Done -> Success
        task.status == TaskUiStatus.Cancelled || task.status == TaskUiStatus.Queued -> PrintMute
        task.status == TaskUiStatus.Waiting -> PrintInk
        else -> Ember
    }
    val pulsing = !motion.reduceMotion && (task.status == TaskUiStatus.Running || task.status == TaskUiStatus.Starting || task.status == TaskUiStatus.Waiting)
    val alpha: State<Float> = if (pulsing) {
        rememberInfiniteTransition(label = "dot").animateFloat(0.5f, 1f, infiniteRepeatable(tween(400), RepeatMode.Reverse), label = "a")
    } else {
        remember { mutableFloatStateOf(1f) }
    }
    Box(Modifier.size(10.dp).graphicsLayer { this.alpha = alpha.value }.clip(CircleShape).background(color))
}

@Composable
private fun DoneHeader(done: List<TaskCard>, memory: ScreenMemory) {
    val unread = done.count { it.task.unread }
    val text = buildString {
        append("DONE · ").append(done.size)
        if (unread > 0) append(" · ").append(unread).append(" not heard")
        append(if (memory.doneExpanded) "  ▾" else "  ▸")
    }
    Text(
        text,
        style = LabelStyle.copy(fontSize = 11.sp), color = if (unread > 0) Success else PrintMute,
        modifier = Modifier.padding(start = 6.dp, top = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClickLabel = if (memory.doneExpanded) "Collapse finished tasks" else "Show finished tasks") {
                memory.doneExpanded = !memory.doneExpanded
            }
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .testTag("done-header"),
    )
}

@Composable
private fun UndoBar(memory: ScreenMemory, actions: TrayActions) {
    val pending = memory.pendingCancels.entries.toList()
    AnimatedVisibility(pending.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((id, label) in pending.takeLast(2)) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SurfaceHigh).border(1.dp, Hairline, RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Cancelling $label", style = SmallStyle, color = PrintInk, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    Text(
                        "Undo",
                        style = SmallStyle.copy(fontWeight = FontWeight.SemiBold), color = Ember,
                        modifier = Modifier.padding(start = 12.dp).clip(RoundedCornerShape(8.dp))
                            .clickable(role = Role.Button, onClickLabel = "Undo cancel") { actions.undo(id) }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .testTag("undo-$id"),
                    )
                }
            }
        }
    }
}
