package com.folio.hermeslive.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.folio.hermes.ApprovalChoice
import com.folio.hermes.live.Speaker
import com.folio.hermeslive.call.ApprovalCard
import com.folio.hermeslive.call.CallPhase
import com.folio.hermeslive.call.CallSummary
import com.folio.hermeslive.call.CallUiState
import com.folio.hermeslive.call.Caption
import com.folio.hermeslive.call.ClarifyCard
import com.folio.hermeslive.call.RouteKind
import com.folio.hermeslive.call.RouteOption
import java.util.Locale

/** Everything the call screen can ask for. Defaults do nothing (previews, tests). */
data class CallActions(
    val onTalk: () -> Unit = {},
    val onEnd: () -> Unit = {},
    val onMute: () -> Unit = {},
    val onRoute: (RouteOption) -> Unit = {},
    val onApprove: (String, ApprovalChoice) -> Unit = { _, _ -> },
    val onClarify: (String, Map<String, String?>) -> Unit = { _, _ -> },
    val onSettings: () -> Unit = {},
    val onDismissSummary: () -> Unit = {},
    val onNewConversation: () -> Unit = {},
)

/**
 * The call screen. Pure: [state] in, [actions] out. [elapsed] is the running time ("4:12");
 * [voiceLine] the server's voice status for the idle screen.
 */
@Composable
fun CallScreen(
    state: CallUiState,
    actions: CallActions,
    modifier: Modifier = Modifier,
    elapsed: String? = null,
    voiceLine: String? = null,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box(
        modifier
            .fillMaxSize()
            .background(VoidBlack)
            .background(Brush.verticalGradient(listOf(Color(0xFF15171B), VoidBlack), endY = 900f)),
    ) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 24.dp)) {
            TopBar(state, elapsed, actions.onSettings)
            Notices(state)
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                FullTranscript(state.captions, Modifier.weight(1f).fillMaxWidth()) { expanded = false }
            } else {
                Spacer(Modifier.weight(0.6f))
                Orb(orbMood(state), state.level, Modifier.size(248.dp).align(Alignment.CenterHorizontally))
                StatusText(state, voiceLine)
                Spacer(Modifier.weight(0.4f))
                RecentCaptions(state.captions) { expanded = true }
            }
            Cards(state, actions)
            Spacer(Modifier.height(16.dp))
            if (state.inCall) CallControls(state, actions) else IdleControls(state, actions)
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun TopBar(state: CallUiState, elapsed: String?, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Hermes Live", style = TitleStyle.copy(shadow = PrintShadow), color = PrintInk)
        Spacer(Modifier.width(12.dp))
        if (state.inCall && elapsed != null) {
            Text(elapsed, style = SmallStyle, color = PrintMute, modifier = Modifier.testTag("elapsed"))
        }
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.size(44.dp).clip(CircleShape)
                .clickable(role = Role.Button, onClickLabel = "Settings", onClick = onSettings)
                .semantics { contentDescription = "Settings" }
                .testTag("settings"),
            contentAlignment = Alignment.Center,
        ) { Glyph(GlyphKind.Gear, PrintMute, size = 22.dp) }
    }
}

@Composable
private fun Notices(state: CallUiState) {
    val line = state.warning ?: state.notice
    AnimatedVisibility(line != null, enter = fadeIn(), exit = fadeOut()) {
        Text(
            line.orEmpty(),
            style = SmallStyle,
            color = if (state.warning != null) Ember else PrintMute,
            modifier = Modifier.padding(top = 6.dp).testTag("notice"),
        )
    }
}

