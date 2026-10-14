package com.folio.hermeslive.data

import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the voice engine stands, for the top of Settings › Voice. */
enum class EngineState { GptLive, NeedsKey, KeyRefused, NeedsVoice, Ready }

data class EngineStatus(val state: EngineState, val keyUnchecked: Boolean = false, val bytesToFetch: Long = 0) {
    val text: String
        get() = when (state) {
            EngineState.GptLive -> "GPT-Live, through your Hermes server."
            EngineState.NeedsKey -> "Bill is off until you paste an OpenRouter key."
            EngineState.KeyRefused -> "OpenRouter refused the saved key. Paste a new one."
            EngineState.NeedsVoice -> "Bill is on. Prepare his voice before the first call" +
                (if (bytesToFetch > 0) " (about ${megabytes(bytesToFetch)} to download)." else ".")
            EngineState.Ready -> "Bill is ready." + if (keyUnchecked) " The key hasn't been checked yet." else ""
        }

    companion object {
        fun of(settings: LiveSettings, key: KeyStatus, prep: PrepState): EngineStatus {
            if (settings.voiceEngine == VoiceEngine.GptLive) return EngineStatus(EngineState.GptLive)
            if (!key.saved) return EngineStatus(EngineState.NeedsKey)
            if (key.check == KeyCheck.Refused) return EngineStatus(EngineState.KeyRefused)
            val modelsMissing = prep.models.any { it.state != ModelState.Ready }
            val state = if (prep.ready && !modelsMissing) EngineState.Ready else EngineState.NeedsVoice
            return EngineStatus(state, key.check == KeyCheck.Unchecked, prep.bytesToFetch)
        }

        fun megabytes(bytes: Long): String = "${(bytes + 524_288) / 1_048_576} MB"
    }
}

/** Everything Settings › Voice shows. */
data class VoiceUi(
    val settings: LiveSettings = LiveSettings(),
    val key: KeyStatus = KeyStatus(),
    val keyBusy: Boolean = false,
    /** The last key action's outcome ("Saved and checked."); never the key. */
    val keyMessage: String? = null,
    val engine: EngineStatus = EngineStatus(EngineState.GptLive),
    val prep: PrepState = PrepState(),
    /** On a metered network (show the Wi-Fi hint before downloading). */
    val metered: Boolean = false,
    /** Turns in the decision log (null until read). */
    val decisionTurns: Int? = null,
    val corrections: List<Correction> = emptyList(),
    val message: String? = null,
    /** Every speech model the picker offers, with its state (Settings › Voice › Speech models). */
    val speechModels: List<SpeechModelStatus> = emptyList(),
) {
    /** The cascade may run: Bill chosen and a usable key saved (the off-state gate's answer). */
    val cascadeActive: Boolean get() = engine.state == EngineState.NeedsVoice || engine.state == EngineState.Ready
}

/**
 * Settings › Voice's state and actions over the [VoiceShell]. Reads that would create files
 * (decision log, corrections) only happen when the files exist, so opening Settings with the
 * cascade off writes nothing.
 */
class VoiceSettingsController(private val shell: VoiceShell, private val scope: CoroutineScope) {
    private data class Local(
        val keyBusy: Boolean = false,
        val keyMessage: String? = null,
        val decisionTurns: Int? = null,
        val corrections: List<Correction> = emptyList(),
        val message: String? = null,
        val metered: Boolean = false,
    )

    private val local = MutableStateFlow(Local())

    val ui: StateFlow<VoiceUi> = combine(shell.settings.settings, shell.keys.status, shell.prep.state, local, shell.speechModels.status) { s, key, prep, l, models ->
        VoiceUi(s, key, l.keyBusy, l.keyMessage, EngineStatus.of(s, key, prep), prep, l.metered, l.decisionTurns, l.corrections, l.message, models)
    }.stateIn(scope, SharingStarted.Eagerly, VoiceUi(shell.settings.settings.value, shell.keys.status.value))

    private val downloads = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    /** Choose the live model (Settings › Voice › Speech models). It downloads only when asked. */
    fun chooseLive(id: String) = update { it.copy(liveModel = id, speechModelsCard = false) }

    /** Choose the accurate model. */
    fun chooseAccurate(id: String) = update { it.copy(accurateModel = id, speechModelsCard = false) }

    /** Download (or resume) one model, with progress in [VoiceUi.speechModels]. */
    fun downloadModel(id: String) {
        if (downloads[id]?.isActive == true) return
        downloads[id] = scope.launch { withContext(Dispatchers.IO) { shell.speechModels.download(id) } }
    }

    /** Pause a download (resumes from where it stopped). */
    fun cancelModel(id: String) {
        downloads.remove(id)?.cancel()
    }

