package com.folio.hermeslive.lab

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the Report button shares: device, settings, model names and numbers only. There is no
 * field for text of any kind (no transcripts, no hotword phrases) and none for audio; the
 * only strings are device and app identifiers, model ids and setting names.
 * LabReportTest walks the JSON and fails on anything else.
 */
@Serializable
data class LabReport(
    val schema: String = SCHEMA,
    @SerialName("created_utc") val createdUtc: String,
    val device: DeviceReport,
    val app: AppReport,
    val settings: SettingsReport,
    val models: List<ModelReport>,
    val aec: List<AecReport>,
    val live: LiveReport,
    val confidence: ConfidenceReport,
    val hotwords: HotwordReport,
    val echo: List<EchoTrial>,
    val bench: List<BenchRow>,
) {
    fun toJson(): String = JSON.encodeToString(serializer(), this)

    companion object {
        const val SCHEMA = "hermes-stt-lab/1"
        private val JSON = Json {
            prettyPrint = true
            encodeDefaults = true
        }
    }
}

@Serializable
data class DeviceReport(
    val manufacturer: String,
    val model: String,
    @SerialName("soc_model") val socModel: String,
    @SerialName("android_release") val androidRelease: String,
    @SerialName("sdk_int") val sdkInt: Int,
    val cores: Int,
    val abi: String,
)

@Serializable
data class AppReport(
    @SerialName("version_name") val versionName: String,
    @SerialName("build_type") val buildType: String,
    @SerialName("sherpa_version") val sherpaVersion: String,
    @SerialName("onnxruntime_version") val onnxruntimeVersion: String,
)

@Serializable
data class SettingsReport(
    @SerialName("capture_source") val captureSource: String,
    @SerialName("streaming_model") val streamingModel: String,
    @SerialName("offline_model") val offlineModel: String,
    val threads: Int,
    @SerialName("decoding_method") val decodingMethod: String,
    @SerialName("call_mode") val callMode: Boolean,
    @SerialName("endpoint_trailing_silence_s") val endpointTrailingSilenceS: Float,
    @SerialName("vad_threshold") val vadThreshold: Float,
    @SerialName("vad_min_speech_s") val vadMinSpeechS: Float,
    @SerialName("vad_min_silence_s") val vadMinSilenceS: Float,
    @SerialName("vad_max_speech_s") val vadMaxSpeechS: Float,
    @SerialName("voice_call_volume") val voiceCallVolume: Double?,
)

@Serializable
data class ModelReport(
    val model: String,
    val ready: Boolean,
    @SerialName("sha256_ok") val sha256Ok: Boolean?,
    @SerialName("download_s") val downloadS: Double?,
    @SerialName("extract_s") val extractS: Double?,
    @SerialName("bytes_on_disk") val bytesOnDisk: Long,
    @SerialName("vocab_pieces") val vocabPieces: Int?,
)

@Serializable
data class AecReport(
    val source: String,
    val available: Boolean,
    val created: Boolean,
    val enabled: Boolean,
)

@Serializable
data class LiveReport(
    val utterances: Int,
    @SerialName("stream_rtf") val streamRtf: Double?,
    @SerialName("slow_chunks") val slowChunks: Int,
    @SerialName("read_errors") val readErrors: Int,
    @SerialName("mean_speech_dbfs") val meanSpeechDbfs: Double?,
    @SerialName("second_pass_ms_p50") val secondPassMsP50: Double?,
    @SerialName("second_pass_ms_max") val secondPassMsMax: Double?,
    @SerialName("second_pass_rtf_mean") val secondPassRtfMean: Double?,
    @SerialName("streaming_words") val streamingWords: Int,
    @SerialName("streaming_words_differing") val streamingWordsDiffering: Int,
    @SerialName("second_pass_words") val secondPassWords: Int,
    @SerialName("second_pass_words_differing") val secondPassWordsDiffering: Int,
)

@Serializable
data class ConfidenceReport(
    @SerialName("agree_words") val agreeWords: Int,
    @SerialName("disagree_words") val disagreeWords: Int,
    @SerialName("agree_p10") val agreeP10: Double?,
    @SerialName("agree_p50") val agreeP50: Double?,
    @SerialName("disagree_p50") val disagreeP50: Double?,
    @SerialName("disagree_p90") val disagreeP90: Double?,
    @SerialName("suggested_threshold") val suggestedThreshold: Double?,
)

@Serializable
data class HotwordReport(
    val phrases: Int,
    @SerialName("parse_errors") val parseErrors: Int,
    @SerialName("streaming_supported") val streamingSupported: Boolean,
    @SerialName("offline_supported") val offlineSupported: Boolean,
    @SerialName("streaming_encodable") val streamingEncodable: Int?,
    @SerialName("offline_encodable") val offlineEncodable: Int?,
    @SerialName("self_test_streaming_changed") val selfTestStreamingChanged: Boolean?,
    @SerialName("self_test_offline_changed") val selfTestOfflineChanged: Boolean?,
)

@Serializable
data class EchoTrial(
    val source: String,
    @SerialName("call_mode") val callMode: Boolean,
    @SerialName("aec_enabled") val aecEnabled: Boolean,
    @SerialName("playback_path") val playbackPath: String,
    @SerialName("playback_dbfs") val playbackDbfs: Double?,
    @SerialName("voice_call_volume") val voiceCallVolume: Double?,
    @SerialName("echo_input_dbfs_median") val echoInputDbfsMedian: Double?,
    @SerialName("echo_words") val echoWords: Int,
    @SerialName("echo_words_in_passage") val echoWordsInPassage: Int,
    @SerialName("vad_false_speech_ratio") val vadFalseSpeechRatio: Double,
    @SerialName("vad_busy_at_prompt") val vadBusyAtPrompt: Boolean,
    @SerialName("cut_in_detected") val cutInDetected: Boolean,
    @SerialName("cut_in_ms") val cutInMs: Double?,
    @SerialName("prompt_to_vad_ms") val promptToVadMs: Double?,
    @SerialName("stop_playback_ms") val stopPlaybackMs: Double?,
    @SerialName("slow_chunks") val slowChunks: Int,
)

@Serializable
data class BenchRow(
    val model: String,
    val pass: String,
    val threads: Int,
    @SerialName("decoding_method") val decodingMethod: String,
    @SerialName("load_ms") val loadMs: Double,
    @SerialName("wav_s") val wavS: Double,
    val rtf: Double,
    val wer: Double?,
)