/** The one-line state under the orb. */
fun statusText(state: CallUiState): String = when {
    state.phase == CallPhase.Connecting -> "Connecting…"
    state.phase == CallPhase.Reconnecting -> "Reconnecting…"
    state.phase == CallPhase.Ending -> "Ending…"
    state.phase == CallPhase.Failed -> "Could not connect"
    !state.inCall -> if (state.summary != null) "Call ended" else "Tap to talk"
    state.onOtherCall -> "Paused for your other call"
    state.approvals.isNotEmpty() -> "Waiting for your approval"
    state.activity == com.folio.hermeslive.call.CallActivity.HermesSpeaking -> "Hermes is speaking"
    state.muted -> "Muted"
    state.activity == com.folio.hermeslive.call.CallActivity.UserSpeaking -> "Listening…"
    state.activity == com.folio.hermeslive.call.CallActivity.Thinking -> "Hermes is working"
    else -> "Listening"
}

@Composable
private fun ColumnScope.StatusText(state: CallUiState, voiceLine: String?) {
    Text(
        statusText(state),
        style = StatusStyle.copy(shadow = PrintShadow),
        color = PrintInk,
        textAlign = TextAlign.Center,
        modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 20.dp).testTag("status"),
    )
    val sub = when {
        state.inCall && state.tool != null && state.activity == com.folio.hermeslive.call.CallActivity.Thinking ->
            state.tool + if (state.musicInGap) " · music in the gap" else ""
        state.inCall && state.musicInGap -> "Music plays while Hermes works"
        !state.inCall && state.error != null -> state.error
        !state.inCall && state.summary == null -> voiceLine
        else -> null
    }
    Text(
        sub.orEmpty(),
        style = SmallStyle,
        color = if (!state.inCall && state.error != null) EndRed else PrintMute,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp).heightIn(min = 18.dp).testTag("substatus"),
    )
    state.summary?.takeIf { !state.inCall }?.let { SummaryLine(it, Modifier.align(Alignment.CenterHorizontally)) }
}

@Composable
private fun SummaryLine(summary: CallSummary, modifier: Modifier) {
    val voice = summary.voiceSeconds?.let { " · voice ${clock((it * 1000).toLong())}" }.orEmpty()
    Column(modifier.padding(top = 10.dp).testTag("summary"), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "${clock(summary.durationMs)}$voice · about ${money(summary.estimatedCostUsd)}",
            style = BodyStyle, color = PrintInk,
        )
        if (summary.reason != "Call ended.") Text(summary.reason, style = SmallStyle, color = PrintMute)
    }
}

/** `m:ss` or `h:mm:ss`. */
fun clock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.UK, "%d:%02d:%02d", h, m, s) else String.format(Locale.UK, "%d:%02d", m, s)
}

fun money(usd: Double): String = if (usd < 0.01) "under 1¢" else String.format(Locale.UK, "$%.2f", usd)

@Composable
private fun RecentCaptions(captions: List<Caption>, onExpand: () -> Unit) {
    val recent = captions.takeLast(3)
    Column(
        Modifier.fillMaxWidth().heightIn(min = 84.dp)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = "Show the whole conversation", onClick = onExpand)
            .animateContentSize()
            .testTag("captions"),
        verticalArrangement = Arrangement.Bottom,
    ) {
        recent.forEachIndexed { i, caption ->
            val fade = when (recent.size - i) { 1 -> 1f; 2 -> 0.6f; else -> 0.35f }
            CaptionLine(caption, fade, maxLines = if (i == recent.lastIndex) 4 else 2)
        }
    }
}

@Composable
private fun CaptionLine(caption: Caption, alpha: Float, maxLines: Int = Int.MAX_VALUE) {
    val user = caption.speaker == Speaker.User
    Text(
        caption.text.trim(),
        style = if (user) BodyStyle else CaptionStyle,
        color = (if (user) PrintMute else PrintInk).copy(alpha = alpha),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        textAlign = if (user) TextAlign.End else TextAlign.Start,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
    )
}

