package com.folio.hermeslive.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import com.folio.hermes.live.HermesVoiceState
import com.folio.hermes.live.SideView
import com.folio.hermeslive.data.HermesVoiceUi
import com.folio.hermeslive.data.LiveSettings
import com.folio.hermeslive.data.SourceChoice
import com.folio.hermeslive.data.SpeechRecognition

/** What the Hermes part of Settings › Voice › Custom can do. */
data class HermesVoiceActions(
    val onRefresh: () -> Unit = {},
    val onVoiceFrom: (SourceChoice) -> Unit = {},
    val onSttFrom: (SourceChoice) -> Unit = {},
    val onChooseVoice: (String?) -> Unit = {},
    val onPreview: (String?) -> Unit = {},
)

/** "Hermes (default)", "Hermes" or "OpenRouter (ElevenLabs)": the choice as Settings words it. */
fun sourceLabel(choice: SourceChoice, openRouter: String): String = when (choice) {
    SourceChoice.Auto -> "Hermes (default)"
    SourceChoice.Hermes -> "Hermes"
    SourceChoice.OpenRouter -> openRouter
}

/** The other choice when the row is tapped (Auto counts as Hermes). */
fun nextSource(choice: SourceChoice): SourceChoice = if (choice.prefersHermes) SourceChoice.OpenRouter else SourceChoice.Hermes

/** Hermes's side in plain words: provider and model, and whether it is direct or relayed. Never a key or URL. */
fun hermesSideText(side: SideView?, state: HermesVoiceState, signedIn: Boolean, what: String): String = when {
    !signedIn -> "Sign in to Hermes to use its $what. Until then OpenRouter's is used."
    side != null && side.mode == "unavailable" -> "Hermes has no $what set up (${side.reason}). OpenRouter's is used."
    side != null -> listOfNotNull(providerName(side.provider), side.model).joinToString(" · ").ifEmpty { "Hermes's own" } + ". " + when (side.mode) {
        "direct" -> "Straight from your phone to ${providerName(side.provider) ?: "the provider"}, with Hermes's settings."
        else -> "Relayed through your Hermes server" + (side.reason?.let { " ($it)" } ?: "") + "."
    }
    state is HermesVoiceState.Unavailable -> when (state.kind) {
        "none" -> "Your Hermes server doesn't offer its voice settings (an older version). OpenRouter's $what is used."
        "refused", "no_key" -> "Hermes refused the request. Sign in again; OpenRouter's $what is used meanwhile."
        else -> "Hermes can't be reached right now. OpenRouter's $what is used until it answers."
    }
    else -> "Asking Hermes…"
}

private fun providerName(id: String?): String? = when (id) {
    null -> null
    "openai" -> "OpenAI"
    "elevenlabs" -> "ElevenLabs"
    "xai" -> "xAI"
    "deepinfra" -> "DeepInfra"
    "groq" -> "Groq"
    "mistral" -> "Mistral"
    else -> id.replaceFirstChar { it.uppercase() }
}

/** True when Hermes is preferred and its TTS can speak now (so OpenRouter's picker is not the voice). */
fun hermesSpeaks(s: LiveSettings, ui: HermesVoiceUi): Boolean = s.voiceFrom.prefersHermes && ui.signedIn && ui.tts?.available == true

/**
 * Settings › Voice › Custom › "Voice from": Hermes (the default when signed in and Hermes reports
 * a TTS) or OpenRouter (ElevenLabs). With Hermes: what it uses, and its voices when the TTS is
 * direct (ElevenLabs' list from Hermes, OpenAI's own list), else its configured voice, read-only.
 */
