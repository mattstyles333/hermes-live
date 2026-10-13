package com.folio.hermeslive.lab

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shared report carries no content: no transcripts, no hotword text, no audio. */
class LabReportTest {
    /** The only keys allowed to hold strings: identifiers, model ids and setting names. */
    private val stringKeys = setOf(
        "schema", "created_utc", "manufacturer", "model", "soc_model", "android_release", "abi",
        "version_name", "build_type", "sherpa_version", "onnxruntime_version",
        "capture_source", "streaming_model", "offline_model", "decoding_method",
        "source", "playback_path", "pass",
    )

    private val forbidden = listOf("text", "transcript", "audio_data", "pcm", "samples", "phrase_text", "words_list", "hotword_text", "utterance_text")

    private fun sample(): LabReport = LabReport(
        createdUtc = "2026-10-13T20:00:00Z",
        device = DeviceReport("samsung", "SM-S911B", "SM8550", "16", 36, 8, "arm64-v8a"),
        app = AppReport("1.0.2", "debug", "1.13.8", "1.23.2"),
        settings = SettingsReport("VOICE_COMMUNICATION", "zipformer-en-2023-06-26", "parakeet-tdt-110m", 2, "modified_beam_search", true, 1.0f, 0.5f, 0.25f, 0.25f, 30f, 0.8),
        models = ModelCatalog.all.map { ModelReport(it.id, true, true, 12.5, 3.0, it.bytes, 500) },
        aec = CaptureSource.entries.map { AecReport(it.name, true, true, false, true) },
        live = LiveReport(4, 0.2, 0, 0, -32.0, 180.0, 400.0, 0.08, 40, 5, 41, 6),
        confidence = ConfidenceReport(35, 5, 0.8, 0.97, 0.55, 0.85, 0.7),
        hotwords = HotwordReport(3, 0, true, true, 3, 3, true, false),
        echo = listOf(EchoTrial("MIC", true, false, "audiotrack_voice_communication", true, -18.0, 0.8, -40.0, 12, 10, 0.4, false, true, 260.0, 600.0, 0.4, 0)),
        bench = listOf(BenchRow("zipformer-en-2023-06-26", "streaming", 4, "modified_beam_search", 800.0, 25.0, 0.1, 0.05)),
    )

    private fun walk(key: String, e: JsonElement, visit: (String, JsonElement) -> Unit) {
        visit(key, e)
        when (e) {
            is JsonObject -> e.forEach { (k, v) -> walk(k, v, visit) }
            is JsonArray -> e.forEach { walk(key, it, visit) }
            else -> Unit
        }
    }

    @Test fun onlyNumbersBooleansAndIdentifiers() {
        val json = Json.parseToJsonElement(sample().toJson())
        walk("", json) { key, e ->
            forbidden.forEach { assertFalse("forbidden key $key", key.contains(it)) }
            if (e is JsonPrimitive && e !is JsonNull && e.isString) {
                assertTrue("string under unexpected key '$key'", key in stringKeys)
                assertTrue("string too long under '$key'", e.content.length <= 64)
            }
            if (e is JsonArray) {
                assertFalse("array of strings under '$key'", e.any { it is JsonPrimitive && it.isString })
            }
        }
    }

    @Test fun reportClassesHaveNoTextFields() {
        val classes = listOf(
            LabReport::class, DeviceReport::class, AppReport::class, SettingsReport::class, ModelReport::class,
            AecReport::class, LiveReport::class, ConfidenceReport::class, HotwordReport::class, EchoTrial::class, BenchRow::class,
        )
        val stringFields = setOf(
            "schema", "createdUtc", "manufacturer", "model", "socModel", "androidRelease", "abi", "versionName",
            "buildType", "sherpaVersion", "onnxruntimeVersion", "captureSource", "streamingModel", "offlineModel",
            "decodingMethod", "source", "playbackPath", "pass",
        )
        for (c in classes) {
            for (field in c.java.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                if (field.type == String::class.java) {
                    assertTrue("${c.simpleName}.${field.name} is a string", field.name in stringFields)
                }
                if (List::class.java.isAssignableFrom(field.type)) {
                    val arg = (field.genericType as java.lang.reflect.ParameterizedType).actualTypeArguments.single().typeName
                    assertTrue("${c.simpleName}.${field.name} lists $arg", arg.startsWith("com.folio.hermeslive.lab."))
                }
                assertFalse("${c.simpleName}.${field.name}", field.type.isArray)
            }
        }
    }

    @Test fun schemaAndRoundTrip() {
        val json = Json.parseToJsonElement(sample().toJson()) as JsonObject
        assertEquals("\"${LabReport.SCHEMA}\"", json["schema"].toString())
        assertEquals(ModelCatalog.all.size, (json["models"] as JsonArray).size)
    }
}
