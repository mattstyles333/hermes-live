package com.folio.hermes.telemetry

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * OTLP/HTTP JSON for logs (`POST <endpoint>/v1/logs`, `Content-Type: application/json`).
 * Field names are lowerCamelCase; 64-bit integers (`timeUnixNano`, `intValue`) are decimal
 * strings, as the OTLP JSON mapping requires. Built with JsonObject builders: no reflection.
 */
object Otlp {
    const val SCOPE = "com.folio.hermes.telemetry"
    const val SCOPE_VERSION = "1"

    fun payload(resource: Map<String, String>, records: List<TelemetryRecord>, observedMs: Long): JsonObject = buildJsonObject {
        putJsonArray("resourceLogs") {
            addJsonObject {
                putJsonObject("resource") {
                    put("attributes", attributes(resource.mapValues { JsonPrimitive(it.value) }))
                }
                putJsonArray("scopeLogs") {
                    addJsonObject {
                        putJsonObject("scope") {
                            put("name", SCOPE)
                            put("version", SCOPE_VERSION)
                        }
                        putJsonArray("logRecords") {
                            records.forEach { add(logRecord(it, observedMs)) }
                        }
                    }
                }
            }
        }
    }

    fun logRecord(record: TelemetryRecord, observedMs: Long): JsonObject = buildJsonObject {
        put("timeUnixNano", nanos(record.timeMs))
        put("observedTimeUnixNano", nanos(observedMs))
        put("severityNumber", record.severity.number)
        put("severityText", record.severity.text)
        putJsonObject("body") { put("stringValue", record.body) }
        put("attributes", attributes(record.attributes))
    }

    private fun attributes(values: Map<String, JsonPrimitive>): JsonArray = buildJsonArray {
        values.forEach { (key, value) ->
            addJsonObject {
                put("key", key)
                put("value", anyValue(value))
            }
        }
    }

    private fun anyValue(v: JsonPrimitive): JsonElement = buildJsonObject {
        when {
            v.isString -> put("stringValue", v.content)
            v.booleanOrNull != null -> put("boolValue", v.booleanOrNull)
            v.longOrNull != null -> put("intValue", v.longOrNull.toString())
            v.doubleOrNull != null -> put("doubleValue", v.doubleOrNull)
            else -> put("stringValue", v.content)
        }
    }

    private fun nanos(ms: Long): String = (ms * 1_000_000L).toString()
}
