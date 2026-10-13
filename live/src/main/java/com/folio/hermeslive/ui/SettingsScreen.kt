package com.folio.hermeslive.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.folio.hermes.live.VoiceLiveStatus
import com.folio.hermeslive.call.FallbackRoute
import com.folio.hermeslive.data.AccountState
import com.folio.hermeslive.data.LiveSettings

/** What the settings screen can do. */
data class SettingsActions(
    val onBack: () -> Unit = {},
    val onChange: ((LiveSettings) -> LiveSettings) -> Unit = {},
    val onSignOut: () -> Unit = {},
    val onResetConversation: () -> Unit = {},
    val onRefreshVoice: () -> Unit = {},
)

@Composable
fun SettingsScreen(
    settings: LiveSettings,
    account: AccountState,
    voice: VoiceLiveStatus?,
    version: String,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(VoidBlack).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button, onClick = actions.onBack)
                    .semantics { contentDescription = "Back" }.testTag("back"),
                contentAlignment = Alignment.Center,
            ) { Glyph(GlyphKind.Back, PrintInk, size = 22.dp) }
            Text("Settings", style = TitleStyle, color = PrintInk, modifier = Modifier.padding(start = 4.dp))
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            Section("Account") {
                when (account) {
                    is AccountState.SignedIn -> {
                        Line("Server", account.server)
                        Line("Signed in as", account.username)
                    }
                    is AccountState.NeedsSignIn -> {
                        Line("Server", account.server)
                        Line("Session", account.reason)
                    }
                    AccountState.SignedOut -> Line("Not signed in", "Hermes Live connects to nothing until you sign in.")
                }
                if (account !is AccountState.SignedOut) {
                    Line("Voice", voiceText(voice), Modifier.clickable(onClick = actions.onRefreshVoice).testTag("voice-status"))
                    Spacer(Modifier.height(8.dp))
                    Pill("Sign out", filled = false, tag = "signout", onClick = actions.onSignOut)
                    Text("Removes the session, the saved password and the conversation from this phone.", style = SmallStyle, color = PrintMute, modifier = Modifier.padding(top = 6.dp))
                }
            }
            Section("Calls") {
                Toggle("Start talking when opened", "Opening Hermes Live starts a call.", settings.startOnOpen, "start-on-open") { v -> actions.onChange { it.copy(startOnOpen = v) } }
                Toggle("Keep the screen on", "During a call.", settings.keepScreenOn, "keep-screen-on") { v -> actions.onChange { it.copy(keepScreenOn = v) } }
                Choice(
                    "Hang up when quiet", if (settings.idleTimeoutMinutes == 0) "Never" else "After ${settings.idleTimeoutMinutes} min with nobody talking",
                    "idle",
                ) { actions.onChange { it.copy(idleTimeoutMinutes = next(LiveSettings.IDLE_CHOICES, it.idleTimeoutMinutes)) } }
                Choice(
                    "Call limit", if (settings.callCapMinutes == 0) "None" else "${settings.callCapMinutes} min (about ${money(settings.callCapMinutes * 0.05)})",
                    "cap",
                ) { actions.onChange { it.copy(callCapMinutes = next(LiveSettings.CAP_CHOICES, it.callCapMinutes)) } }
            }
            Section("Sound") {
                Toggle("Music in the gaps", "Spotify and other players pause while you or Hermes talk, and play while Hermes works.", settings.musicInGaps, "music") { v -> actions.onChange { it.copy(musicInGaps = v) } }
                Toggle("Duck on the speaker", "Without a headset, keep music quiet in the gaps instead of full volume, so less of it reaches the mic.", settings.duckOnSpeaker, "duck", enabled = settings.musicInGaps) { v -> actions.onChange { it.copy(duckOnSpeaker = v) } }
                Choice("Without a headset", if (settings.fallbackRoute == FallbackRoute.Speaker) "Speaker" else "Phone (earpiece)", "fallback") {
                    actions.onChange { it.copy(fallbackRoute = if (it.fallbackRoute == FallbackRoute.Speaker) FallbackRoute.Earpiece else FallbackRoute.Speaker) }
                }
                Toggle("Call sounds", "Soft tones when the call connects, mutes and ends.", settings.earcons, "earcons") { v -> actions.onChange { it.copy(earcons = v) } }
                Toggle("Haptics", "A light tap with call sounds and approvals.", settings.haptics, "haptics") { v -> actions.onChange { it.copy(haptics = v) } }
            }
            Section("Approvals") {
                Toggle(
                    "Spoken approvals",
                    "Off: Hermes waits for a tap on Approve or Deny. On: saying just \"approve\" or \"deny\" also answers.",
                    settings.spokenApprovals, "spoken-approvals",
                ) { v -> actions.onChange { it.copy(spokenApprovals = v) } }
            }
            Section("Conversation") {
                Text("Calls within 30 minutes carry on the same Hermes conversation.", style = SmallStyle, color = PrintMute)
                Spacer(Modifier.height(8.dp))
                Pill("Start a new conversation", filled = false, tag = "reset-conversation", onClick = actions.onResetConversation)
            }
            Section("About") {
                Line("Hermes Live", version)
                Text(
                    "Talks only to your own Hermes server. The voice is OpenAI's GPT-Live, reached through Hermes; it costs about \$0.05 a minute. " +
                        "Headset button: press to mute, hold to end. Say \"goodbye\" or \"that's all\" to hang up.",
                    style = SmallStyle, color = PrintMute,
                )
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

fun voiceText(voice: VoiceLiveStatus?): String = when {
    voice == null -> "Unknown (tap to check)"
    voice.available -> listOf(voice.model, voice.voice.takeIf { it.isNotBlank() }?.let { "voice $it" }).filterNotNull().filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { "Available" }
    else -> "Unavailable" + (voice.reason?.let { ": $it" } ?: "")
}

private fun next(choices: List<Int>, current: Int): Int {
    val i = choices.indexOf(current)
    return choices[(i + 1) % choices.size]
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(title, style = HeadingStyle, color = PrintInk, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
    HorizontalDivider(color = Hairline)
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
}

@Composable
private fun Line(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, style = SmallStyle, color = PrintMute)
        Text(value, style = BodyStyle, color = PrintInk)
    }
}

@Composable
private fun Toggle(title: String, detail: String, checked: Boolean, tag: String, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) }.padding(vertical = 8.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = BodyStyle, color = if (enabled) PrintInk else PrintMute)
            Text(detail, style = SmallStyle, color = PrintMute)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = VoidBlack, checkedTrackColor = PrintInk,
                uncheckedThumbColor = PrintMute, uncheckedTrackColor = Surface, uncheckedBorderColor = Hairline,
            ),
        )
    }
}

@Composable
private fun Choice(title: String, value: String, tag: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 8.dp).testTag(tag)) {
        Text(title, style = BodyStyle, color = PrintInk)
        Text(value, style = SmallStyle, color = Ember)
    }
}
