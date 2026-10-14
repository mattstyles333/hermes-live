package com.folio.hermeslive.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.folio.hermeslive.data.LiveSettings
import com.folio.hermeslive.data.TtsModelInfo
import com.folio.hermeslive.data.VoiceCatalog
import com.folio.hermeslive.data.VoiceEngine
import com.folio.hermeslive.data.VoiceOption
import com.folio.hermeslive.data.VoicePickerUi
import com.folio.hermeslive.data.VoiceSource

/** What the voice pickers can do (Settings › Voice; ondemand slice). */
data class VoicePickerActions(
    val onLoad: (force: Boolean) -> Unit = {},
    val onChooseTtsModel: (String) -> Unit = {},
    val onChooseVoice: (String) -> Unit = {},
    val onChooseGrokVoice: (String) -> Unit = {},
    val onPreviewCustom: (String) -> Unit = {},
    val onPreviewGrok: (String) -> Unit = {},
)

/** The "Save cost" switch for a realtime engine (GPT-Live, Grok Voice). */
@Composable
fun SaveCostToggle(settings: LiveSettings, onChange: ((LiveSettings) -> LiveSettings) -> Unit) {
    val grok = settings.voiceEngine == VoiceEngine.Grok
    val on = if (grok) settings.grokSaveCost else settings.gptLiveSaveCost
    Row(Modifier.fillMaxWidth().padding(top = 10.dp).testTag("save-cost"), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Save cost: close the voice session while agents work", style = BodyStyle, color = PrintInk)
            Text(
                "The call stays open and the phone listens. The paid voice session closes after a few quiet seconds and " +
                    "reopens when you speak or a result is ready; music can play in between.",
                style = SmallStyle, color = PrintMute,
            )
        }
        Switch(
            checked = on,
            onCheckedChange = { v -> onChange { if (grok) it.copy(grokSaveCost = v) else it.copy(gptLiveSaveCost = v) } },
            colors = SwitchDefaults.colors(checkedTrackColor = Ember),
            modifier = Modifier.testTag("save-cost-switch"),
        )
    }
}

/** Custom: the TTS model and the voice, from OpenRouter's list, with a preview per voice. Bill is the default. */
@Composable
fun CustomVoicePicker(settings: LiveSettings, picker: VoicePickerUi, actions: VoicePickerActions) {
    LaunchedEffect(Unit) { actions.onLoad(false) }
    val s = settings.normalized()
    val model = picker.model(s.ttsModel) ?: VoiceCatalog.DEFAULT_TTS.takeIf { it.id == s.ttsModel }
    var modelsOpen by remember { mutableStateOf(false) }
    var voicesOpen by remember { mutableStateOf(false) }
    Header("Voice model", model?.name ?: s.ttsModel, "tts-model-picker", open = modelsOpen) { modelsOpen = !modelsOpen }
    if (modelsOpen) {
        picker.ttsModels?.items.orEmpty().forEach { m ->
            Option(m.name, priceLine(m), chosen = m.id == s.ttsModel, tag = "tts-model-${m.id}") {
                actions.onChooseTtsModel(m.id)
                modelsOpen = false
            }
        }
    }
    Header("Voice", voiceLabel(s.billVoice), "voice-picker", open = voicesOpen) { voicesOpen = !voicesOpen }
    if (voicesOpen) {
        val voices = model?.voices ?: listOf(s.billVoice)
        voices.forEach { v ->
            Option(voiceLabel(v), null, chosen = v == s.billVoice, tag = "voice-$v", previewing = picker.previewing == "custom:$v", onPreview = { actions.onPreviewCustom(v) }) {
                actions.onChooseVoice(v)
            }
        }
    }
    SourceLine(picker.ttsModels?.source, picker.loading, "OpenRouter") { actions.onLoad(true) }
    Text("Changing the voice records the stock lines again in it (only the ones it hasn't said yet).", style = SmallStyle, color = PrintMute)
    picker.message?.let { Text(it, style = SmallStyle, color = Ember, modifier = Modifier.testTag("voice-picker-message")) }
}

/** Grok Voice: xAI's voices (the live list with a key, else the documented ones), with a preview through xAI's TTS. */
@Composable
fun GrokVoicePicker(settings: LiveSettings, picker: VoicePickerUi, actions: VoicePickerActions) {
    LaunchedEffect(Unit) { actions.onLoad(false) }
    val s = settings.normalized()
    var open by remember { mutableStateOf(false) }
    val chosen = s.grokVoice
    Header(
        "Grok's voice",
        chosen.replaceFirstChar { it.uppercase() } + if (s.grokCustomVoice.isNotEmpty()) " (custom voice in use)" else "",
        "grok-voice",
        open = open,
    ) { open = !open }
    if (open) {
        val voices = picker.grokVoices?.items ?: listOf(VoiceOption(chosen))
        voices.forEach { v ->
            Option(v.name, null, chosen = v.id == chosen && s.grokCustomVoice.isEmpty(), tag = "grok-voice-${v.id}", previewing = picker.previewing == "grok:${v.id}", onPreview = { actions.onPreviewGrok(v.id) }) {
                actions.onChooseGrokVoice(v.id)
            }
        }
    }
    SourceLine(picker.grokVoices?.source, picker.loading, "xAI") { actions.onLoad(true) }
    picker.message?.let { Text(it, style = SmallStyle, color = Ember, modifier = Modifier.testTag("voice-picker-message")) }
}

/** "Bill (default)" for the default voice, else the id capitalised. */
fun voiceLabel(id: String): String = id.replaceFirstChar { it.uppercase() } + if (id == LiveSettings.DEFAULT_VOICE) " (default)" else ""

/** The model's price line, from OpenRouter's per-character price. */
fun priceLine(m: TtsModelInfo): String = "${m.voices.size} voices" + (m.usdPerMillionChars?.let { " · $" + "%.2f".format(java.util.Locale.UK, it) + " per million characters" } ?: "")

@Composable
private fun Header(title: String, value: String, tag: String, open: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 10.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = BodyStyle, color = PrintInk)
            Text(value, style = SmallStyle, color = Ember)
        }
        Text(if (open) "Close" else "Change", style = SmallStyle, color = PrintMute)
    }
}

@Composable
private fun Option(
    title: String,
    detail: String?,
    chosen: Boolean,
    tag: String,
    previewing: Boolean = false,
    onPreview: (() -> Unit)? = null,
    onChoose: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.RadioButton, onClick = onChoose).semantics { selected = chosen }
            .padding(start = 12.dp, top = 6.dp, bottom = 6.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text((if (chosen) "● " else "○ ") + title, style = BodyStyle, color = if (chosen) PrintInk else PrintMute)
            detail?.let { Text(it, style = SmallStyle, color = PrintMute) }
        }
        onPreview?.let { Pill(if (previewing) "Playing…" else "Preview", filled = false, tag = "$tag-preview") { if (!previewing) it() } }
    }
}

@Composable
private fun SourceLine(source: VoiceSource?, loading: Boolean, from: String, onRefresh: () -> Unit) {
    val text = when {
        loading -> "Loading voices from $from…"
        source == VoiceSource.Live -> "Voices from $from."
        source == VoiceSource.Cached -> "Voices from $from (saved list)."
        source == VoiceSource.BuiltIn -> "$from's list isn't available; showing the built-in voices."
        else -> null
    } ?: return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text, style = SmallStyle, color = PrintMute, modifier = Modifier.weight(1f))
        if (!loading) Pill("Refresh", filled = false, tag = "voices-refresh", onClick = onRefresh)
    }
}
