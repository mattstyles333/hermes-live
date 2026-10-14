package com.folio.hermeslive.data

import com.folio.hermeslive.stt.ModelKind
import com.folio.hermeslive.stt.SttCatalog
import com.folio.hermeslive.stt.SttModel

/**
 * Settings › Voice › Speech models: which live (streaming) and accurate (second-pass) model a
 * Bill call uses. Defaults and the offered models come from the on-device bench
 * (`hermes-cascade-ondevice-models-2026-10-14.md`).
 *
 * - A new install gets [DEFAULT_LIVE] and [DEFAULT_ACCURATE].
 * - An install that already had speech models keeps what it had (Zipformer, and Parakeet-110M or
 *   0.6B with High accuracy) until the user picks: no surprise 600 MB download. It is told once
 *   that better models are available ([LiveSettings.speechModelsCard]).
 * - A call uses the chosen model only once it is downloaded; until then the installed fallback
 *   ([FALLBACK_LIVE], [FALLBACK_ACCURATE]), as High accuracy always did.
 */
object SpeechModelChoice {
    val FALLBACK_LIVE: SttModel = SttCatalog.zipformer
    val FALLBACK_ACCURATE: SttModel = SttCatalog.parakeet110m

    /** What Settings offers for each stage. */
    val LIVE: List<SttModel> = listOf(SttCatalog.zipformer, SttCatalog.nemotron, SttCatalog.moonshineBase)
    val ACCURATE: List<SttModel> = listOf(SttCatalog.parakeet110m, SttCatalog.parakeet06b)

    /**
     * The bench's rules (hub, 2026-10-14): Nemotron only if its emulator RTF at 4 threads is about
     * 1.5 or less (it was 2.2-4.3), else Moonshine base when it beats Zipformer on WER (live text
     * 8.9% vs 18.1%); Parakeet 0.6B when its p90 wait at commit stays within `accuratePassWaitMs`
     * (795 ms paced, against 1.5 s and 2.5 s).
     */
    val DEFAULT_LIVE: SttModel = SttCatalog.moonshineBase
    val DEFAULT_ACCURATE: SttModel = SttCatalog.parakeet06b

    /** A short name and what it is like, for the picker. */
    fun label(model: SttModel): String = when (model.id) {
        SttCatalog.zipformer.id -> "Zipformer"
        SttCatalog.nemotron.id -> "Nemotron 0.6B"
        SttCatalog.moonshineBase.id -> "Moonshine base"
        SttCatalog.parakeet110m.id -> "Parakeet 110M"
        SttCatalog.parakeet06b.id -> "Parakeet 0.6B"
        else -> model.id
    }

    /** One line on what the model is like (from the bench). */
    fun note(model: SttModel): String = NOTES[model.id] ?: ""

    private val NOTES = mapOf(
        SttCatalog.zipformer.id to "Light, takes your words; 18% word errors while you talk",
        SttCatalog.nemotron.id to "Best live words (7%), but too heavy for most phones; no custom words",
        SttCatalog.moonshineBase.id to "Default: 9% word errors while you talk, light; no custom words",
        SttCatalog.parakeet110m.id to "Quick; 6% word errors",
        SttCatalog.parakeet06b.id to "Default: 4.6% word errors, more names right",
    )

    /** The live model chosen (or the default). */
    fun live(s: LiveSettings): SttModel = s.liveModel?.let { id -> LIVE.firstOrNull { it.id == id } } ?: DEFAULT_LIVE

    /** The accurate model chosen (or the default). */
    fun accurate(s: LiveSettings): SttModel = s.accurateModel?.let { id -> ACCURATE.firstOrNull { it.id == id } } ?: DEFAULT_ACCURATE

    /**
     * The models these settings want on the phone: the chosen pair, plus Zipformer while the live
     * model is a large one the guard may have to replace mid-call.
     */
    fun wanted(s: LiveSettings): List<SttModel> = listOfNotNull(
        live(s),
        accurate(s),
        FALLBACK_LIVE.takeIf { live(s).kind == ModelKind.NEMOTRON_STREAMING },
    ).distinct()

    /** Settings may delete [id]: not the chosen pair, nor the fallbacks a call needs while they download or when the guard trips. */
    fun deletable(s: LiveSettings, id: String): Boolean =
        id !in wanted(s).map { it.id } && id != FALLBACK_LIVE.id && id != FALLBACK_ACCURATE.id

    /** The fallback the guard swaps to, or null when the live model is the fallback. */
    fun guardFallback(s: LiveSettings): SttModel? = FALLBACK_LIVE.takeIf { live(s) != FALLBACK_LIVE }

    /** The pair a call can use now: the chosen one where it is installed, else the fallback. Null if neither is. */
    fun forCall(s: LiveSettings, installed: (SttModel) -> Boolean): Pair<SttModel, SttModel>? {
        val live = live(s).takeIf(installed) ?: FALLBACK_LIVE.takeIf(installed) ?: return null
        val accurate = accurate(s).takeIf(installed) ?: FALLBACK_ACCURATE.takeIf(installed) ?: return null
        return live to accurate
    }

    /**
     * The first time the picker exists on this install ([LiveSettings.liveModel] still null): an
     * install with speech models keeps them and gets the card when the defaults differ; a new
     * one gets the defaults. Unchanged when a choice was already made.
     */
    fun firstRun(s: LiveSettings, installed: (SttModel) -> Boolean): LiveSettings {
        if (s.liveModel != null && s.accurateModel != null) return s
        val existing = installed(SttCatalog.zipformer) || installed(SttCatalog.parakeet110m) || installed(SttCatalog.parakeet06b)
        if (!existing) return s.copy(liveModel = DEFAULT_LIVE.id, accurateModel = DEFAULT_ACCURATE.id)
        val accurate = if (s.highAccuracySpeech) SttCatalog.parakeet06b else SttCatalog.parakeet110m
        val differs = SttCatalog.zipformer != DEFAULT_LIVE || accurate != DEFAULT_ACCURATE
        return s.copy(liveModel = SttCatalog.zipformer.id, accurateModel = accurate.id, speechModelsCard = differs)
    }
}
