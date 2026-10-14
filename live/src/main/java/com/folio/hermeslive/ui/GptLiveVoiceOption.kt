package com.folio.hermeslive.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.folio.hermeslive.data.LiveSettings

/**
 * Settings › Voice with GPT-Live chosen: the voice slot (ondemand slice). A placeholder: the
 * GPT-Live parallel-agents slice decides whether GPT-Live's voice can be chosen per session and
 * fills this in (a picker over [settings], saved through [onChange]). Until then it only says
 * where the voice comes from.
 */
@Composable
fun GptLiveVoiceOption(settings: LiveSettings, onChange: ((LiveSettings) -> LiveSettings) -> Unit) {
    Text("GPT-Live speaks in the voice your Hermes server sets.", style = SmallStyle, color = PrintMute, modifier = Modifier.testTag("gpt-live-voice"))
}
