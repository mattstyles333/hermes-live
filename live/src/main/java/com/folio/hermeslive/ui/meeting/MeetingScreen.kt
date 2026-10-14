package com.folio.hermeslive.ui.meeting

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.folio.hermeslive.meeting.MarkKind
import com.folio.hermeslive.meeting.MeetingBrief
import com.folio.hermeslive.meeting.MeetingLive
import com.folio.hermeslive.meeting.MeetingOutbox
import com.folio.hermeslive.meeting.MeetingPhase
import com.folio.hermeslive.meeting.MeetingScreen
import com.folio.hermeslive.meeting.MeetingService
import com.folio.hermeslive.ui.BodyStyle
import com.folio.hermeslive.ui.CaptionStyle
import com.folio.hermeslive.ui.Ember
import com.folio.hermeslive.ui.EndRed
import com.folio.hermeslive.ui.Hairline
import com.folio.hermeslive.ui.HeadingStyle
import com.folio.hermeslive.ui.LabelStyle
import com.folio.hermeslive.ui.Pill
import com.folio.hermeslive.ui.PrintInk
import com.folio.hermeslive.ui.PrintMute
import com.folio.hermeslive.ui.SmallStyle
import com.folio.hermeslive.ui.StatusStyle
import com.folio.hermeslive.ui.SurfaceHigh
import com.folio.hermeslive.ui.TitleStyle
import com.folio.hermeslive.ui.VoidBlack

/** What the meeting screen can do. */
data class MeetingActions(
    val onSpeakTitle: () -> Unit = {},
    val onStart: (String) -> Unit = {},
    val onCancel: () -> Unit = {},
    val onPause: () -> Unit = {},
    val onResume: () -> Unit = {},
    val onMark: () -> Unit = {},
    val onEnd: () -> Unit = {},
    val onAdd: (String) -> Unit = {},
    val onSpeakAddition: () -> Unit = {},
    val onSend: () -> Unit = {},
    val onSave: () -> Unit = {},
    val onDiscard: () -> Unit = {},
    val onResumeInterrupted: () -> Unit = {},
    val onEndInterrupted: () -> Unit = {},
    val onClose: () -> Unit = {},
    val onPrepareModels: () -> Unit = {},
)

/**
 * Meeting mode's screen. Pure: [screen] in, [actions] out; [nowMs] (wall) drives the elapsed
 * time, so a still render is the same every time. Calm by design: a dim orb, the elapsed time,
 * the last few lines, and the controls. Nothing animates on its own, so with the screen off (or
 * the app in the background) nothing is drawn.
 */
@Composable
fun MeetingScreenView(screen: MeetingScreen, nowMs: Long, actions: MeetingActions, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(VoidBlack).testTag("meeting-screen")) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 24.dp)) {
            when (screen) {
                is MeetingScreen.Taking -> Taking(screen.live, nowMs, actions)
                is MeetingScreen.Intro -> Intro(screen, actions)
                is MeetingScreen.Ending -> Ending(screen, actions)
                is MeetingScreen.Interrupted -> Message(
                    "Meeting notes were interrupted",
                    "\"${screen.meta.title.ifBlank { "Untitled meeting" }}\" stopped after ${screen.segments} lines " +
                        "(${MeetingBrief.duration(screen.meta.elapsedMs(nowMs))} in). Nothing said since was heard.",
                    tag = "meeting-interrupted",
                ) {
                    Pill("Resume", filled = true, tag = "meeting-resume-interrupted", onClick = actions.onResumeInterrupted)
                    Pill("End and send", filled = false, tag = "meeting-end-interrupted", onClick = actions.onEndInterrupted)
                    Pill("Discard", filled = false, tag = "meeting-discard", onClick = actions.onDiscard)
                }
                MeetingScreen.NeedsModels -> Message(
                    "Prepare speech models first",
                    "Meeting notes use the on-phone speech models. In Settings › Voice, choose Custom as the voice engine, then download them under Speech models.",
                    tag = "meeting-needs-models",
                ) {
                    Pill("Open Settings", filled = true, tag = "meeting-prepare", onClick = actions.onPrepareModels)
                    Pill("Close", filled = false, tag = "meeting-close", onClick = actions.onClose)
                }
                is MeetingScreen.Done -> Message(doneTitle(screen), doneText(screen), tag = "meeting-done") {
                    Pill("Done", filled = true, tag = "meeting-close", onClick = actions.onClose)
                }
                is MeetingScreen.Failed -> Message("Meeting notes stopped", screen.message, tag = "meeting-failed") {
                    Pill("Close", filled = true, tag = "meeting-close", onClick = actions.onClose)
                }
                MeetingScreen.None -> Unit
            }
        }
    }
}

