package com.folio.hermeslive.data

import com.folio.hermes.cascade.PhraseBank
import com.folio.hermes.cascade.PhraseRenderer
import com.folio.hermes.cascade.RenderProgress
import com.folio.hermes.telemetry.TelemetrySink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PrepPhase { Idle, Running, Done, Cancelled, Failed }

/**
 * "Preparing Bill's voice": the stock lines rendered into the TTS cache ([phrases]) and the
 * speech models fetched ([models]). [ready] is true once a render for the current voice, model
 * and bank version finished with nothing skipped.
 */
data class PrepState(
    val phase: PrepPhase = PrepPhase.Idle,
    val phrases: RenderProgress = RenderProgress(),
    val models: List<SpeechModelStatus> = emptyList(),
    val ready: Boolean = false,
) {
    /** Bytes the wanted models still need (for the Wi-Fi hint). */
    val bytesToFetch: Long get() = models.filter { it.state != ModelState.Ready }.sumOf { (it.bytesTotal - it.bytesDone).coerceAtLeast(0) }
}

/** One render of the bank (a fresh [PhraseRenderer] over the current voice and model). */
/** One render: the renderer and the fingerprint it renders to; [warm] opens the OpenRouter connection first (no key: nothing). */
class RenderJob(val renderer: PhraseRenderer, val fingerprint: String, val warm: suspend () -> Unit = {})

/**
 * Runs "Prepare Bill's voice" from Settings: renders every stock line (at most 4 requests at a
 * time) and downloads the wanted speech models in parallel. Cancel stops both; Prepare again
 * resumes (cached lines are skipped; model downloads resume).
 *
 * Off state: [start] refuses unless the [gate] is open, and [renders] (which builds an
 * OpenRouter client) is called only inside [start].
 */
class VoicePrep(
    private val scope: CoroutineScope,
    private val gate: CascadeGate,
    private val models: SpeechModels,
    private val highAccuracy: () -> Boolean,
    private val bank: () -> PhraseBank,
    /** Builds the render for the current voice and model; called only while the gate is open. */
    private val renders: () -> RenderJob,
    /** The fingerprint of the last complete render, or null; and where to save a new one. */
    private val prepared: PreparedMarker,
    /** The fingerprint the current settings would render to (voice, model, bank version). */
    private val currentFingerprint: () -> String,
    private val telemetry: TelemetrySink = SafeTelemetry(),
) {
    private val _state = MutableStateFlow(PrepState(ready = prepared.get() == currentFingerprint()))
    val state: StateFlow<PrepState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        scope.launch { models.status.collect { list -> _state.update { it.copy(models = shown(list)) } } }
    }

    /** The models these settings use: the high-accuracy one only when it is on. */
    private fun shown(list: List<SpeechModelStatus>) = list.filter { it.role != ModelRole.HighAccuracy || highAccuracy() }

    /** Re-read whether the current settings are prepared (after a voice, model or accuracy change). */
    fun refresh() = _state.update { it.copy(ready = prepared.get() == currentFingerprint(), models = shown(models.status.value)) }

    /** Start or resume. False (and nothing built) when the cascade is off or a run is going. */
    @Synchronized
    fun start(): Boolean {
        if (!gate.active || job?.isActive == true) return false
        _state.update { it.copy(phase = PrepPhase.Running) }
        val started = System.nanoTime()
        job = scope.launch {
            val outcome = try {
                coroutineScope {
                    val render = renders()
                    // "Prepare" usually comes just before a call ("Preparing Bill…"): open the connection now.
                    launch { runCatching { render.warm() } }
                    val watch = launch { render.renderer.progress.collect { p -> _state.update { it.copy(phrases = p) } } }
                    val downloads = launch {
                        models.wanted(highAccuracy()).forEach { m -> models.download(m.id) }
                    }
                    val result = render.renderer.render(bank())
                    downloads.join()
                    watch.cancel()
                    _state.update { it.copy(phrases = result) }
                    val modelsOk = models.wanted(highAccuracy()).isEmpty()
                    if (result.failed == 0 && result.total > 0) prepared.set(render.fingerprint)
                    if (result.failed == 0 && modelsOk) PrepPhase.Done else PrepPhase.Failed
                }
            } catch (e: CancellationException) {
                PrepPhase.Cancelled
            } catch (e: Exception) {
                PrepPhase.Failed
            }
            val s = _state.value
            _state.value = s.copy(phase = outcome, ready = prepared.get() == currentFingerprint())
            runCatching {
                telemetry.event(
                    "cascade.prepare",
                    mapOf(
                        "outcome" to outcome.name.lowercase(), "lines" to s.phrases.total, "done" to s.phrases.done,
                        "failed" to s.phrases.failed, "models_missing" to models.wanted(highAccuracy()).size,
                        "ms" to (System.nanoTime() - started) / 1_000_000,
                    ),
                    outcome == PrepPhase.Failed,
                )
            }
        }
        return true
    }

    /** Stop now; [start] resumes. */
    @Synchronized
    fun cancel() {
        job?.cancel()
    }

    /** Where the last complete render's fingerprint lives (the cascade store, key `cascade.shell.voice.prepared`). */
    interface PreparedMarker {
        fun get(): String?
        fun set(fingerprint: String)
    }

    companion object {
        /** `voice|model|bank version`: a new value means the lines must be rendered again. */
        fun fingerprint(voice: String, model: String, bankVersion: Int): String = "$voice|$model|$bankVersion"
    }
}
