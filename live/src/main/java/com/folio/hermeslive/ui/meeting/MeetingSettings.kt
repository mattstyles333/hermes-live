package com.folio.hermeslive.ui.meeting

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.folio.hermeslive.meeting.MeetingBrief
import com.folio.hermeslive.meeting.MeetingEntry
import com.folio.hermeslive.meeting.MeetingHost
import com.folio.hermeslive.meeting.MeetingMeta
import com.folio.hermeslive.meeting.MeetingPhase
import com.folio.hermeslive.meeting.MeetingSettings
import com.folio.hermeslive.ui.BodyStyle
import com.folio.hermeslive.ui.Ember
import com.folio.hermeslive.ui.Hairline
import com.folio.hermeslive.ui.MonoStyle
import com.folio.hermeslive.ui.Pill
import com.folio.hermeslive.ui.PrintInk
import com.folio.hermeslive.ui.PrintMute
import com.folio.hermeslive.ui.SmallStyle
import com.folio.hermeslive.ui.Surface
import com.folio.hermeslive.ui.TitleStyle
import com.folio.hermeslive.ui.VoidBlack
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What Settings › Meeting mode can do. */
data class MeetingSettingsActions(
    val onChange: ((MeetingSettings) -> MeetingSettings) -> Unit = {},
    val transcript: (String) -> String = { "" },
    val onResend: (String) -> Unit = {},
    val onDelete: (String) -> Unit = {},
)

/** Settings › Meeting mode on the app's prefs and (while on) its host. Nothing in previews. */
@Composable
fun MeetingSettingsWired() {
    if (LocalInspectionMode.current) return
    val context = LocalContext.current
    val prefs = remember { runCatching { MeetingHost.prefs(context) }.getOrNull() } ?: return
    val settings by prefs.settings.collectAsState()
    val host = remember(settings.enabled) { MeetingHost.ifEnabled(context) }
    var version by remember { mutableIntStateOf(0) }
    val history = remember(host, version) { host?.flow?.history().orEmpty() }
    LaunchedEffect(settings.enabled) { MeetingEntry.syncShortcut(context, settings.enabled) }
    MeetingSettingsSection(
        settings = settings,
        history = history,
        actions = MeetingSettingsActions(
            onChange = { change ->
                prefs.update(change)
                host?.prune()
                version++
            },
            transcript = { id -> host?.flow?.transcriptText(id).orEmpty() },
            onResend = { id ->
                host?.flow?.resend(id)
                version++
            },
            onDelete = { id ->
                host?.flow?.delete(id)
                version++
            },
        ),
    )
}