@Composable
fun HermesVoiceFromBlock(s: LiveSettings, ui: HermesVoiceUi, actions: HermesVoiceActions) {
    LaunchedEffect(Unit) { actions.onRefresh() }
    Choice("Voice from", sourceLabel(s.voiceFrom, "OpenRouter (ElevenLabs)"), "voice-from") { actions.onVoiceFrom(nextSource(s.voiceFrom)) }
    if (!s.voiceFrom.prefersHermes) return
    Text(hermesSideText(ui.tts, ui.state, ui.signedIn, "voice"), style = SmallStyle, color = PrintMute, modifier = Modifier.testTag("hermes-tts"))
    val tts = ui.tts
    if (hermesSpeaks(s, ui) && tts != null) {
        val picked = s.hermesVoiceFor(tts.provider)
        val shown = picked ?: tts.voice
        if (ui.canPick) {
            var open by remember { mutableStateOf(false) }
            val label = ui.voices?.firstOrNull { it.id == shown }?.name ?: shown ?: "Hermes's voice"
            Header("Hermes voice", label + if (picked == null) " (Hermes's setting)" else "", "hermes-voice-picker", open = open) { open = !open }
            if (open) {
                Option("Hermes's setting" + (tts.voice?.let { " ($it)" } ?: ""), null, chosen = picked == null, tag = "hermes-voice-default",
                    previewing = ui.previewing == "hermes:", onPreview = { actions.onPreview(null) }) { actions.onChooseVoice(null) }
                ui.voices.orEmpty().forEach { v ->
                    Option(v.name, null, chosen = picked == v.id, tag = "hermes-voice-${v.id}", previewing = ui.previewing == "hermes:${v.id}",
                        onPreview = { actions.onPreview(v.id) }) { actions.onChooseVoice(v.id) }
                }
            }
        } else {
            Line("Hermes voice", (shown ?: "Hermes's configured voice") + if (tts.mode == "relay") " (set on the server)" else "", Modifier.testTag("hermes-voice-readonly"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill(if (ui.previewing != null) "Playing…" else "Preview", filled = false, tag = "hermes-voice-preview") { if (ui.previewing == null) actions.onPreview(null) }
            }
        }
        Text("Changing the voice records the stock lines again in it (only the ones it hasn't said yet).", style = SmallStyle, color = PrintMute)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Pill(if (ui.loading) "Asking Hermes…" else "Ask Hermes again", filled = false, tag = "hermes-voice-refresh") { if (!ui.loading) actions.onRefresh() }
    }
    ui.message?.let { Text(it, style = SmallStyle, color = Ember, modifier = Modifier.testTag("hermes-voice-message")) }
}

/** Settings › Voice › Speech recognition (cloud on): "Cloud speech recognition from" Hermes or OpenRouter, and what Hermes uses. */
@Composable
fun HermesSttFromBlock(s: LiveSettings, ui: HermesVoiceUi, actions: HermesVoiceActions) {
    if (s.speechRecognition != SpeechRecognition.Cloud) return
    Choice("Cloud speech recognition from", sourceLabel(s.sttFrom, "OpenRouter"), "stt-from") { actions.onSttFrom(nextSource(s.sttFrom)) }
    if (!s.sttFrom.prefersHermes) return
    Text(hermesSideText(ui.stt, ui.state, ui.signedIn, "speech recognition"), style = SmallStyle, color = PrintMute, modifier = Modifier.testTag("hermes-stt"))
    if (hermesHears(s, ui)) {
        Text(
            "Your words list isn't sent this way" + (if (ui.stt?.provider in setOf("openai", "groq") && ui.stt?.mode == "direct") " except as a hint to OpenAI or Groq" else "") + ".",
            style = SmallStyle, color = PrintMute,
        )
    }
}

/** True when Hermes is preferred for cloud speech and can take it now. */
fun hermesHears(s: LiveSettings, ui: HermesVoiceUi): Boolean = s.sttFrom.prefersHermes && ui.signedIn && ui.stt?.available == true

/** Where the cloud clip goes, in plain words (the user must know when audio leaves the phone, and to whom). */
fun cloudAudioText(s: LiveSettings, ui: HermesVoiceUi): String = when {
    !hermesHears(s, ui) -> "Cloud when online: an audio clip of each thing you say is sent to OpenRouter " +
        "for better words, on your OpenRouter key. Offline, or if it is slow, the phone's own pass is used."
    ui.stt?.mode == "direct" -> "Cloud when online: an audio clip of each thing you say goes straight to " +
        (providerName(ui.stt?.provider) ?: "Hermes's provider") + " on your Hermes server's settings. If that fails, OpenRouter (with a key), " +
        "then the phone's own pass."
    else -> "Cloud when online: an audio clip of each thing you say goes to your Hermes server, which transcribes it. " +
        "If that fails, OpenRouter (with a key), then the phone's own pass."
}

/** Under the OpenRouter key: what Custom still needs it for, now that the voice and speech can come from Hermes. */
const val OPENROUTER_STILL_NEEDED = "Custom still needs this key for Jev (turn-taking and routing), the writer and transcript clean-up. " +
    "The voice and cloud speech recognition can come from your Hermes server instead (Voice from)."