@Composable
private fun ColumnScope.Taking(live: MeetingLive, nowMs: Long, actions: MeetingActions) {
    val paused = live.meta.phase == MeetingPhase.Paused
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (paused) "PAUSED" else "TAKING NOTES", style = LabelStyle, color = if (paused) Ember else PrintMute, modifier = Modifier.testTag("meeting-state"))
        Spacer(Modifier.weight(1f))
        Text("${live.segments} lines · ${live.marks} marked", style = SmallStyle, color = PrintMute)
    }
    Text(
        live.meta.title.ifBlank { "Meeting" },
        style = TitleStyle, color = PrintInk, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 6.dp).testTag("meeting-title"),
    )
    Spacer(Modifier.height(20.dp))
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        DimOrb(paused, Modifier.size(150.dp))
        Text(
            MeetingService.clock(live.meta.elapsedMs(nowMs)),
            style = StatusStyle, color = if (paused) PrintMute else PrintInk,
            modifier = Modifier.semantics { contentDescription = "Elapsed ${MeetingService.clock(live.meta.elapsedMs(nowMs))}" }.testTag("meeting-elapsed"),
        )
    }
    Spacer(Modifier.height(20.dp))
    Column(Modifier.weight(1f).fillMaxWidth().testTag("meeting-transcript"), verticalArrangement = Arrangement.Bottom) {
        if (live.lines.isEmpty() && live.partial == null) {
            Text(if (paused) "Paused. Nothing is heard until you resume." else "Listening silently. Say \"Bill, note that …\" to mark something.", style = BodyStyle, color = PrintMute)
        }
        val n = live.lines.size
        live.lines.forEachIndexed { i, line ->
            val fade = 0.45f + 0.55f * ((i + 1).toFloat() / n.coerceAtLeast(1))
            if (line.kind != null) {
                Text(
                    "★ " + line.text,
                    style = BodyStyle, color = Ember.copy(alpha = fade), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(vertical = 3.dp).clip(RoundedCornerShape(6.dp)).background(Ember.copy(alpha = 0.10f)).padding(horizontal = 8.dp, vertical = 4.dp)
                        .testTag(if (line.kind == MarkKind.Mark) "meeting-line-mark" else "meeting-line-note"),
                )
            } else {
                Text(line.text, style = CaptionStyle, color = PrintInk.copy(alpha = fade), maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 3.dp))
            }
        }
        live.partial?.takeIf { !paused }?.let { Text(it, style = CaptionStyle, color = PrintMute, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 3.dp)) }
    }
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Top) {
        Round(if (paused) "Resume" else "Pause", 64.dp, SurfaceHigh, PrintInk, if (paused) "meeting-resume" else "meeting-pause", if (paused) actions.onResume else actions.onPause) { c ->
            if (paused) PlayGlyph(c) else PauseGlyph(c)
        }
        Round("Mark", 76.dp, PrintInk, VoidBlack, "meeting-mark", actions.onMark, enabled = !paused) { c -> StarGlyph(c) }
        Round("End meeting", 64.dp, EndRed, PrintInk, "meeting-end", actions.onEnd) { c -> StopGlyph(c) }
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun ColumnScope.Intro(screen: MeetingScreen.Intro, actions: MeetingActions) {
    var title by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(screen.heard) { screen.heard?.let { title = it } }
    Spacer(Modifier.weight(0.5f))
    Text("What's the meeting?", style = StatusStyle, color = PrintInk, modifier = Modifier.testTag("meeting-intro"))
    Text(
        "Optional: a title or a line of context for the notes. The phone listens silently and sends the transcript to Hermes for notes when you end.",
        style = BodyStyle, color = PrintMute, modifier = Modifier.padding(top = 8.dp),
    )
    Spacer(Modifier.height(20.dp))
    OutlinedTextField(
        value = title, onValueChange = { title = it.take(120) }, singleLine = true,
        placeholder = { Text("e.g. Weekly supplier call with Priya", style = BodyStyle, color = PrintMute) },
        textStyle = BodyStyle.copy(color = PrintInk),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PrintInk, unfocusedBorderColor = Hairline, cursorColor = PrintInk),
        modifier = Modifier.fillMaxWidth().testTag("meeting-title-field"),
    )
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Pill(if (screen.listening) "Listening…" else "Say it", filled = false, tag = "meeting-speak-title", onClick = { if (!screen.loading && !screen.listening) actions.onSpeakTitle() })
    }
    Spacer(Modifier.weight(1f))
    if (screen.loading) Text("Loading the speech models…", style = SmallStyle, color = PrintMute, modifier = Modifier.padding(bottom = 12.dp).testTag("meeting-loading"))
    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Pill("Cancel", filled = false, tag = "meeting-cancel", onClick = actions.onCancel)
        Spacer(Modifier.weight(1f))
        Pill(if (title.isBlank()) "Skip and start" else "Start", filled = true, tag = "meeting-start", onClick = { if (!screen.loading) actions.onStart(title) })
    }
}