@Composable
fun MeetingSettingsSection(settings: MeetingSettings, history: List<MeetingMeta>, actions: MeetingSettingsActions, zone: ZoneId = ZoneId.systemDefault()) {
    var open by remember { mutableStateOf<MeetingMeta?>(null) }
    MeetingHeading("Meeting mode")
    HorizontalDivider(color = Hairline)
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Toggle(
            "Meeting notes", "A Meeting button beside Talk and a \"Take meeting notes\" shortcut. The phone listens silently, then Hermes writes the notes.",
            settings.enabled, "meeting-enabled",
        ) { v -> actions.onChange { it.copy(enabled = v) } }
        if (settings.enabled) {
            Toggle(
                "Wake word \"Bill\"", "\"Bill, note that …\" marks it in the notes; \"Bill, start a task to …\" starts an agent task. A haptic tick confirms; nothing is spoken.",
                settings.wakeWord, "meeting-wake",
            ) { v -> actions.onChange { it.copy(wakeWord = v) } }
            Column(
                Modifier.fillMaxWidth().clickable(role = Role.Button) {
                    actions.onChange { it.copy(retentionDays = nextRetention(it.retentionDays)) }
                }.padding(vertical = 8.dp).testTag("meeting-retention"),
            ) {
                Text("Keep transcripts on the phone", style = BodyStyle, color = PrintInk)
                Text("${settings.retentionDays} days after the meeting, then deleted (unsent ones are kept)", style = SmallStyle, color = Ember)
            }
            Text(
                "Language: transcripts use your speech models (Settings › Voice), which are English. With Speech recognition set to the cloud, " +
                    "each clip goes to OpenRouter as it does in a call. No audio is kept; only the text, on this phone.",
                style = SmallStyle, color = PrintMute, modifier = Modifier.padding(vertical = 6.dp).testTag("meeting-language"),
            )
            Text("Past meetings", style = BodyStyle, color = PrintInk, modifier = Modifier.padding(top = 8.dp))
            if (history.isEmpty()) Text("None yet.", style = SmallStyle, color = PrintMute, modifier = Modifier.testTag("meeting-history-empty"))
            history.forEach { m ->
                Column(Modifier.fillMaxWidth().clickable { open = m }.padding(vertical = 8.dp).testTag("meeting-history-${m.id}")) {
                    Text(m.title.ifBlank { "Untitled meeting" }, style = BodyStyle, color = PrintInk)
                    Text("${date(m.startedAt, zone)} · ${MeetingBrief.duration(m.elapsedMs(m.endedAt ?: m.startedAt))} · ${phaseText(m)}", style = SmallStyle, color = PrintMute)
                }
            }
        }
    }
    open?.let { m ->
        TranscriptDialog(m, actions.transcript(m.id), onResend = {
            actions.onResend(m.id)
            open = null
        }, onDelete = {
            actions.onDelete(m.id)
            open = null
        }, onClose = { open = null })
    }
}

@Composable
private fun TranscriptDialog(m: MeetingMeta, text: String, onResend: () -> Unit, onDelete: () -> Unit, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(VoidBlack).padding(24.dp).testTag("meeting-transcript-dialog")) {
            Text(m.title.ifBlank { "Untitled meeting" }, style = TitleStyle, color = PrintInk)
            Text(phaseText(m), style = SmallStyle, color = PrintMute, modifier = Modifier.padding(top = 4.dp))
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp).clip(RoundedCornerShape(8.dp)).background(Surface)
                    .verticalScroll(rememberScrollState()).padding(12.dp).heightIn(min = 80.dp),
            ) { Text(text, style = MonoStyle, color = PrintInk) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill("Delete", filled = false, tag = "meeting-history-delete", onClick = onDelete)
                Spacer(Modifier.weight(1f))
                Pill("Close", filled = false, tag = "meeting-history-close", onClick = onClose)
                Spacer(Modifier.width(4.dp))
                Pill(if (m.phase == MeetingPhase.Sent) "Send again" else "Send to Hermes", filled = true, tag = "meeting-history-send", onClick = onResend)
            }
        }
    }
}

@Composable
private fun Toggle(title: String, detail: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Switch) { onChange(!checked) }.padding(vertical = 8.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = BodyStyle, color = PrintInk)
            Text(detail, style = SmallStyle, color = PrintMute)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = VoidBlack, checkedTrackColor = PrintInk,
                uncheckedThumbColor = PrintMute, uncheckedTrackColor = Surface, uncheckedBorderColor = Hairline,
            ),
        )
    }
}

fun nextRetention(current: Int): Int {
    val c = MeetingSettings.RETENTION_CHOICES
    val i = c.indexOf(current)
    return c[(i + 1) % c.size]
}

fun phaseText(m: MeetingMeta): String = when (m.phase) {
    MeetingPhase.Sent -> "Sent to Hermes"
    MeetingPhase.Queued -> "Waiting to send" + (m.heldReason?.let { " (${it.replace('_', ' ')})" } ?: "")
    MeetingPhase.Saved -> "Kept on the phone"
    MeetingPhase.Ended -> "Not sent"
    MeetingPhase.Recording, MeetingPhase.Paused -> "In progress"
}

private fun date(ms: Long, zone: ZoneId) = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.UK).format(Instant.ofEpochMilli(ms).atZone(zone))
