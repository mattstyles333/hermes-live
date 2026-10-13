package com.folio.hermes.telemetry

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

/** OpenTelemetry severities used here (number per the OTel log data model). */
@Serializable
enum class Severity(val number: Int) {
    Info(9),
    Warn(13),
    Error(17),
    Fatal(21),
    ;

    val text: String get() = name.uppercase()
}

/** One log record, as queued in memory and as written to `pending/`. Attributes are already scrubbed. */
@Serializable
data class TelemetryRecord(
    val timeMs: Long,
    val severity: Severity,
    val body: String,
    val attributes: Map<String, JsonPrimitive> = emptyMap(),
)

/** What a pending file holds: one crash, or a batch of exits, with the events queued just before. */
@Serializable
data class PendingBatch(val records: List<TelemetryRecord>)

/** Builds [TelemetryRecord]s: safe keys, scrubbed values, exceptions per OTel semantic conventions. */
object Records {
    const val MAX_ATTRIBUTES = 32
    const val STACK_MAX = 16 * 1024
    const val NONFATAL_STACK_MAX = 8 * 1024
    private const val MAX_FRAMES = 64
    private const val MAX_CAUSES = 8
    private val keyChars = Regex("[^A-Za-z0-9._-]")

    fun record(
        timeMs: Long,
        severity: Severity,
        name: String,
        attrs: Map<String, Any?> = emptyMap(),
        throwable: Throwable? = null,
        stackMax: Int = NONFATAL_STACK_MAX,
    ): TelemetryRecord {
        val body = Scrub.message(name, 120).ifEmpty { "event" }
        val out = LinkedHashMap<String, JsonPrimitive>()
        out["event.name"] = JsonPrimitive(body)
        for ((k, v) in attrs) {
            if (out.size >= MAX_ATTRIBUTES) break
            val key = key(k) ?: continue
            value(v)?.let { out[key] = it }
        }
        if (throwable != null) out.putAll(exception(throwable, stackMax))
        return TelemetryRecord(timeMs, severity, body, out)
    }

    /** `exception.type`, `exception.message` (scrubbed, ≤500) and `exception.stacktrace` (≤[stackMax]). */
    fun exception(t: Throwable, stackMax: Int): Map<String, JsonPrimitive> = buildMap {
        put("exception.type", JsonPrimitive(t.javaClass.name))
        Scrub.message(t.message).takeIf { it.isNotEmpty() }?.let { put("exception.message", JsonPrimitive(it)) }
        put("exception.stacktrace", JsonPrimitive(stackTrace(t, stackMax)))
    }

    /** Like printStackTrace, with every message in the cause chain scrubbed on its own. */
    fun stackTrace(t: Throwable, max: Int = STACK_MAX): String {
        val sb = StringBuilder()
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        var current: Throwable? = t
        var prefix = ""
        var depth = 0
        while (current != null && seen.add(current) && depth < MAX_CAUSES && sb.length < max) {
            sb.append(prefix).append(current.javaClass.name)
            Scrub.message(current.message, 300).takeIf { it.isNotEmpty() }?.let { sb.append(": ").append(it) }
            sb.append('\n')
            val frames = current.stackTrace
            frames.take(MAX_FRAMES).forEach { sb.append("\tat ").append(it).append('\n') }
            if (frames.size > MAX_FRAMES) sb.append("\t... ").append(frames.size - MAX_FRAMES).append(" more\n")
            current = current.cause
            prefix = "Caused by: "
            depth++
        }
        return Scrub.truncate(sb.toString(), max)
    }

    private fun key(raw: String): String? = keyChars.replace(raw.trim(), "_").take(64).ifEmpty { null }

    /** Numbers and booleans stay typed (so SigNoz can aggregate them); anything else is scrubbed text. */
    fun value(v: Any?): JsonPrimitive? = when (v) {
        null -> null
        is Boolean -> JsonPrimitive(v)
        is Int -> JsonPrimitive(v.toLong())
        is Long -> JsonPrimitive(v)
        is Short -> JsonPrimitive(v.toLong())
        is Byte -> JsonPrimitive(v.toLong())
        is Double -> if (v.isFinite()) JsonPrimitive(v) else null
        is Float -> if (v.isFinite()) JsonPrimitive(v.toDouble()) else null
        is Enum<*> -> JsonPrimitive(v.name)
        else -> JsonPrimitive(Scrub.message(v.toString(), Scrub.ATTRIBUTE_MAX))
    }
}
