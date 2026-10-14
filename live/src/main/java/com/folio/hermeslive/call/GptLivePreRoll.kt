package com.folio.hermeslive.call

import com.folio.hermeslive.audio.Dbfs
import com.folio.hermeslive.data.LiveSettings
import com.folio.hermeslive.data.SpeechModelChoice
import com.folio.hermeslive.stt.ModelManager
import com.folio.hermeslive.stt.OfflineEngine
import com.folio.hermeslive.stt.SherpaEngines
import com.folio.hermeslive.stt.SttEngineConfig
import com.folio.hermeslive.stt.SttModel

/**
 * On-device text for GPT-Live's held audio: Bill's offline (second-pass) model when one is on the
 * phone, Parakeet-110M first (the smallest, about 0.1 s for a few seconds of speech), else the
 * chosen accurate model. Loaded on first use, freed at hang-up. Null from [of]: no model, so no
 * pre-roll text (the voice is told the start may be missing).
 */
class SherpaPreRoll private constructor(private val models: ModelManager, private val model: SttModel) : PreRollTranscriber {
    private var engine: OfflineEngine? = null

    @Synchronized
    override fun transcribe(pcm: ShortArray): String? {
        val e = engine ?: SherpaEngines.offline(models, model, SttEngineConfig(accurate = model)).also { engine = it }
        return e.decode(Dbfs.pcm16ToFloat(pcm), "").trim().ifEmpty { null }
    }

    @Synchronized
    override fun close() {
        engine?.release()
        engine = null
    }

    companion object {
        fun of(models: ModelManager, settings: LiveSettings): SherpaPreRoll? {
            val model = listOf(SpeechModelChoice.FALLBACK_ACCURATE, SpeechModelChoice.accurate(settings)).firstOrNull(models::isInstalled) ?: return null
            return SherpaPreRoll(models, model)
        }
    }
}
