package com.folio.hermes.realtime

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** A function call Grok made: run it, then send its output with [RealtimeProtocol.functionOutput]. */
data class FunctionCall(val callId: String, val name: String, val arguments: String, val responseId: String? = null)

/**
 * What the server sends, as the session needs it. Names verified against docs.x.ai on 2026-10-14;
 * where the docs and the official cookbook use two names, both are accepted (noted per type).
 */
sealed interface ServerEvent {
    data object SessionCreated : ServerEvent
    data object SessionUpdated : ServerEvent

    /** `conversation.created`: [id] is the key for `resumption` (`?conversation_id=`). */
    data class ConversationCreated(val id: String) : ServerEvent

    /** `input_audio_buffer.speech_started`: the user started speaking (barge-in under `server_vad`). */
    data class SpeechStarted(val itemId: String?) : ServerEvent
    data class SpeechStopped(val itemId: String?) : ServerEvent

    /** `…input_audio_transcription.updated` (cumulative, not a delta) or `.completed` ([final]). */
    data class UserTranscript(val itemId: String?, val text: String, val final: Boolean) : ServerEvent

    data class ResponseCreated(val responseId: String?) : ServerEvent

    /** `response.output_audio.delta` (also `response.audio.delta`): PCM s16le at the session's rate. */
    data class AudioDelta(val responseId: String?, val itemId: String?, val pcm: ByteArray) : ServerEvent {
        override fun equals(other: Any?): Boolean = other is AudioDelta && other.responseId == responseId && other.itemId == itemId && other.pcm.contentEquals(pcm)
        override fun hashCode(): Int = (responseId.hashCode() * 31 + itemId.hashCode()) * 31 + pcm.contentHashCode()
    }

    /** `response.output_audio_transcript.delta` / `.done` (also `response.audio_transcript.*`), and text deltas. */
    data class AssistantText(val responseId: String?, val text: String, val done: Boolean) : ServerEvent

    /**
     * `response.function_call_arguments.done`, or `response.output_item.done` with a
     * `function_call` item (the cookbook's trigger). The same call may arrive both ways: dedupe by [FunctionCall.callId].
     */
    data class Call(val call: FunctionCall) : ServerEvent

    /** `response.done`: [status] `completed`, `cancelled` (barge-in) or `incomplete`; [calls] from its output, if listed. */
    data class ResponseDone(val responseId: String?, val status: String, val calls: List<FunctionCall>) : ServerEvent

    /** `error`: [type] (`invalid_request_error`, `invalid_event`, `internal_error`, `timeout`, `max_duration`) and [code]. The message is never kept (it may echo content). */
    data class Error(val type: String?, val code: String?) : ServerEvent

    /** `ping` (cookbook keepalive): answer with [RealtimeProtocol.pong]. */
    data class Ping(val timestamp: Long?) : ServerEvent

    /** Anything else, by type (telemetry only). */
    data class Other(val type: String) : ServerEvent
}

/** The client events and the server-event parser (pure). */
object RealtimeProtocol {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(o: JsonObject): String = o.toString()

    /**
     * `session.update`: instructions, voice, the tools, `server_vad` turns (null [turnDetection]:
     * manual text turns, for the eval), input transcription, `audio/pcm` in and out at
     * [GrokConfig.sampleRate], and `resumption` when [GrokConfig.resumption].
     */
    fun sessionUpdate(config: GrokConfig, instructions: String, tools: JsonArray, turnDetection: Boolean = true): JsonObject = buildJsonObject {
        put("type", "session.update")
        putJsonObject("session") {
            put("instructions", instructions)
            put("voice", config.voice)
            put("tools", tools)
            if (turnDetection) putJsonObject("turn_detection") { put("type", "server_vad") } else put("turn_detection", JsonNull)
            putJsonObject("audio") {
                putJsonObject("input") {
                    putJsonObject("format") {
                        put("type", "audio/pcm")
                        put("rate", config.sampleRate)
                    }
                    putJsonObject("transcription") {
                        config.languageHint?.let { put("language_hint", it) }
                        val terms = config.keyterms.map { it.trim().take(50) }.filter { it.isNotEmpty() }.distinct().take(100)
                        if (terms.isNotEmpty()) putJsonArray("keyterms") { terms.forEach { add(JsonPrimitive(it)) } }
                    }
                }
                putJsonObject("output") {
                    putJsonObject("format") {
                        put("type", "audio/pcm")
                        put("rate", config.sampleRate)
                    }
                }
            }
            if (config.resumption) putJsonObject("resumption") { put("enabled", true) }
        }
    }

    fun appendAudio(pcm: ByteArray): JsonObject = buildJsonObject {
        put("type", "input_audio_buffer.append")
        put("audio", Base64.getEncoder().encodeToString(pcm))
    }

