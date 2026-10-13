package com.folio.hermeslive.call

import com.folio.hermes.ApprovalChoice
import com.folio.hermes.ApprovalRequest
import com.folio.hermes.ClarifyRequest
import com.folio.hermes.TurnUpdate
import com.folio.hermes.live.DelegationBackend
import com.folio.hermes.live.LiveHistoryMessage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class FakeBackend : DelegationBackend {
    data class Call(val prompt: String, val context: String, val interruptFirst: Boolean)
    val calls = mutableListOf<Call>()
    val channels = mutableListOf<Channel<TurnUpdate>>()

    override fun delegate(prompt: String, voiceContext: String, interruptFirst: Boolean): Flow<TurnUpdate> {
        calls += Call(prompt, voiceContext, interruptFirst)
        val channel = Channel<TurnUpdate>(Channel.UNLIMITED)
        channels += channel
        return channel.consumeAsFlow()
    }
}

class FakeLink(var stored: String? = "stored-1") : HermesLink {
    override val backend = FakeBackend()
    var prepareError: Exception? = null
    val prepared = mutableListOf<Long>()
    val histories = mutableListOf<List<LiveHistoryMessage>>()
    val approvals = mutableListOf<Pair<String, ApprovalChoice>>()
    val clarifies = mutableListOf<Pair<String, Map<String, String?>>>()
    var closed = 0

    override suspend fun prepare(minValidityMs: Long) {
        prepared += minValidityMs
        prepareError?.let { throw it }
    }

    override suspend fun createVoiceSession(offerSdp: String, history: List<LiveHistoryMessage>): String {
        histories += history
        return "v=0\r\nanswer\r\n"
    }

    override suspend fun answerApproval(request: ApprovalRequest, choice: ApprovalChoice) {
        approvals += request.id to choice
    }

    override suspend fun answerClarify(request: ClarifyRequest, answers: Map<String, String?>) {
        clarifies += request.id to answers
    }

    override val storedSessionId: String? get() = stored

    override fun close() {
        closed++
    }
}

class FakeTransport(val events: TransportEvents) : LiveTransport {
    val sent = mutableListOf<JsonObject>()
    var offers = mutableListOf<String>()
    var micOn = true
    var speakerSilenced = false
    var closed = false
    var open = true

    override suspend fun start(exchange: suspend (String) -> String) {
        val offer = "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\n"
        offers += offer
        exchange(offer)
    }

    override fun send(message: String): Boolean {
        if (!open || closed) return false
        sent += Json.parseToJsonElement(message).jsonObject
        return true
    }

    override fun setMicEnabled(enabled: Boolean) { micOn = enabled }
    override fun setSpeakerMuted(muted: Boolean) { speakerSilenced = muted }
    override fun close() { closed = true }

    fun server(json: String) = events.onMessage(json)
    fun started() = server("""{"type":"session.started","session":{"id":"sess_1"}}""")
    fun user(text: String, end: Long) = server("""{"type":"session.input_transcript.delta","delta":"$text","start_ms":${end - 200},"end_ms":$end}""")
    fun assistant(text: String, end: Long) = server("""{"type":"session.output_transcript.delta","delta":"$text","start_ms":${end - 200},"end_ms":$end}""")
    fun delegation(id: String) = server("""{"type":"session.delegation.created","delegation":{"id":"$id","type":"client","target":"backend"}}""")
    fun closedBy(reason: String, seconds: Double) = server("""{"type":"session.closed","reason":"$reason","usage":{"seconds":$seconds}}""")
    fun typesSent(): List<String> = sent.map { it["type"]!!.jsonPrimitive.content }
    fun contents(type: String): List<String> = sent.filter { it["type"]!!.jsonPrimitive.content == type }.map { it["content"]!!.jsonPrimitive.content }
}

class FakeAudio : CallAudio {
    var listener: CallAudio.Listener? = null
    var began = 0
    var ended = 0
    var reasserted = 0
    val focus = mutableListOf<FocusMode>()
    val selected = mutableListOf<RouteOption>()
    var available = listOf(EARPIECE, SPEAKER)
    var musicActive = true

    override fun begin(listener: CallAudio.Listener) { began++; this.listener = listener }
    override fun end() { ended++ }
    override fun devices() = available
    override fun select(route: RouteOption) { selected += route }
    override fun setFocus(mode: FocusMode) { focus += mode }
    override fun reassert() { reasserted++ }
    override fun isMusicActive() = musicActive

    companion object {
        val EARPIECE = RouteOption(1, RouteKind.Earpiece, "Phone")
        val SPEAKER = RouteOption(2, RouteKind.Speaker, "Speaker")
        val BUDS = RouteOption(7, RouteKind.Bluetooth, "Buds")
        val WIRED = RouteOption(8, RouteKind.Wired, "Wired")
    }
}

class FakeEffects : CallEffects {
    val earcons = mutableListOf<Earcon>()
    val proximity = mutableListOf<Boolean>()
    var haptics = 0
    override fun earcon(kind: Earcon) { earcons += kind }
    override fun haptic(strong: Boolean) { haptics++ }
    override fun proximity(enabled: Boolean) { proximity += enabled }
}

class FakeMemory(var memory: SessionMemory = SessionMemory.Empty) : SessionMemoryStore {
    override fun load() = memory
    override fun save(memory: SessionMemory) { this.memory = memory }
}