@Composable
private fun ColumnScope.Ending(screen: MeetingScreen.Ending, actions: MeetingActions) {
    var text by rememberSaveable { mutableStateOf("") }
    val m = screen.meta
    Spacer(Modifier.height(24.dp))
    Text("MEETING ENDED", style = LabelStyle, color = PrintMute)
    Text(m.title.ifBlank { "Meeting" }, style = TitleStyle, color = PrintInk, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
    Text(
        "${MeetingBrief.duration(m.elapsedMs(m.endedAt ?: m.startedAt))} · ${screen.segments} lines · ${screen.marks} marked",
        style = SmallStyle, color = PrintMute, modifier = Modifier.padding(top = 4.dp).testTag("meeting-summary"),
    )
    Spacer(Modifier.weight(0.4f))
    Text("Anything to add?", style = StatusStyle, color = PrintInk, modifier = Modifier.testTag("meeting-ending"))
    Text("Who was there, what you want from the notes… Optional.", style = BodyStyle, color = PrintMute, modifier = Modifier.padding(top = 6.dp))
    m.additions.forEach { Text("+ $it", style = BodyStyle, color = PrintInk, modifier = Modifier.padding(top = 6.dp)) }
    OutlinedTextField(
        value = text, onValueChange = { text = it.take(2_000) },
        placeholder = { Text("e.g. Priya, Sam and me. I need the action items.", style = BodyStyle, color = PrintMute) },
        textStyle = BodyStyle.copy(color = PrintInk),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PrintInk, unfocusedBorderColor = Hairline, cursorColor = PrintInk),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("meeting-add-field"),
    )
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Pill("Add", filled = false, tag = "meeting-add") {
            actions.onAdd(text)
            text = ""
        }
        if (screen.canSpeak) Pill(if (screen.listening) "Listening…" else "Say it", filled = false, tag = "meeting-speak-add", onClick = { if (!screen.listening) actions.onSpeakAddition() })
    }
    Spacer(Modifier.weight(1f))
    Text("The transcript goes to Hermes as one task, \"${MeetingBrief.label(m)}\". The brief tells it never to email anyone without your OK.", style = SmallStyle, color = PrintMute)
    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Pill("Discard", filled = false, tag = "meeting-discard", onClick = actions.onDiscard)
        Pill("Keep", filled = false, tag = "meeting-save", onClick = actions.onSave)
        Spacer(Modifier.weight(1f))
        Pill("Send for notes", filled = true, tag = "meeting-send") {
            if (text.isNotBlank()) actions.onAdd(text)
            actions.onSend()
        }
    }
}