@Composable
private fun FullTranscript(captions: List<Caption>, modifier: Modifier, onCollapse: () -> Unit) {
    val list = rememberLazyListState()
    LaunchedEffect(captions.size) { if (captions.isNotEmpty()) list.scrollToItem(captions.lastIndex) }
    Column(modifier.testTag("transcript")) {
        Text(
            "Conversation · tap to close",
            style = LabelStyle, color = PrintMute,
            modifier = Modifier.clickable(onClick = onCollapse).padding(vertical = 8.dp),
        )
        LazyColumn(Modifier.weight(1f).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onCollapse), state = list) {
            items(captions) { CaptionLine(it, 1f) }
        }
    }
}

@Composable
private fun Cards(state: CallUiState, actions: CallActions) {
    state.approvals.firstOrNull()?.let { ApprovalCardView(it, actions.onApprove, more = state.approvals.size - 1) }
    state.clarify?.let { ClarifyCardView(it, actions.onClarify) }
}

@Composable
private fun CardFrame(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 12.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(SurfaceHigh)
            .border(BorderStroke(1.dp, Hairline), RoundedCornerShape(18.dp))
            .padding(18.dp),
        content = content,
    )
}

@Composable
internal fun ApprovalCardView(card: ApprovalCard, onApprove: (String, ApprovalChoice) -> Unit, more: Int) {
    val request = card.request
    CardFrame {
        Text("HERMES ASKS FIRST", style = LabelStyle, color = Ember)
        if (request.description.isNotBlank()) {
            Text(request.description, style = BodyStyle, color = PrintInk, modifier = Modifier.padding(top = 8.dp))
        }
        if (request.command.isNotBlank()) {
            Text(
                request.command, style = MonoStyle, color = PrintInk, maxLines = 6, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(VoidBlack).padding(10.dp),
            )
        }
        FlowRow(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val order = listOf(ApprovalChoice.Once, ApprovalChoice.Session, ApprovalChoice.Always)
            order.filter { it in card.choices }.forEach { choice ->
                Pill(choiceLabel(choice), filled = choice == ApprovalChoice.Once, tag = "approve-${choice.wire}") { onApprove(card.id, choice) }
            }
            Pill("Deny", filled = false, tag = "approve-deny") { onApprove(card.id, ApprovalChoice.Deny) }
        }
        Text(
            if (more > 0) "$more more waiting. Hermes never approves by itself." else "Hermes never approves by itself.",
            style = SmallStyle, color = PrintMute, modifier = Modifier.padding(top = 10.dp),
        )
    }
}

private fun choiceLabel(choice: ApprovalChoice) = when (choice) {
    ApprovalChoice.Once -> "Approve once"
    ApprovalChoice.Session -> "This session"
    ApprovalChoice.Always -> "Always"
    ApprovalChoice.Deny -> "Deny"
}

@Composable
internal fun ClarifyCardView(card: ClarifyCard, onClarify: (String, Map<String, String?>) -> Unit) {
    val question = card.request.questions.firstOrNull() ?: return
    var typed by remember(card.id) { mutableStateOf("") }
    CardFrame {
        Text("HERMES ASKS", style = LabelStyle, color = Ember)
        Text(question.question, style = CaptionStyle, color = PrintInk, modifier = Modifier.padding(top = 8.dp))
        if (question.choices.isNotEmpty()) {
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                question.choices.forEach { choice ->
                    Pill(choice, filled = false, tag = "clarify-choice") { onClarify(card.id, mapOf(question.qid to choice)) }
                }
            }
        } else {
            OutlinedTextField(
                value = typed, onValueChange = { typed = it }, singleLine = true,
                colors = fieldColors(), textStyle = BodyStyle,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).testTag("clarify-text"),
            )
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (question.choices.isEmpty()) {
                Pill("Send", filled = true, tag = "clarify-send") { if (typed.isNotBlank()) onClarify(card.id, mapOf(question.qid to typed.trim())) }
            }
            Pill("Skip", filled = false, tag = "clarify-skip") { onClarify(card.id, mapOf(question.qid to null)) }
        }
        Text("You can also answer out loud.", style = SmallStyle, color = PrintMute, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = PrintInk,
    unfocusedBorderColor = Hairline,
    focusedTextColor = PrintInk,
    unfocusedTextColor = PrintInk,
    cursorColor = Ember,
    focusedLabelColor = PrintInk,
    unfocusedLabelColor = PrintMute,
)