    /** One function call's result; send all of a response's outputs, then one [responseCreate]. */
    fun functionOutput(callId: String, output: String): JsonObject = buildJsonObject {
        put("type", "conversation.item.create")
        putJsonObject("item") {
            put("type", "function_call_output")
            put("call_id", callId)
            put("output", output)
        }
    }

    fun responseCreate(): JsonObject = buildJsonObject { put("type", "response.create") }

    fun responseCancel(): JsonObject = buildJsonObject { put("type", "response.cancel") }

    /** A text message item ([role] `user`, `system` or `assistant`). No response by itself: follow with [responseCreate]. */
    fun message(role: String, text: String): JsonObject = buildJsonObject {
        put("type", "conversation.item.create")
        putJsonObject("item") {
            put("type", "message")
            put("role", role)
            put("content", buildJsonArray { add(buildJsonObject { put("type", "input_text"); put("text", text) }) })
        }
    }

    /** After a barge-in: the model's copy of its audio item ends where the user stopped hearing it. */
    fun truncate(itemId: String, audioEndMs: Long): JsonObject = buildJsonObject {
        put("type", "conversation.item.truncate")
        put("item_id", itemId)
        put("content_index", 0)
        put("audio_end_ms", audioEndMs.coerceAtLeast(0))
    }

    fun pong(timestamp: Long?): JsonObject = buildJsonObject {
        put("type", "pong")
        timestamp?.let { put("ping_timestamp", it) }
    }

    /** One server message; null if it is not a JSON object with a type. */
    fun parse(text: String): ServerEvent? {
        val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val type = o.str("type") ?: return null
        return when (type) {
            "session.created" -> ServerEvent.SessionCreated
            "session.updated" -> ServerEvent.SessionUpdated
            "conversation.created" -> (o.obj("conversation")?.str("id") ?: o.str("conversation_id"))?.let { ServerEvent.ConversationCreated(it) } ?: ServerEvent.Other(type)
            "input_audio_buffer.speech_started" -> ServerEvent.SpeechStarted(o.str("item_id"))
            "input_audio_buffer.speech_stopped" -> ServerEvent.SpeechStopped(o.str("item_id"))
            "conversation.item.input_audio_transcription.updated" -> ServerEvent.UserTranscript(o.str("item_id"), o.str("transcript").orEmpty(), final = false)
            "conversation.item.input_audio_transcription.completed" -> ServerEvent.UserTranscript(o.str("item_id"), o.str("transcript").orEmpty(), final = true)
            "response.created" -> ServerEvent.ResponseCreated(o.obj("response")?.str("id") ?: o.str("response_id"))
            "response.output_audio.delta", "response.audio.delta" -> {
                val bytes = o.str("delta")?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() } ?: return ServerEvent.Other(type)
                ServerEvent.AudioDelta(o.str("response_id"), o.str("item_id"), bytes)
            }
            "response.output_audio_transcript.delta", "response.audio_transcript.delta", "response.output_text.delta", "response.text.delta" ->
                ServerEvent.AssistantText(o.str("response_id"), o.str("delta").orEmpty(), done = false)
            "response.output_audio_transcript.done", "response.audio_transcript.done" ->
                ServerEvent.AssistantText(o.str("response_id"), o.str("transcript").orEmpty(), done = true)
            "response.function_call_arguments.done" -> call(o, o.str("response_id"))?.let { ServerEvent.Call(it) } ?: ServerEvent.Other(type)
            "response.output_item.done" -> o.obj("item")?.takeIf { it.str("type") == "function_call" }?.let { call(it, o.str("response_id")) }?.let { ServerEvent.Call(it) } ?: ServerEvent.Other(type)
            "response.done" -> {
                val r = o.obj("response")
                val id = r?.str("id") ?: o.str("response_id")
                val calls = (r?.get("output") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.takeIf { i -> i.str("type") == "function_call" }?.let { i -> call(i, id) } }
                ServerEvent.ResponseDone(id, r?.str("status") ?: "completed", calls)
            }
            "error" -> {
                val e = o.obj("error")
                ServerEvent.Error(e?.str("type") ?: o.str("error_type"), e?.str("code") ?: o.str("code"))
            }
            "ping" -> ServerEvent.Ping(o["ping_timestamp"]?.jsonPrimitive?.longOrNull)
            else -> ServerEvent.Other(type)
        }
    }

    private fun call(o: JsonObject, responseId: String?): FunctionCall? {
        val id = o.str("call_id") ?: return null
        val name = o.str("name") ?: return null
        return FunctionCall(id, name, o.str("arguments") ?: "{}", responseId)
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
}
