package com.folio.hermeslive.ui.cascade

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.folio.hermeslive.ui.EndRed
import com.folio.hermeslive.ui.Ember
import com.folio.hermeslive.ui.Glyph
import com.folio.hermeslive.ui.GlyphKind
import com.folio.hermeslive.ui.Hairline
import com.folio.hermeslive.ui.LabelStyle
import com.folio.hermeslive.ui.PrintInk
import com.folio.hermeslive.ui.PrintMute
import com.folio.hermeslive.ui.SmallStyle
import com.folio.hermeslive.ui.SurfaceHigh
import com.folio.hermeslive.ui.VoidBlack
import kotlinx.coroutines.delay

/** The controls row's height: the buttons and their padding (the end keeps the tray in place). */
internal val CONTROLS_HEIGHT = 90.dp

/** The top bar: call time, engine, connection. */
@Composable
internal fun TopBar(chrome: CascadeChrome, now: State<Long>, top: State<TopModel>, endedAfterMs: Long? = null, live: () -> Float = { 1f }) {
    Probe("top")
    Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(top = 14.dp).height(20.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { CallTime(chrome.callStartedAtMs, now, endedAfterMs) }
        Text(chrome.engine, style = LabelStyle.copy(fontSize = 11.sp), color = PrintMute, maxLines = 1)
        // The connection belongs to the call: it fades with the wind-down.
        Box(Modifier.weight(1f).graphicsLayer { alpha = live() }, contentAlignment = Alignment.CenterEnd) { ConnectionDot(top) }
    }
}

/** The call time; once the call ended, frozen at [endedAfterMs]. */
@Composable
private fun CallTime(startedAtMs: Long?, now: State<Long>, endedAfterMs: Long?) {
    val text = when {
        endedAfterMs != null -> elapsed(endedAfterMs)
        startedAtMs != null -> elapsed(now.value - startedAtMs)
        else -> return
    }
    Text(text, style = SmallStyle.copy(fontSize = 12.sp, fontFeatureSettings = "tnum"), color = PrintMute, modifier = Modifier.semantics { contentDescription = "Call time $text" }.testTag("call-time"))
}

@Composable
private fun ConnectionDot(top: State<TopModel>) {
    val t = top.value
    val color = when {
        t.memo -> Ember
        !t.online -> EndRed
        !t.hermesUp -> PrintMute
        else -> Success
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "Connection ${t.connection}" }.testTag("connection")) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Text(t.connection, style = SmallStyle.copy(fontSize = 12.sp), color = PrintMute, modifier = Modifier.padding(start = 6.dp))
    }
}

/**
 * The Jev strip: the JEV node (glows while a call is out; the synapse to the orb is drawn by the
 * scene) and the answer chips, which drop in 90 ms apart after each `JevAnswered`. Long-press
 * opens the last 20 decisions.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun JevStrip(jev: State<JevModel>, epoch: Int, motion: CascadeMotion, onAnchor: (Offset) -> Unit, onLongPress: () -> Unit) {
    Probe("jev")
    val model = jev.value
    val ring by animateColorAsState(if (model.inFlight) Ember else Hairline, tween(150), label = "jev")
    val description = if (model.chips.isEmpty()) "Jev, no decision yet" else "Jev decided: " + model.chips.joinToString(", ") { it.text }
    Row(
        Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 18.dp)
            .combinedClickable(onClick = {}, onLongClick = onLongPress, onLongClickLabel = "Show Jev's last 20 decisions")
            .semantics(mergeDescendants = true) {
                contentDescription = description
                liveRegion = LiveRegionMode.Polite
            }
            .testTag("jev-strip"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(30.dp)
                .onGloballyPositioned { onAnchor(it.boundsInRoot().center) }
                .clip(CircleShape).background(if (model.inFlight) Ember.copy(alpha = 0.12f) else Color.Transparent)
                .border(1.dp, ring, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text("JEV", style = SmallStyle.copy(fontSize = 9.sp, letterSpacing = 0.5.sp), color = if (model.inFlight) Ember else PrintMute) }
        Spacer(Modifier.width(10.dp))
        Chips(model, epoch, motion)
    }
}

@Composable
private fun Chips(model: JevModel, epoch: Int, motion: CascadeMotion) {
    var revealed by remember { mutableIntStateOf(Int.MAX_VALUE) }
    LaunchedEffect(epoch) {
        if (epoch == 0) return@LaunchedEffect
        revealed = 0
        repeat(model.chips.size) {
            delay(if (motion.reduceMotion) 0 else 90)
            revealed = it + 1
        }
        revealed = Int.MAX_VALUE
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), maxLines = 2) {
        model.chips.forEachIndexed { i, chip ->
            AnimatedVisibility(
                i < revealed,
                enter = if (motion.reduceMotion) fadeIn(tween(200)) else fadeIn(tween(250)) + slideInVertically(tween(250)) { -it / 3 },
                exit = fadeOut(tween(120)),
            ) { Chip(chip, dim = model.inFlight) }
        }
    }
}

@Composable
private fun Chip(chip: JevChip, dim: Boolean) {
    val text = buildAnnotatedString {
        append(chip.question)
        append(' ')
        withStyle(SpanStyle(color = Ember, fontWeight = FontWeight.SemiBold)) { append(chip.choice) }
        chip.confidence?.let { append(" $it") }
    }
    Text(
        text, style = SmallStyle.copy(fontSize = 11.sp, letterSpacing = 0.sp), color = PrintInk, maxLines = 1,
        modifier = Modifier.graphicsLayer { alpha = if (dim) 0.45f else 1f }
            .clip(RoundedCornerShape(50)).background(SurfaceHigh).border(1.dp, Hairline, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** "Memo · offline · 3 held" (or "offline · 2 held"), with the anchor for flyouts. */
