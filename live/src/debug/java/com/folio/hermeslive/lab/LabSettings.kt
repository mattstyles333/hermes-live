package com.folio.hermeslive.lab

/** Capture sources under test; values are android.media.MediaRecorder.AudioSource constants. */
enum class CaptureSource(val androidValue: Int, val label: String) {
    VOICE_COMMUNICATION(7, "Voice comm"),
    VOICE_RECOGNITION(6, "Voice recog"),
    MIC(1, "Mic"),
}

data class LabSettings(
    val source: CaptureSource = CaptureSource.VOICE_COMMUNICATION,
    val streamingId: String = ModelCatalog.zipformer.id,
    /** [NONE] turns the second pass off. */
    val offlineId: String = ModelCatalog.parakeet110m.id,
    val threads: Int = 2,
    val decoding: String = Hotwords.BEAM,
    /** MODE_IN_COMMUNICATION with the loudspeaker as communication device, as in a call. */
    val callMode: Boolean = true,
    val hotwordsText: String = "",
) {
    val streaming: LabModel get() = ModelCatalog.byId(streamingId) ?: ModelCatalog.zipformer
    val offline: LabModel? get() = ModelCatalog.byId(offlineId)

    companion object {
        const val NONE = "none"
        val THREADS = listOf(1, 2, 4)
    }
}
