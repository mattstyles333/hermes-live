package com.folio.hermes.cascade.registry

import com.folio.hermes.BusyMode
import com.folio.hermes.SubmitStatus
import com.folio.hermes.TurnStatus
import com.folio.hermes.TurnUpdate
import com.folio.hermes.cascade.ports.HermesPort
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flow

/**
 * A fake Hermes server that outlives app processes: sessions keyed by stored session id, each
 * with the prompts it received. It knows nothing about the app's task ids (the real one cannot).
 */
class FakeServer {
    class Session(val id: String) {
        val prompts = mutableListOf<String>()
        var ended: Pair<String, TurnStatus>? = null
        val done = CompletableDeferred<Pair<String, TurnStatus>>()
        val live = mutableListOf<Channel<TurnUpdate>>()
    }

    val sessions = linkedMapOf<String, Session>()

    /** Every prompt, in order. */
    val prompts: List<String> get() = sessions.values.flatMap { it.prompts }

    fun create(): Session = Session("s${sessions.size + 1}").also { sessions[it.id] = it }

    /** The session's turn ends with [text] (pushed to a live client, and to any later probe). */
    fun complete(sessionId: String, text: String, status: TurnStatus = TurnStatus.Complete) {
        val s = sessions.getValue(sessionId)
        s.ended = text to status
        s.done.complete(text to status)
        s.live.forEach {
            it.trySend(TurnUpdate.Completed(text, status, null))
            it.close()
        }
    }

    fun sessionFor(prompt: String): Session = sessions.values.first { prompt in it.prompts }
}

/**
 * One app process's view of [server] (a [HermesPort] plus [SessionRecovery]), like the gateway:
 * [create] makes a task's session with no prompt (session-first, as `session.create`); with
 * [createAhead] false it cannot, and the first submit creates it (the stored id is minted just
 * before the prompt goes out, then reported on [sessions]). A stored id given to [open] is resumed.
 * [probe] reports the prompts the session holds, like `session.resume` with messages.
 */
class ClientHermes(private val server: FakeServer) : HermesPort, SessionRecovery {
    private val bound = linkedMapOf<String, String?>()
    val opened = mutableListOf<Triple<String, String, String?>>()
    val probes = mutableListOf<String>()
    val interrupts = mutableListOf<String>()
    var autoStart = true

    /** The next [failSubmits] submits fail before reaching the server (no session, no prompt). */
    var failSubmits = 0

    /** Session-first (the gateway's behaviour); false models a port that cannot create ahead. */
    var createAhead = true
    val created = mutableListOf<String>()

    override suspend fun create(taskId: String, label: String): String? {
        if (!createAhead) return null
        val session = server.create()
        bound[taskId] = session.id
        created += session.id
        sessions.tryEmit(taskId to session.id)
        return session.id
    }

    override fun open(taskId: String, label: String, storedSessionId: String?) {
        if (taskId in bound) return
        bound[taskId] = storedSessionId
        opened += Triple(taskId, label, storedSessionId)
    }

    override fun submit(taskId: String, text: String, voiceContext: String, queued: Boolean, interruptFirst: Boolean): Flow<TurnUpdate> {
        if (failSubmits > 0) {
            failSubmits--
            return flow { emit(TurnUpdate.Failed("unreachable")) }
        }
        val session = bound[taskId]?.let { server.sessions[it] } ?: server.create().also {
            bound[taskId] = it.id
            sessions.tryEmit(taskId to it.id)
        }
        session.prompts += text
        val channel = Channel<TurnUpdate>(Channel.UNLIMITED)
        session.live += channel
        if (autoStart) {
            channel.trySend(TurnUpdate.Accepted(session.id, if (queued) SubmitStatus.Queued else SubmitStatus.Streaming))
            channel.trySend(TurnUpdate.Started)
        }
        return channel.consumeAsFlow()
    }

    override suspend fun interrupt(taskId: String): Boolean {
        interrupts += taskId
        val session = bound[taskId]?.let { server.sessions[it] } ?: return false
        session.live.forEach {
            it.trySend(TurnUpdate.Completed("", TurnStatus.Interrupted, null))
            it.close()
        }
        return true
    }

    override suspend fun busyMode(): BusyMode? = BusyMode.Interrupt

    override val sessions = MutableSharedFlow<Pair<String, String>>(replay = 64)
    override val connected = MutableStateFlow(true)

    override suspend fun probe(taskId: String, storedSessionId: String): SessionProbe {
        probes += storedSessionId
        val s = server.sessions[storedSessionId] ?: return SessionProbe.Unknown
        if (s.prompts.isEmpty()) return SessionProbe.NeverPrompted
        val prompts = s.prompts.toList()
        s.ended?.let { return SessionProbe.Ended(it.first, it.second, prompts) }
        return SessionProbe.Running(flow { val (text, status) = s.done.await(); emit(TurnUpdate.Completed(text, status, null)) }, prompts)
    }
}
