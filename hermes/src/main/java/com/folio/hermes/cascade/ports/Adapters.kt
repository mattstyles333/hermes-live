package com.folio.hermes.cascade.ports

import com.folio.hermes.BusyMode
import com.folio.hermes.HermesConfig
import com.folio.hermes.HermesConversation
import com.folio.hermes.HermesGateway
import com.folio.hermes.PromptSurface
import com.folio.hermes.SessionOptions
import com.folio.hermes.TurnUpdate
import com.folio.hermes.GatewayConnection
import com.folio.hermes.cascade.AudioClip
import com.folio.hermes.cascade.BillVoice
import com.folio.hermes.openrouter.JevAnswer
import com.folio.hermes.openrouter.JevQuestion
import com.folio.hermes.openrouter.OpenRouter
import com.folio.hermes.openrouter.OpenRouterResult
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject

/** In-memory [Store] for tests and for running without persistence. Thread-safe. */
class MemoryStore : Store {
    private val map = ConcurrentHashMap<String, String>()
    private val logs = ConcurrentHashMap<String, MutableList<String>>()

    override fun get(key: String): String? = map[key]

    override fun put(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }

    override fun keys(prefix: String): List<String> = map.keys.filter { it.startsWith(prefix) }.sorted()

    override fun append(log: String, line: String) {
        val l = logs.getOrPut(log) { mutableListOf() }
        synchronized(l) { l += line }
    }

    override fun read(log: String): List<String> = logs[log]?.let { synchronized(it) { it.toList() } }.orEmpty()

    override fun trim(log: String, keep: Int) {
        val l = logs[log] ?: return
        synchronized(l) { while (l.size > keep.coerceAtLeast(0)) l.removeAt(0) }
    }
}

/**
 * [HermesPort] on the shared gateway: one [HermesConversation] per task, titled "Voice: <label>"
 * (as C1). The conversations are connection resources held by this adapter, not call state.
 */
class GatewayHermesPort(
    private val gateway: HermesGateway,
    private val config: HermesConfig,
    private val options: (label: String) -> SessionOptions = { label -> SessionOptions(title = "Voice: $label") },
) : HermesPort {
    private val conversations = ConcurrentHashMap<String, HermesConversation>()
    private val minted = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override val sessions: Flow<Pair<String, String>> = minted.asSharedFlow()

    override val connected: Flow<Boolean> = gateway.connection.map { it is GatewayConnection.Ready }.distinctUntilChanged()

    override fun open(taskId: String, label: String, storedSessionId: String?) {
        conversations.computeIfAbsent(taskId) {
            HermesConversation(gateway, config, options(label), storedSessionId) { stored -> minted.tryEmit(taskId to stored) }
        }
    }

    /** A fresh conversation for [taskId] (never the stored id it had), created now; its stored id. */
    override suspend fun create(taskId: String, label: String): String? {
        val conversation = HermesConversation(gateway, config, options(label), null) { stored -> minted.tryEmit(taskId to stored) }
        conversations[taskId] = conversation
        conversation.ensureSession()
        return conversation.storedSessionId
    }

    override fun submit(taskId: String, text: String, voiceContext: String, queued: Boolean, interruptFirst: Boolean): Flow<TurnUpdate> {
        val conversation = conversations[taskId] ?: return emptyFlow()
        return conversation.turn(text, PromptSurface.VoiceLive, voiceContext, interruptFirst = interruptFirst, queued = queued)
    }

    override suspend fun interrupt(taskId: String): Boolean = conversations[taskId]?.interrupt() ?: false

    override suspend fun busyMode(): BusyMode? = gateway.busyMode()
}

/**
 * [OpenRouterPort] on the C1 [OpenRouter] clients, with Bill's voice from [voice] (TTS cache first).
 * A whole writer answer ([write]: agent briefs, offline segmentation, summaries) may use up to
 * [writeMaxTokens]; the spoken writer stream keeps the short default.
 */
class LiveOpenRouter(
    private val openRouter: OpenRouter,
    private val voice: BillVoice,
    private val writeMaxTokens: Int = WRITE_MAX_TOKENS,
) : OpenRouterPort {
    override suspend fun jev(state: JsonObject, questions: Map<String, JevQuestion>, timeoutMs: Long): OpenRouterResult<Map<String, JevAnswer>> =
        openRouter.jev.decide(state, questions, timeoutMs)

    override fun sentences(system: String, prompt: String): Flow<String> = openRouter.writer.sentences(system, prompt)

    override suspend fun write(system: String, prompt: String): OpenRouterResult<String> = openRouter.writer.write(system, prompt, writeMaxTokens)

    override suspend fun clip(text: String): OpenRouterResult<AudioClip> = voice.clip(text)

    override fun isCached(text: String): Boolean = voice.isCached(text)

    override suspend fun warm() {
        openRouter.warm()
    }

    companion object {
        /**
         * Spec v2 §14 item 17a. 200 tokens cut multi-brief JSON short. Worst cases (sized in
         * `WriterBudgetTest`): 4 briefs of `Goal/Constraints/Done when` lines with label, summary,
         * fixes and notes stay near 1,500 tokens; offline segmentation of a 24,000-character
         * chunk of short sentences answers about 3,600 tokens of group numbers. A cap, not a charge.
         */
        const val WRITE_MAX_TOKENS = 4_096
    }
}