@Composable
fun Pill(text: String, filled: Boolean, tag: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier.clip(shape)
            .then(if (filled) Modifier.background(PrintInk) else Modifier.border(1.dp, Hairline, shape))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = BodyStyle, color = if (filled) VoidBlack else PrintInk, maxLines = 1)
    }
}

@Composable
private fun CallControls(state: CallUiState, actions: CallActions) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Top) {
        RoundControl(
            glyph = if (state.muted) GlyphKind.MicOff else GlyphKind.Mic,
            label = if (state.muted) "Muted" else "Mute",
            size = 64.dp,
            background = if (state.muted) PrintInk else SurfaceHigh,
            tint = if (state.muted) VoidBlack else PrintInk,
            tag = "mute",
            description = if (state.muted) "Unmute" else "Mute",
            onClick = actions.onMute,
        )
        RoundControl(GlyphKind.EndCall, "End", 76.dp, EndRed, PrintInk, "end", "End call", actions.onEnd)
        RouteControl(state, actions.onRoute)
    }
}

@Composable
private fun RouteControl(state: CallUiState, onRoute: (RouteOption) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        RoundControl(
            glyph = glyphFor(state.route?.kind),
            label = state.route?.label ?: "Audio",
            size = 64.dp,
            background = SurfaceHigh,
            tint = PrintInk,
            tag = "route",
            description = "Audio output: ${state.route?.label ?: "default"}",
        ) { open = state.routes.size > 1 }
        DropdownMenu(open, onDismissRequest = { open = false }, containerColor = SurfaceHigh) {
            state.routes.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            option.label + if (option.id == state.route?.id) "  ·  on" else "",
                            style = BodyStyle, color = PrintInk,
                        )
                    },
                    leadingIcon = { Glyph(glyphFor(option.kind), PrintInk, size = 20.dp) },
                    onClick = {
                        open = false
                        onRoute(option)
                    },
                    modifier = Modifier.testTag("route-${option.kind.name}"),
                )
            }
        }
    }
}

private fun glyphFor(kind: RouteKind?) = when (kind) {
    RouteKind.Earpiece -> GlyphKind.Earpiece
    RouteKind.Speaker, null -> GlyphKind.Speaker
    RouteKind.Bluetooth -> GlyphKind.Bluetooth
    RouteKind.Wired, RouteKind.Usb, RouteKind.HearingAid, RouteKind.Other -> GlyphKind.Headset
}

@Composable
private fun RoundControl(
    glyph: GlyphKind,
    label: String,
    size: Dp,
    background: Color,
    tint: Color,
    tag: String,
    description: String,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(96.dp)) {
        Box(
            Modifier.size(size).clip(CircleShape).background(background)
                .border(1.dp, Hairline, CircleShape)
                .clickable(role = Role.Button, onClickLabel = description, onClick = onClick)
                .semantics { contentDescription = description }
                .testTag(tag),
            contentAlignment = Alignment.Center,
        ) { Glyph(glyph, tint, size = size * 0.42f) }
        Text(label, style = SmallStyle, color = PrintMute, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun IdleControls(state: CallUiState, actions: CallActions) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        RoundControl(GlyphKind.Talk, if (state.summary != null || state.error != null) "Talk again" else "Talk", 76.dp, PrintInk, VoidBlack, "talk", "Talk to Hermes", actions.onTalk)
        Text(
            "New conversation",
            style = SmallStyle, color = PrintMute,
            modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(50)).clickable(onClick = actions.onNewConversation)
                .padding(horizontal = 12.dp, vertical = 8.dp).testTag("new-conversation"),
        )
    }
}
