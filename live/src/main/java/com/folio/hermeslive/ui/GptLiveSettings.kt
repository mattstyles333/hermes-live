package com.folio.hermeslive.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.folio.hermes.live.VoiceLiveStatus
import com.folio.hermeslive.LiveApp
import com.folio.hermeslive.data.GptLivePrefs
import com.folio.hermeslive.data.VoiceShell
import kotlinx.coroutines.launch

/**
 * GPT-Live's options, wired to the app (the switch in [GptLivePrefs], the voice from the account's
 * `voice-live/status`). For the Settings screen's `GptLiveVoiceOption(settings, onChange)`
 * placeholder to call. Draws nothing outside the app (previews, tests without [LiveApp]).
 */
@Composable
fun GptLiveOptions() {
    val context = LocalContext.current
    val app = context.applicationContext as? LiveApp ?: return
    val prefs = remember { GptLivePrefs.of(context) }
    val agents by prefs.agents.collectAsStateWithLifecycle()
    val voice by app.graph.account.voiceStatus.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    GptLiveSettingsSection(
        agentsOn = agents,
        onAgents = { on ->
            prefs.setAgents(on)
            // The task runtime is built or released with the gate.
            VoiceShell.of(context).onSettingsChanged()
        },
        voice = voice,
        onRefreshVoice = { scope.launch { app.graph.account.refreshVoiceStatus() } },
    )
}

/**
 * GPT-Live's own settings (gptlive-parallel slice), pure, for the Settings screen to place (the
 * hub owns `SettingsScreen.kt`): the parallel-agents switch (off by default) and the voice, read-only.
 *
 * The voice is read-only because the Hermes server does not let a client choose it: `POST
 * /api/audio/voice-live/session` takes only `{sdp, history}` (extra fields are ignored), and the
 * server builds the session's voice from its config (`voice.gpt_live.voice`, default `marin`);
 * GPT-Live has no client event that changes it. Checked read-only against the deployed source,
 * 2026-10-14.
 */
@Composable
fun GptLiveSettingsSection(
    agentsOn: Boolean,
    onAgents: (Boolean) -> Unit,
    voice: VoiceLiveStatus?,
    onRefreshVoice: () -> Unit = {},
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Switch) { onAgents(!agentsOn) }
                .padding(vertical = 8.dp).testTag("gptlive-agents"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(AGENTS_TITLE, style = BodyStyle, color = PrintInk)
                Text(AGENTS_DETAIL, style = SmallStyle, color = PrintMute)
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = agentsOn,
                onCheckedChange = onAgents,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = VoidBlack, checkedTrackColor = PrintInk,
                    uncheckedThumbColor = PrintMute, uncheckedTrackColor = Surface, uncheckedBorderColor = Hairline,
                ),
            )
        }
        Column(Modifier.fillMaxWidth().clickable(onClick = onRefreshVoice).padding(vertical = 6.dp).testTag("gptlive-voice")) {
            Text("GPT-Live voice", style = SmallStyle, color = PrintMute)
            Text(gptLiveVoiceLine(voice), style = BodyStyle, color = PrintInk)
            Text(VOICE_NOTE, style = SmallStyle, color = PrintMute)
        }
    }
}

/** The GPT-Live voice as the server reports it (`GET /api/audio/voice-live/status`). */
fun gptLiveVoiceLine(voice: VoiceLiveStatus?): String = when {
    voice == null -> "Unknown (tap to check)"
    voice.voice.isBlank() -> "The server's default"
    else -> voice.voice.replaceFirstChar { it.uppercaseChar() }
}

const val AGENTS_TITLE = "Parallel agents"
const val AGENTS_DETAIL =
    "Each request runs as its own background task with a card, several at once, and carries on after you hang up. " +
        "Say \"how's it going\" or \"cancel the …\" to manage them. Off: one request at a time."
const val VOICE_NOTE = "Set on your Hermes server (voice.gpt_live.voice). It can't be picked per call."
