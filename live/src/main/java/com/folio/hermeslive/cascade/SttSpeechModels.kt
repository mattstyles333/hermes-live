package com.folio.hermeslive.cascade

import com.folio.hermeslive.data.ModelRole
import com.folio.hermeslive.data.ModelState
import com.folio.hermeslive.data.SpeechModelChoice
import com.folio.hermeslive.data.SpeechModelStatus
import com.folio.hermeslive.data.SpeechModels
import com.folio.hermeslive.stt.ModelManager
import com.folio.hermeslive.stt.ModelStatus
import com.folio.hermeslive.stt.SttCatalog
import com.folio.hermeslive.stt.SttModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * S-C's [ModelManager] as S-E's [SpeechModels] (Settings › Voice › "Prepare Bill's voice" and
 * "Speech models"): every live and accurate model the picker offers ([SpeechModelChoice]), and the
 * bundled Silero VAD, always ready. Nothing downloads until [download] is called.
 */
class SttSpeechModels(private val manager: ModelManager, scope: CoroutineScope) : SpeechModels {
    private val rows = SpeechModelChoice.LIVE.map { Row(it, SpeechModelChoice.label(it), ModelRole.Streaming) } +
        SpeechModelChoice.ACCURATE.map { Row(it, SpeechModelChoice.label(it), ModelRole.Accurate) }

    private class Row(val model: SttModel, val label: String, val role: ModelRole)

    override val status: StateFlow<List<SpeechModelStatus>> =
        manager.models.map(::list).stateIn(scope, SharingStarted.Eagerly, list(manager.models.value))

    private fun list(states: Map<String, ModelStatus>): List<SpeechModelStatus> = rows.map { r ->
        val st = states[r.model.id] ?: ModelStatus.Missing
        val (state, done) = when (st) {
            ModelStatus.Missing -> ModelState.Missing to 0L
            is ModelStatus.Downloading -> ModelState.Downloading to st.done
            ModelStatus.Installing -> ModelState.Verifying to r.model.bytes
            is ModelStatus.Ready -> ModelState.Ready to r.model.bytes
            is ModelStatus.Failed -> (if (st.reason == ModelStatus.Reason.CANCELLED) ModelState.Paused else ModelState.Failed) to 0L
        }
        SpeechModelStatus(r.model.id, r.label, r.role, state, done, r.model.bytes)
    } + SpeechModelStatus(VAD_ID, "Voice detection (built in)", ModelRole.Vad, ModelState.Ready)

    override suspend fun download(id: String): Boolean {
        if (id == VAD_ID) return true
        val model = SttCatalog.byId(id) ?: return false
        return manager.fetch(model)
    }

    override fun delete(id: String) {
        val model = SttCatalog.byId(id) ?: return
        manager.deleteModel(model)
    }

    companion object {
        const val VAD_ID = "silero-vad"
    }
}
