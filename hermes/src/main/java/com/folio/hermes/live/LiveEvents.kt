package com.folio.hermes.live

import com.folio.hermes.HermesJson
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Events the live model sends on the `oai-events` data channel that the client acts on. */
sealed interface LiveServerEvent {
    /** The session is live. Never send `session.start`; wait for this. */
    data class Started(val sessionId: String?) : LiveServerEvent

    /** A transcript delta (user or assistant). */
    data class Transcript(val fragment: LiveTranscriptFragment) : LiveServerEvent

    /** The model asked the backend (Hermes) for help. No text: rebuild it from the transcript. */
    data class DelegationCreated(val delegationId: String) : LiveServerEvent

    /** Billed seconds so far. */
    data class UsageUpdated(val seconds: Double) : LiveServerEvent

    /** A vendor error. [ignorable] for late appends after close (`context_injection_incomplete`). */
    data class Error(val code: String?, val message: String, val ignorable: Boolean) : LiveServerEvent

    /** The session ended. */
    data class Closed(val reason: String, val usageSeconds: Double?) : LiveServerEvent

    /** Anything else (kept for logging). */
    data class Other(val type: String) : LiveServerEvent

    companion object {
        /** Parse one data-channel message; null when it is not a JSON event. */
        fun parse(raw: String): LiveServerEvent? {
            val wire = runCatching { HermesJson.decodeFromString<LiveWire>(raw) }.getOrNull() ?: return null
            return when (wire.type) {
                "session.started" -> Started(wire.session?.id)
                "session.input_transcript.delta", "session.output_transcript.delta" -> Transcript(
                    LiveTranscriptFragment(
                        speaker = if (wire.type == "session.input_transcript.delta") Speaker.User else Speaker.Assistant,
                        text = wire.delta.orEmpty(),
                        startMs = wire.startMs?.toLong() ?: 0,
                        endMs = wire.endMs?.toLong() ?: 0,
                    ),
                )
                "session.delegation.created" -> wire.delegation?.id?.let(::DelegationCreated) ?: Other(wire.type)
                "session.usage.updated" -> UsageUpdated(wire.usage?.seconds ?: 0.0)
                "error" -> {
                    val code = wire.error?.code
                    Error(code, wire.error?.message ?: "GPT-Live error", ignorable = code == "context_injection_incomplete")
                }
                "session.closed" -> Closed(wire.reason ?: "closed", wire.usage?.seconds)
                else -> Other(wire.type)
            }
        }
    }
}

@Serializable
internal data class LiveWire(
    val type: String,
    val delta: String? = null,
    @SerialName("start_ms") val startMs: Double? = null,
    @SerialName("end_ms") val endMs: Double? = null,
    val delegation: LiveWireDelegation? = null,
    val error: LiveWireError? = null,
    val usage: LiveWireUsage? = null,
    val reason: String? = null,
    val session: LiveWireSession? = null,
)

@Serializable internal data class LiveWireDelegation(val id: String? = null, val type: String? = null, val target: String? = null)
@Serializable internal data class LiveWireError(val type: String? = null, val code: String? = null, val message: String? = null)
@Serializable internal data class LiveWireUsage(val seconds: Double? = null)
@Serializable internal data class LiveWireSession(val id: String? = null)

/**
 * Builds the client events for the data channel as JSON strings. Event ids are `<prefix>_<n>`
 * like the desktop client (`say_1`, `think_2`, …).
 */
class LiveClientEvents {
    private val counter = AtomicInteger(0)
    private val whitespace = Regex("\\s+")

    private fun nextId(prefix: String) = "${prefix}_${counter.incrementAndGet()}"

    /** Results the voice should say (paraphrased): one event per chunk of ≤1400 chars. */
    fun commentary(delegationId: String?, content: String): List<String> =
        Commentary.chunk(content).map { append("session.commentary.append", "say", delegationId, it) }

    /** Quiet progress for the voice ("Hermes is working: …"). Null when [content] is blank. */
    fun thinking(delegationId: String?, content: String): String? =
        content.replace(whitespace, " ").trim().take(Commentary.APPEND_CHAR_LIMIT).ifEmpty { null }
            ?.let { append("session.thinking.append", "think", delegationId, it) }

    /** Steer the live persona for the rest of the session. Null when [content] is blank. */
    fun instructions(content: String): String? =
        content.trim().take(Commentary.APPEND_CHAR_LIMIT).ifEmpty { null }
            ?.let { append("session.instructions.append", "instr", null, it) }

    /** `session.input_audio.mute` / `unmute` (the app also disables the mic track). */
    fun mute(muted: Boolean): String = buildJsonObject {
        put("event_id", nextId(if (muted) "mute" else "unmute"))
        put("type", if (muted) "session.input_audio.mute" else "session.input_audio.unmute")
    }.toString()

    /** Ask the vendor to end the session; it answers `session.closed`. */
    fun close(): String = buildJsonObject { put("type", "session.close") }.toString()

    private fun append(type: String, prefix: String, delegationId: String?, content: String): String = buildJsonObject {
        put("content", content)
        put("delegation_id", delegationId?.let(::JsonPrimitive) ?: JsonNull)
        put("event_id", nextId(prefix))
        put("type", type)
    }.toString()
}