    /** Delete a model, unless a call would need it: the chosen pair, and the fallbacks they need. */
    fun deleteModel(id: String) {
        if (!SpeechModelChoice.deletable(shell.settings.settings.value, id)) {
            local.update { it.copy(message = "That model is in use. Choose another one first.") }
            return
        }
        cancelModel(id)
        scope.launch(Dispatchers.IO) { shell.speechModels.delete(id) }
    }

    /** The one-time "Better speech models available" card was seen. */
    fun dismissModelsCard() = update { it.copy(speechModelsCard = false) }

    /** Re-read the log size, corrections and network (on opening the screen). */
    fun refresh() {
        scope.launch {
            val (turns, corrections) = withContext(Dispatchers.IO) {
                val turns = if (shell.dirs.decisions.exists()) shell.decisionLog.records().size else 0
                val corrections = if (shell.dirs.store.exists()) shell.vocabulary.corrections() else emptyList()
                turns to corrections
            }
            local.update { it.copy(decisionTurns = turns, corrections = corrections, metered = shell.network.metered()) }
        }
    }

    fun update(transform: (LiveSettings) -> LiveSettings) {
        shell.settings.update(transform)
        shell.onSettingsChanged()
    }

    /** Check [clip] with OpenRouter and save it sealed. [onSaved] runs once it is saved (clear the clipboard there). */
    fun pasteKey(clip: CharSequence?, onSaved: () -> Unit = {}) {
        when (val parsed = KeyPaste.parse(clip)) {
            is KeyPaste.Parsed.Problem -> local.update { it.copy(keyMessage = parsed.message) }
            is KeyPaste.Parsed.Key -> {
                local.update { it.copy(keyBusy = true, keyMessage = "Checking with OpenRouter…") }
                scope.launch {
                    val result = withContext(Dispatchers.IO) { shell.keyChecker.check(parsed.key) }
                    val message = withContext(Dispatchers.IO) {
                        when (result) {
                            KeyCheckResult.Valid -> {
                                shell.keys.save(parsed.key, KeyCheck.Valid)
                                "Saved and checked."
                            }
                            KeyCheckResult.Refused -> "OpenRouter refused that key. Nothing was saved."
                            is KeyCheckResult.Unreachable -> {
                                shell.keys.save(parsed.key, KeyCheck.Unchecked)
                                "Saved. OpenRouter couldn't be reached to check it (${result.kind}); check again later."
                            }
                        }
                    }
                    if (result != KeyCheckResult.Refused) onSaved()
                    shell.onSettingsChanged()
                    local.update { it.copy(keyBusy = false, keyMessage = message) }
                }
            }
        }
    }

    fun recheckKey() {
        local.update { it.copy(keyBusy = true, keyMessage = "Checking with OpenRouter…") }
        scope.launch {
            val message = withContext(Dispatchers.IO) {
                val key = shell.keys.key() ?: return@withContext "No key saved."
                when (val r = shell.keyChecker.check(key)) {
                    KeyCheckResult.Valid -> "OpenRouter accepts the key.".also { shell.keys.setCheck(KeyCheck.Valid) }
                    KeyCheckResult.Refused -> "OpenRouter refused the key.".also { shell.keys.setCheck(KeyCheck.Refused) }
                    is KeyCheckResult.Unreachable -> "OpenRouter couldn't be reached (${r.kind})."
                }
            }
            shell.onSettingsChanged()
            local.update { it.copy(keyBusy = false, keyMessage = message) }
        }
    }

    fun removeKey() {
        shell.keys.remove()
        shell.onSettingsChanged()
        local.update { it.copy(keyMessage = "Key removed from this phone.") }
    }

    fun prepare() {
        if (!shell.prep.start()) local.update { it.copy(message = if (!shell.gate.active) "Choose Bill and save a key first." else null) }
    }

    fun cancelPrepare() = shell.prep.cancel()

    /** The share-sheet intent for the decision log, or null (with a message) when it is empty. */
    suspend fun shareDecisionLog(): Intent? {
        val intent = withContext(Dispatchers.IO) {
            if (!shell.dirs.decisions.exists()) null else DecisionLogShare.prepare(shell.context, shell.decisionLog, shell.dirs)
        }
        if (intent == null) local.update { it.copy(message = "Nothing in the decision log yet.") }
        return intent
    }

    fun clearDecisionLog() {
        scope.launch {
            withContext(Dispatchers.IO) {
                if (shell.dirs.decisions.exists()) DecisionLogShare.clear(shell.decisionLog, shell.dirs)
                DecisionLogShare.file(shell.dirs).delete()
            }
            local.update { it.copy(decisionTurns = 0, message = "Decision log cleared.") }
        }
    }

    fun forgetCorrection(heard: String) {
        scope.launch {
            val list = withContext(Dispatchers.IO) {
                shell.vocabulary.forget(heard)
                shell.vocabulary.corrections()
            }
            local.update { it.copy(corrections = list) }
        }
    }

    fun dismissMessage() = local.update { it.copy(message = null) }
}
