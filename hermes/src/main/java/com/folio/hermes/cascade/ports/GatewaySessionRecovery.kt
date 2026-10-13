package com.folio.hermes.cascade.ports

import com.folio.hermes.GatewayEvent
import com.folio.hermes.HermesConfig
import com.folio.hermes.HermesConversation
import com.folio.hermes.HermesGateway
import com.folio.hermes.HermesRpcException
import com.folio.hermes.ResumedSession
import com.folio.hermes.TurnStatus
import com.folio.hermes.TurnUpdate
import com.folio.hermes.cascade.registry.SessionProbe
import com.folio.hermes.cascade.registry.SessionRecovery
import com.folio.hermes.isUnknownSession
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [SessionRecovery] on the gateway (R-DUR-3): `session.resume` **with** messages, read only. It
 * never submits, never creates a session and never changes server config.
 *
 * What the deployed server says (`tui_gateway/methods_session.py`, `methods_prompt.py`, read
 * 2026-10-13), and what it means here:
 * - `prompt.submit` stores the session row and the user message before the turn starts
 *   (`_persist_session_row_for_submit`), and `session.create` stores nothing. So a stored id the
 *   server answers 4007 ("session not found") for was **never prompted**. The one exception is a
 *   session the user deleted, which is taken as never prompted too (documented assumption).
 * - `messages[]` are `{role, text}` rows; `inflight.user` and `queued.user` (live sessions only)
 *   are user text not stored as a message yet. Together they are [SessionProbe.prompts], which
 *   recovery matches against a run's text to tell whether that prompt arrived.
 * - `running: true`: a turn is still going. [SessionProbe.Running] streams it from the gateway's
 *   events until `message.complete`; it subscribes first and resumes again, so an end that
 *   lands in between is read from the transcript instead of being missed.
 * - Not running: the last assistant row after the last user row is the result
 *   ([SessionProbe.Ended], `Complete`); none means the turn ended without a reply (`Error`).
 */
class GatewaySessionRecovery(
    private val gateway: HermesGateway,
    private val config: HermesConfig,
    private val profile: String? = null,
) : SessionRecovery {
    override suspend fun probe(taskId: String, storedSessionId: String): SessionProbe {
        val resumed = try {
            gateway.resumeSession(storedSessionId, profile, withMessages = true)
        } catch (e: HermesRpcException) {
            return if (e.isUnknownSession) SessionProbe.NeverPrompted else SessionProbe.Unknown
        }
        return classify(resumed) { follow(storedSessionId) }
    }

    /** The turn still running in [storedSessionId], to its end. */
    private fun follow(storedSessionId: String): Flow<TurnUpdate> = channelFlow {
        val inbox = Channel<GatewayEvent>(Channel.UNLIMITED)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { gateway.events.collect { inbox.send(it) } }
        try {
            val again = gateway.resumeSession(storedSessionId, profile, withMessages = true)
            if (!again.running) {
                send(ended(again))
                return@channelFlow
            }
            val sid = again.sessionId
            val streamed = StringBuilder()
            while (true) {
                val event = withTimeoutOrNull(config.turnInactivityTimeoutMs) { inbox.receive() }
                    ?: run {
                        send(TurnUpdate.Failed("Hermes stopped answering."))
                        return@channelFlow
                    }
                if (event.sessionId != sid) continue
                when (event) {
                    is GatewayEvent.MessageStart -> send(TurnUpdate.Started)
                    is GatewayEvent.MessageDelta -> {
                        streamed.append(event.text)
                        send(TurnUpdate.Text(event.text, streamed.toString()))
                    }
                    is GatewayEvent.MessageInterim -> send(TurnUpdate.Interim(event.text))
                    is GatewayEvent.ToolStart -> send(TurnUpdate.ToolStarted(event.toolId, event.name, HermesConversation.toolLabel(event.name)))
                    is GatewayEvent.ToolComplete -> send(TurnUpdate.ToolCompleted(event.toolId, event.name, event.summary))
                    is GatewayEvent.ApprovalRequested -> send(TurnUpdate.ApprovalNeeded(event.request))
                    is GatewayEvent.ClarifyRequested -> send(TurnUpdate.ClarifyNeeded(event.request))
                    is GatewayEvent.RequestCancelled -> send(TurnUpdate.RequestWithdrawn(event.id, event.reason))
                    is GatewayEvent.Error -> send(TurnUpdate.Warning(event.message))
                    is GatewayEvent.MessageComplete -> {
                        send(TurnUpdate.Completed(event.text.ifEmpty { streamed.toString() }, event.status, event.error))
                        return@channelFlow
                    }
                    else -> Unit
                }
            }
        } finally {
            collector.cancel()
            inbox.close()
        }
    }

    companion object {
        /** What a `session.resume` reply says about the session's last turn ([running] follows it). */
        fun classify(resumed: ResumedSession, running: () -> Flow<TurnUpdate>): SessionProbe {
            val stored = resumed.messages.filter { it.role == USER }.map { it.text }
            val prompts = stored + resumed.pendingUserText
            return when {
                resumed.running -> SessionProbe.Running(running(), prompts)
                stored.isEmpty() && resumed.pendingUserText.isEmpty() -> SessionProbe.NeverPrompted
                stored.isEmpty() -> SessionProbe.Unknown
                else -> (ended(resumed) as TurnUpdate.Completed).let { SessionProbe.Ended(it.text, it.status, prompts) }
            }
        }

        /** The last assistant reply after the last user row, as a completion. */
        private fun ended(resumed: ResumedSession): TurnUpdate {
            val lastUser = resumed.messages.indexOfLast { it.role == USER }
            val reply = resumed.messages.drop(lastUser + 1).lastOrNull { it.role == ASSISTANT && it.text.isNotBlank() }
            return if (reply != null) TurnUpdate.Completed(reply.text, TurnStatus.Complete, null) else TurnUpdate.Completed("", TurnStatus.Error, null)
        }

        private const val USER = "user"
        private const val ASSISTANT = "assistant"
    }
}
