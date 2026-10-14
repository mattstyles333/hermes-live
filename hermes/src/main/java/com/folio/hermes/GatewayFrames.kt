package com.folio.hermes

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** One decoded gateway frame. The wire is one JSON-RPC object per WebSocket message. */
internal sealed interface GatewayFrame {
    /** `{"method":"event","params":{type, session_id, payload}}` (no id). */
    data class Event(val type: String, val sessionId: String?, val payload: JsonObject?) : GatewayFrame

    /** A server→client request: `{"id":"srq-…","method":…,"params":…}`. */
    data class ServerRequest(val id: JsonElement, val method: String, val params: JsonObject) : GatewayFrame

    /** A response to one of our calls. Our ids are integers. */
    data class Response(val id: Long, val result: JsonElement?, val error: JsonObject?) : GatewayFrame

    data object Unknown : GatewayFrame
}

internal object GatewayFrames {
    /** Methods only a Hermes Desktop window can answer: decline with this code so the agent is told at once. */
    const val NOT_SHOWN_CODE = 4404
    const val METHOD_NOT_FOUND = -32601
    val WINDOW_OWNED = setOf("preview.read", "preview.act", "terminal.read", "window.read", "tour")

    fun classify(obj: JsonObject): GatewayFrame {
        val method = obj.string("method")
        val id = obj["id"]?.takeUnless { it is JsonNull }
        if (method == "event" && id == null) {
            val params = obj.obj("params") ?: return GatewayFrame.Unknown
            val type = params.string("type") ?: return GatewayFrame.Unknown
            return GatewayFrame.Event(type, params.string("session_id"), params.obj("payload"))
        }
        if (method != null && id != null) {
            return GatewayFrame.ServerRequest(id, method, obj.obj("params") ?: JsonObject(emptyMap()))
        }
        if (method == null && id != null && ("result" in obj || "error" in obj)) {
            val numeric = (id as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: return GatewayFrame.Unknown
            return GatewayFrame.Response(numeric, obj["result"], obj.obj("error"))
        }
        return GatewayFrame.Unknown
    }

    fun request(id: Long, method: String, params: JsonObject): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("method", method)
        put("params", params)
    }.toString()

    fun result(id: JsonElement, result: JsonObject): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }.toString()

    fun error(id: JsonElement, code: Int, message: String): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("error", buildJsonObject {
            put("code", code)
            put("message", message)
        })
    }.toString()

    fun event(type: String, sessionId: String?, payload: JsonObject?): GatewayEvent {
        val p = payload ?: JsonObject(emptyMap())
        return when (type) {
            "message.start" -> GatewayEvent.MessageStart(sessionId)
            "message.delta" -> GatewayEvent.MessageDelta(sessionId, p.string("text").orEmpty())
            "message.interim" -> GatewayEvent.MessageInterim(sessionId, p.string("text").orEmpty(), p.bool("already_streamed") == true)
            "message.complete" -> GatewayEvent.MessageComplete(
                sessionId = sessionId,
                text = p["text"].asText(),
                status = when (p.string("status")) {
                    "error" -> TurnStatus.Error
                    "interrupted" -> TurnStatus.Interrupted
                    else -> TurnStatus.Complete
                },
                error = p.string("error"),
                responseTransformed = p.bool("response_transformed") == true,
            )
            "tool.start" -> GatewayEvent.ToolStart(sessionId, p.string("tool_id").orEmpty(), p.string("name").orEmpty(), p.string("context"))
            "tool.complete" -> GatewayEvent.ToolComplete(sessionId, p.string("tool_id").orEmpty(), p.string("name").orEmpty(), p.string("summary"))
            "error" -> GatewayEvent.Error(sessionId, p.string("message").orEmpty())
            "status.update" -> GatewayEvent.Status(sessionId, p.string("kind").orEmpty(), p.string("text").orEmpty())
            "request.cancel" -> GatewayEvent.RequestCancelled(
                sessionId, p.string("id").orEmpty(), p.string("method").orEmpty(), p.string("reason").orEmpty(),
            )
            else -> GatewayEvent.Other(sessionId, type, payload)
        }
    }

    fun approval(id: JsonElement, params: JsonObject): ApprovalRequest = ApprovalRequest(
        id = id.idText(),
        sessionId = params.string("session_id").orEmpty(),
        requestId = params.string("request_id").orEmpty(),
        command = params.string("command").orEmpty(),
        description = params.string("description").orEmpty(),
        choices = (params["choices"] as? JsonArray).orEmpty().mapNotNull { choice ->
            val wire = (choice as? JsonPrimitive)?.contentOrNull
            ApprovalChoice.entries.firstOrNull { it.wire == wire }
        }.ifEmpty { listOf(ApprovalChoice.Once, ApprovalChoice.Deny) },
        toolName = params.string("tool_name"),
        params = params,
    )

    fun clarify(id: JsonElement, params: JsonObject): ClarifyRequest = ClarifyRequest(
        id = id.idText(),
        sessionId = params.string("session_id").orEmpty(),
        questions = (params["questions"] as? JsonArray).orEmpty().mapNotNull { q ->
            val o = q as? JsonObject ?: return@mapNotNull null
            ClarifyQuestion(
                qid = o.string("qid") ?: return@mapNotNull null,
                question = o.string("question").orEmpty(),
                choices = (o["choices"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                multiSelect = o.bool("multi_select") == true,
            )
        },
        params = params,
    )

    fun submitStatus(result: JsonObject): SubmitStatus = when {
        result.bool("voice_stopped") == true -> SubmitStatus.VoiceStopped
        else -> when (result.string("status")) {
            "streaming" -> SubmitStatus.Streaming
            "queued" -> SubmitStatus.Queued
            "redirected" -> SubmitStatus.Redirected
            "steered" -> SubmitStatus.Steered
            else -> SubmitStatus.Unknown
        }
    }

    /**
     * `config.get {key: "busy"}` → the busy mode. The reply shape was not verified against the
     * server, so this accepts a bare string, `{value}`, `{busy}`, `{mode}` or
     * `{display: {busy_input_mode}}`; anything else is null.
     */
    fun busyMode(result: JsonElement?): BusyMode? {
        val raw = when (result) {
            is JsonPrimitive -> result.takeIf { it.isString }?.content
            is JsonObject -> result.string("value") ?: result.string("busy") ?: result.string("mode")
                ?: result.string("busy_input_mode") ?: result.obj("display")?.string("busy_input_mode")
            else -> null
        }
        return BusyMode.entries.firstOrNull { it.wire == raw?.trim()?.lowercase() }
    }

    private fun JsonElement.idText(): String = (this as? JsonPrimitive)?.content ?: toString()
}
