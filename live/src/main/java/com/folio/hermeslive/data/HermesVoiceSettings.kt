package com.folio.hermeslive.data

import com.folio.hermes.live.HermesVoiceState
import com.folio.hermes.live.SideView
import com.folio.hermes.openrouter.OpenRouterResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Settings › Voice › Custom's Hermes part: what Hermes uses and the voices it offers. Never a key. */
data class HermesVoiceUi(
    val state: HermesVoiceState = HermesVoiceState.Unknown,
    val signedIn: Boolean = false,
    /** Voices the picker offers for Hermes's TTS (null: read-only, Hermes's own voice). */
    val voices: List<VoiceOption>? = null,
    val loading: Boolean = false,
    /** The voice being previewed ("hermes:<id>"), or null. */
    val previewing: String? = null,
    val message: String? = null,
) {
    val tts: SideView? get() = (state as? HermesVoiceState.Ready)?.view?.tts
    val stt: SideView? get() = (state as? HermesVoiceState.Ready)?.view?.stt

    /** The picker can choose a voice: Hermes's TTS is direct (the relay always speaks its configured voice). */
    val canPick: Boolean get() = tts?.mode == "direct" && voices != null
}

/**
 * The Hermes part of Settings › Voice › Custom (Hermes voice slice): "Voice from", "Cloud speech
 * recognition from", what Hermes uses (provider and model, direct or relayed), and its voices
 * (ElevenLabs' from `GET /api/audio/elevenlabs/voices` when the provider is ElevenLabs, OpenAI's
 * own list for OpenAI; anything else is Hermes's configured voice, read-only).
 */
class HermesVoiceSettingsController(
    private val scope: CoroutineScope,
    private val shell: VoiceShell,
    private val signedIn: () -> Boolean,
) {
    private data class Local(val voices: List<VoiceOption>? = null, val voicesFor: String? = null, val loading: Boolean = false, val previewing: String? = null, val message: String? = null)

    private val local = MutableStateFlow(Local())
    private var previewJob: Job? = null

    val ui: StateFlow<HermesVoiceUi> = combine(shell.hermesVoices.state, local) { st, l ->
        val provider = (st as? HermesVoiceState.Ready)?.view?.tts?.takeIf { it.mode == "direct" }?.provider
        HermesVoiceUi(st, signedIn(), l.voices?.takeIf { l.voicesFor == provider }, l.loading, l.previewing, l.message)
    }.stateIn(scope, SharingStarted.Eagerly, HermesVoiceUi(signedIn = signedIn()))

    /** Ask Hermes afresh (Settings opened, or Refresh), then load its voices. Nothing while signed out or not wanted. */
    fun refresh() {
        local.update { it.copy(loading = true, message = null) }
        scope.launch {
            val cfg = runCatching { shell.hermesVoices.refresh(force = true) }.getOrNull()
            val tts = cfg?.tts?.let(SideView::of)
            val voices = when {
                tts?.mode != "direct" -> null
                tts.provider == "elevenlabs" -> elevenLabs()
                tts.provider == "openai" -> openAiVoices(tts.model).map { VoiceOption(it, it.replaceFirstChar(Char::uppercase)) }
                else -> null
            }
            local.update { it.copy(voices = voices, voicesFor = tts?.provider, loading = false) }
        }
    }

    private suspend fun elevenLabs(): List<VoiceOption>? {
        val r = shell.hermesVoices.voice()?.elevenLabsVoices() ?: return null
        val list = (r as? OpenRouterResult.Ok)?.value?.takeIf { it.available } ?: return null
        return list.voices.map { VoiceOption(it.voice_id, it.label) }
    }

    fun chooseVoiceFrom(choice: SourceChoice) {
        shell.settings.update { it.copy(voiceFrom = choice) }
        shell.onSettingsChanged()
        if (choice.prefersHermes) refresh() else if (!shell.hermesVoiceWanted(shell.settings.settings.value)) shell.hermesVoices.forget()
    }

    fun chooseSttFrom(choice: SourceChoice) {
        shell.settings.update { it.copy(sttFrom = choice) }
        if (choice.prefersHermes) refresh() else if (!shell.hermesVoiceWanted(shell.settings.settings.value)) shell.hermesVoices.forget()
    }

    /** Pick [id] for Hermes's current provider; null or blank: Hermes's own voice. */
    fun chooseVoice(id: String?) {
        val provider = ui.value.tts?.takeIf { it.mode == "direct" }?.provider ?: return
        shell.settings.update { it.copy(hermesVoice = id?.trim()?.takeIf { v -> v.isNotEmpty() }?.let { v -> "$provider:$v" }.orEmpty()) }
        shell.onSettingsChanged()
    }

    fun preview(id: String?) {
        if (previewJob?.isActive == true) return
        local.update { it.copy(previewing = "hermes:${id.orEmpty()}", message = null) }
        previewJob = scope.launch {
            val message = when (runCatching { shell.preview.hermes(id) }.getOrNull()) {
                PreviewOutcome.Played -> null
                PreviewOutcome.NoKey -> "Sign in to Hermes to hear its voice."
                else -> "Couldn't get a preview from Hermes. Check the connection and try again."
            }
            local.update { it.copy(previewing = null, message = message) }
        }
    }

    companion object {
        /** OpenAI's TTS voices (platform docs, 2026-10); `tts-1` models have the first nine. */
        val OPENAI_VOICES = listOf("alloy", "ash", "coral", "echo", "fable", "nova", "onyx", "sage", "shimmer", "ballad", "verse", "marin", "cedar")

        fun openAiVoices(model: String?): List<String> = if (model?.startsWith("tts-1") == true) OPENAI_VOICES.take(9) else OPENAI_VOICES
    }
}