@Composable
private fun ColumnScope.Message(title: String, text: String, tag: String, buttons: @Composable () -> Unit) {
    Spacer(Modifier.weight(1f))
    Text(title, style = StatusStyle, color = PrintInk, textAlign = TextAlign.Start, modifier = Modifier.testTag(tag))
    Text(text, style = BodyStyle, color = PrintMute, modifier = Modifier.padding(top = 10.dp))
    Spacer(Modifier.height(20.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { buttons() }
    Spacer(Modifier.weight(1.4f))
}

fun doneTitle(s: MeetingScreen.Done): String = when (s.outcome) {
    is MeetingOutbox.Outcome.Sent -> "Sent for notes"
    is MeetingOutbox.Outcome.Held -> "Saved; it goes to Hermes later"
    MeetingOutbox.Outcome.Missing, null -> "Kept on this phone"
}

fun doneText(s: MeetingScreen.Done): String = when (val o = s.outcome) {
    is MeetingOutbox.Outcome.Sent -> "\"${MeetingBrief.label(s.meta)}\" is with Hermes. When the notes are ready you'll get a notification with Hear it, like any task."
    is MeetingOutbox.Outcome.Held -> heldText(o.reason)
    MeetingOutbox.Outcome.Missing, null -> "The transcript stays on the phone. Send it any time from Settings › Meeting mode."
}

/** Why held notes have not gone, in plain words. */
fun heldText(reason: String): String = when (reason) {
    "offline" -> "The phone is offline. The notes go to Hermes as soon as it is back online."
    "bill_off" -> "Sending needs Custom or Grok Voice: choose one in Settings › Voice and save its key. The notes wait on the phone until then."
    "signed_out" -> "Sign in to Hermes and the notes go then. They wait on the phone until then."
    "no_reply" -> "Hermes's task list didn't answer in time. It tries again on its own; nothing is sent twice."
    else -> if (reason.startsWith("writer_")) "The transcript is long and couldn't be summarised just now. It tries again later." else "The notes wait on the phone and are tried again later."
}

@Composable
private fun DimOrb(paused: Boolean, modifier: Modifier) {
    val glow = if (paused) 0.10f else 0.22f
    Canvas(modifier.semantics { contentDescription = if (paused) "Paused" else "Listening" }) {
        val r = size.minDimension / 2
        drawCircle(Brush.radialGradient(listOf(PrintInk.copy(alpha = glow), Color.Transparent), center = center, radius = r), r, center)
        drawCircle(PrintInk.copy(alpha = glow * 0.9f), r * 0.62f, center, style = Stroke(width = 1.dp.toPx()))
    }
}

@Composable
private fun Round(label: String, size: Dp, background: Color, tint: Color, tag: String, onClick: () -> Unit, enabled: Boolean = true, glyph: @Composable (Color) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(96.dp)) {
        Box(
            Modifier.size(size).clip(CircleShape).background(if (enabled) background else background.copy(alpha = 0.4f)).border(1.dp, Hairline, CircleShape)
                .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
                .semantics { contentDescription = label }.testTag(tag),
            contentAlignment = Alignment.Center,
        ) { Box(Modifier.size(size * 0.40f)) { glyph(tint) } }
        Text(label, style = SmallStyle, color = PrintMute, maxLines = 1, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun PauseGlyph(c: Color) = Canvas(Modifier.fillMaxSize()) {
    val w = size.minDimension
    drawLine(c, Offset(w * 0.34f, w * 0.18f), Offset(w * 0.34f, w * 0.82f), w * 0.1f)
    drawLine(c, Offset(w * 0.66f, w * 0.18f), Offset(w * 0.66f, w * 0.82f), w * 0.1f)
}

@Composable
private fun PlayGlyph(c: Color) = Canvas(Modifier.fillMaxSize()) {
    val w = size.minDimension
    val p = androidx.compose.ui.graphics.Path().apply {
        moveTo(w * 0.3f, w * 0.16f)
        lineTo(w * 0.84f, w * 0.5f)
        lineTo(w * 0.3f, w * 0.84f)
        close()
    }
    drawPath(p, c)
}

@Composable
private fun StopGlyph(c: Color) = Canvas(Modifier.fillMaxSize()) {
    val w = size.minDimension
    drawRoundRect(c, Offset(w * 0.22f, w * 0.22f), androidx.compose.ui.geometry.Size(w * 0.56f, w * 0.56f), androidx.compose.ui.geometry.CornerRadius(w * 0.08f))
}

@Composable
fun StarGlyph(c: Color) = Canvas(Modifier.fillMaxSize()) {
    val w = size.minDimension
    val cx = w / 2
    val cy = w / 2
    val p = androidx.compose.ui.graphics.Path()
    for (i in 0 until 10) {
        val r = if (i % 2 == 0) w * 0.48f else w * 0.2f
        val a = Math.toRadians(-90.0 + i * 36.0)
        val x = cx + (r * Math.cos(a)).toFloat()
        val y = cy + (r * Math.sin(a)).toFloat()
        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    p.close()
    drawPath(p, c)
}

/** The home screen's Meeting button, beside Talk (shown only while meeting mode is on). */
@Composable
fun MeetingButton(onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(96.dp)) {
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(SurfaceHigh).border(1.dp, Hairline, CircleShape)
                .clickable(role = Role.Button, onClickLabel = "Take meeting notes", onClick = onClick)
                .semantics { contentDescription = "Take meeting notes" }.testTag("meeting"),
            contentAlignment = Alignment.Center,
        ) { NotesGlyph(PrintInk, Modifier.size(26.dp)) }
        Text("Meeting", style = SmallStyle, color = PrintMute, maxLines = 1, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun NotesGlyph(c: Color, modifier: Modifier) = Canvas(modifier) {
    val w = size.minDimension
    val s = Stroke(width = w * 0.075f)
    drawRoundRect(c, Offset(w * 0.18f, w * 0.1f), androidx.compose.ui.geometry.Size(w * 0.64f, w * 0.8f), androidx.compose.ui.geometry.CornerRadius(w * 0.08f), style = s)
    listOf(0.32f, 0.5f, 0.68f).forEach { y -> drawLine(c, Offset(w * 0.32f, w * y), Offset(w * 0.68f, w * y), w * 0.06f) }
}

/** A small heading used by the meeting settings. */
@Composable
internal fun MeetingHeading(text: String) = Text(text, style = HeadingStyle, color = PrintInk, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
