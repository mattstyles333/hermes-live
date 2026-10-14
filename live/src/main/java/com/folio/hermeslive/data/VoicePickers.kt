package com.folio.hermeslive.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Settings › Voice's voice pickers (ondemand slice). */
data class VoicePickerUi(
    /** Custom: OpenRouter's speech models and their voices (null until loaded). */
    val ttsModels: VoiceList<TtsModelInfo>? = null,
    /** Grok Voice: xAI's voices (null until loaded). */
    val grokVoices: VoiceList<VoiceOption>? = null,
    val loading: Boolean = false,
    /** The voice being previewed now ("custom:<id>" or "grok:<id>"), or null. */
    val previewing: String? = null,
    val message: String? = null,
) {
    /** The chosen TTS model's entry (or the default's when the list does not have it). */
    fun model(id: String): TtsModelInfo? = ttsModels?.items?.firstOrNull { it.id == id }
}

/**
 * The voice pickers' state and actions: load the lists (cached a day), choose a TTS model (the
 * voice follows if the new model lacks it: Bill, else its first voice), choose a voice, preview
 * one. Changing the Custom voice or model re-renders the stock lines in it ([update] goes through
 * the shell's settings change, which starts the render when Custom is on).
 */
class VoicePickerController(
    private val scope: CoroutineScope,
    private val catalog: VoiceCatalog,
    private val preview: VoicePreview,
    private val settings: () -> LiveSettings,
    /** Save a settings change (and let the shell react to it). */
    private val update: ((LiveSettings) -> LiveSettings) -> Unit,
    private val xaiKey: () -> String?,
) {
    private val _ui = MutableStateFlow(VoicePickerUi())
    val ui: StateFlow<VoicePickerUi> = _ui.asStateFlow()
    private var previewJob: Job? = null

    /** Load both lists (from the day's cache unless [force]). */
    fun load(force: Boolean = false) {
        _ui.update { it.copy(loading = true) }
        scope.launch {
            val models = catalog.ttsModels(force)
            val grok = catalog.grokVoices(xaiKey(), force)
            _ui.update { it.copy(ttsModels = models, grokVoices = grok, loading = false) }
        }
    }

    fun chooseTtsModel(id: String) {
        val model = _ui.value.model(id) ?: return
        update { it.copy(ttsModel = model.id, billVoice = VoiceCatalog.voiceFor(model, it.billVoice)) }
    }

    fun chooseVoice(id: String) {
        if (id.isBlank() || id == settings().billVoice) return
        update { it.copy(billVoice = id) }
    }

    fun chooseGrokVoice(id: String) {
        if (id.isBlank()) return
        update { it.copy(grokVoice = id, grokCustomVoice = "") }
    }

    fun previewCustom(voice: String) = run("custom:$voice") {
        when (preview.custom(settings().normalized().ttsModel, voice)) {
            PreviewOutcome.Played -> null
            PreviewOutcome.NoKey -> "Save your OpenRouter key above to hear a preview."
            PreviewOutcome.Failed -> "Couldn't get a preview. Check the connection and try again."
        }
    }

    fun previewGrok(voice: String) = run("grok:$voice") {
        when (preview.grok(voice)) {
            PreviewOutcome.Played -> null
            PreviewOutcome.NoKey -> "Save your xAI key above to hear a preview."
            PreviewOutcome.Failed -> "Couldn't get a preview from xAI. Check the connection and try again."
        }
    }

    private fun run(tag: String, block: suspend () -> String?) {
        if (previewJob?.isActive == true) return
        _ui.update { it.copy(previewing = tag, message = null) }
        previewJob = scope.launch {
            val message = runCatching { block() }.getOrElse { "Couldn't play the preview." }
            _ui.update { it.copy(previewing = null, message = message) }
        }
    }
}