@Composable
internal fun ConnectionBanner(banner: State<BannerModel>) {
    Probe("banner")
    val b = banner.value
    AnimatedVisibility(b.text != null, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                b.text.orEmpty(),
                style = SmallStyle.copy(fontSize = 12.sp), color = if (b.memo) Ember else PrintInk,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(SurfaceHigh)
                    .border(1.dp, if (b.memo) Ember.copy(alpha = 0.6f) else Hairline, RoundedCornerShape(50))
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .padding(horizontal = 14.dp, vertical = 6.dp)
                    .testTag("connection-banner"),
            )
        }
    }
}

/** Held turns flying out of the pill on reconnect, then gone. */
@Composable
internal fun Flyouts(memory: ScreenMemory, motion: CascadeMotion) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        for (f in memory.flyouts) {
            androidx.compose.runtime.key(f.turnId) {
                val k = remember { Animatable(0f) }
                LaunchedEffect(Unit) {
                    k.animateTo(1f, tween(if (motion.reduceMotion) 400 else 900))
                    memory.flyouts.remove(f)
                }
                Text(
                    "Sent · " + f.text,
                    style = SmallStyle.copy(fontSize = 12.sp), color = Success, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 260.dp)
                        .graphicsLayer {
                            alpha = 1f - k.value
                            if (!motion.reduceMotion) {
                                translationY = k.value * 90.dp.toPx()
                                scaleX = 1f - k.value * 0.3f
                                scaleY = scaleX
                            }
                        }
                        .clip(RoundedCornerShape(50)).background(SurfaceHigh).border(1.dp, Success.copy(alpha = 0.5f), RoundedCornerShape(50))
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                )
            }
        }
    }
}

/**
 * Mute, end, speaker. [onEnd] presses the End button; once the call is ending ([ending]) the
 * buttons stop taking taps, the End button stays pressed in ([press] 0..1) and the others fade
 * with [live].
 */
@Composable
internal fun Controls(
    chrome: CascadeChrome,
    intents: CascadeIntents,
    onEnd: () -> Unit = intents::end,
    ending: Boolean = false,
    press: () -> Float = { 0f },
    live: () -> Float = { 1f },
) {
    Probe("controls")
    Row(Modifier.fillMaxWidth().padding(bottom = 22.dp, top = 10.dp), horizontalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterHorizontally)) {
        val fade = Modifier.graphicsLayer { alpha = live() }
        RoundButton(
            if (chrome.muted) GlyphKind.MicOff else GlyphKind.Mic, if (chrome.muted) "Unmute" else "Mute", if (chrome.muted) PrintInk else SurfaceHigh,
            if (chrome.muted) VoidBlack else PrintInk, "cascade-mute", intents::mute, enabled = !ending, modifier = fade,
        )
        RoundButton(
            GlyphKind.EndCall, "End call", EndRed, PrintInk, "cascade-end", onEnd, enabled = !ending,
            modifier = Modifier.graphicsLayer {
                // Pressed in: smaller and a shade darker, and it stays so while the call winds down.
                val p = press()
                scaleX = 1f - 0.12f * p
                scaleY = scaleX
                alpha = (1f - 0.2f * p) * live()
            },
        )
        RoundButton(
            if (chrome.speakerOn) GlyphKind.Speaker else GlyphKind.Earpiece, if (chrome.speakerOn) "Speaker on" else "Speaker off",
            SurfaceHigh, PrintInk, "cascade-speaker", intents::speaker, enabled = !ending, modifier = fade,
        )
    }
}

@Composable
private fun RoundButton(
    glyph: GlyphKind,
    description: String,
    background: Color,
    tint: Color,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 58.dp,
    enabled: Boolean = true,
) {
    Box(
        modifier.size(size).clip(CircleShape).background(background).border(1.dp, Hairline, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = description, onClick = onClick)
            .semantics { contentDescription = description }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) { Glyph(glyph, tint, size = size * 0.42f) }
}
