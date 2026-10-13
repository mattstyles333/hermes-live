package com.folio.hermeslive.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What a speech model is for (spec v2 §11b). */
enum class ModelRole {
    /** Fast streaming pass (Zipformer-en int8 by default). R-STT-1. */
    Streaming,

    /** Accurate second pass (Parakeet-TDT-110M by default). R-STT-2. */
    Accurate,

    /** The optional "High accuracy" second pass (Parakeet-0.6B-v2): fetched only when that setting is on. */
    HighAccuracy,

    /** Voice activity (Silero, bundled). */
    Vad,
}

enum class ModelState { Missing, Downloading, Paused, Verifying, Ready, Failed }

/** One model as Settings shows it. [bytesTotal] is 0 when unknown. */
data class SpeechModelStatus(
    val id: String,
    val label: String,
    val role: ModelRole,
    val state: ModelState,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
) {
    val fraction: Float get() = if (bytesTotal > 0) (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f) else if (state == ModelState.Ready) 1f else 0f
}

/**
 * The speech models on the phone: what Settings › Voice › "Prepare Bill's voice" needs from
 * S-C's model manager (`stt/`). S-F adapts S-C's implementation to this.
 *
 * - [status] lists every model the app knows, and emits on every change, progress included.
 * - [download] fetches or resumes [id] (pinned URL and sha256 are S-C's) and returns true once
 *   it is ready. Cancelling the calling coroutine pauses it; calling again resumes it.
 * - [delete] removes a downloaded model.
 */
interface SpeechModels {
    val status: StateFlow<List<SpeechModelStatus>>

    suspend fun download(id: String): Boolean

    fun delete(id: String)

    /** Models still to fetch for these settings: everything not ready, the high-accuracy one only when it is on. */
    fun wanted(highAccuracy: Boolean): List<SpeechModelStatus> =
        status.value.filter { it.state != ModelState.Ready && (it.role != ModelRole.HighAccuracy || highAccuracy) }

    companion object {
        /** No model manager wired yet (before S-F): nothing to show or fetch. */
        val None: SpeechModels = object : SpeechModels {
            override val status: StateFlow<List<SpeechModelStatus>> = MutableStateFlow<List<SpeechModelStatus>>(emptyList()).asStateFlow()
            override suspend fun download(id: String): Boolean = false
            override fun delete(id: String) = Unit
        }
    }
}
