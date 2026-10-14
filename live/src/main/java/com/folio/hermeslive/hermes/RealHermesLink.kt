package com.folio.hermeslive.hermes

import com.folio.hermes.ApprovalChoice
import com.folio.hermes.ApprovalRequest
import com.folio.hermes.ClarifyRequest
import com.folio.hermes.GatewayConnection
import com.folio.hermes.HermesAuth
import com.folio.hermes.HermesAuthException
import com.folio.hermes.HermesConfig
import com.folio.hermes.HermesConversation
import com.folio.hermes.HermesGateway
import com.folio.hermes.SessionOptions
import com.folio.hermes.TurnUpdate
import com.folio.hermes.live.DelegationBackend
import com.folio.hermes.live.LiveHistoryMessage
import com.folio.hermes.live.VoiceLiveApi
import com.folio.hermeslive.call.HermesLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.OkHttpClient

/**
 * The real [HermesLink]: [HermesGateway] + [HermesConversation] (voice turns use
 * `surface="voice-live"`) + [VoiceLiveApi], all on the user's own server.
 */
class RealHermesLink(
    config: HermesConfig,
    private val auth: HermesAuth,
    client: OkHttpClient,
    scope: CoroutineScope,
    storedSessionId: String?,
) : HermesLink {
    private val gateway = HermesGateway(config, auth, client, scope)
    private val conversation = HermesConversation(
        gateway = gateway,
        config = config,
        options = SessionOptions(title = "Hermes Live"),
        storedSessionId = storedSessionId,
    )
    private val voice = VoiceLiveApi(config, auth, client)

    override val backend: DelegationBackend = conversation

    override val storedSessionId: String? get() = conversation.storedSessionId

    override suspend fun prepare(minValidityMs: Long) {
        auth.session(minValidityMs)
        gateway.connect()
    }

    override suspend fun createVoiceSession(offerSdp: String, history: List<LiveHistoryMessage>): String =
        voice.createSession(offerSdp, history).sdpAnswer

    override suspend fun answerApproval(request: ApprovalRequest, choice: ApprovalChoice) =
        gateway.answerApproval(request, choice)

    override suspend fun answerClarify(request: ClarifyRequest, answers: Map<String, String?>) =
        gateway.answerClarify(request, answers)

    override fun close() = gateway.disconnect()

    override val reachable: Boolean
        get() = when (gateway.connection.value) {
            is GatewayConnection.Waiting, is GatewayConnection.Stopped -> false
            GatewayConnection.Idle, is GatewayConnection.Connecting, is GatewayConnection.Ready -> true
        }
}

/** The link used when signed out: fails before any request is made. */
object SignedOutLink : HermesLink {
    override val backend: DelegationBackend = object : DelegationBackend {
        override fun delegate(prompt: String, voiceContext: String, interruptFirst: Boolean): Flow<TurnUpdate> =
            flow { emit(TurnUpdate.Failed("Sign in to Hermes.")) }
    }
    override val storedSessionId: String? = null
    override suspend fun prepare(minValidityMs: Long) = throw HermesAuthException.SignInRequired()
    override suspend fun createVoiceSession(offerSdp: String, history: List<LiveHistoryMessage>): String =
        throw HermesAuthException.SignInRequired()
    override suspend fun answerApproval(request: ApprovalRequest, choice: ApprovalChoice) = Unit
    override suspend fun answerClarify(request: ClarifyRequest, answers: Map<String, String?>) = Unit
    override fun close() = Unit

    override val reachable: Boolean get() = false
}
