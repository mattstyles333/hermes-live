package com.folio.hermes

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** One Json for the library: tolerant of new server fields, compile-time serializers only. */
internal val HermesJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

internal fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

internal fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

/** A JSON value as display text: strings as-is, anything else as compact JSON, null as "". */
internal fun JsonElement?.asText(): String = when (this) {
    null, JsonNull -> ""
    is JsonPrimitive -> if (isString) content else content
    else -> toString()
}
