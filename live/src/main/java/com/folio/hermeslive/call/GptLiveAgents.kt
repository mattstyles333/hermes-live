package com.folio.hermeslive.call

import com.folio.hermes.ApprovalChoice
import com.folio.hermes.live.AgentIntents
import com.folio.hermes.live.DelegationTasks
import com.folio.hermes.live.JevAgentIntents
import com.folio.hermes.live.TaskLabeler
import com.folio.hermes.live.WriterLabeler
import com.folio.hermes.openrouter.OpenRouterResult
import com.folio.hermeslive.cascade.CascadeAppRuntime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What a GPT-Live call needs for parallel agents (gptlive-parallel slice): the shared task API on
 * Bill's process runtime, Jev to classify a delegation (status / cancel / update / new), the
 * writer to label it, the runtime's tap-only approval and clarify cards, and a hold on its gateway
 * for the call. Null from [GptLiveAgentsFactory]: agents are off and the call is today's single
 * Hermes conversation.
 */
interface GptLiveAgentsHost {
    val tasks: DelegationTasks
    val intents: AgentIntents
    val labeler: TaskLabeler

    /** Approval cards from the task sessions (never answered without a tap). */
    val approvals: StateFlow<List<ApprovalCard>>
    val clarify: StateFlow<ClarifyCard?>

    suspend fun answerApproval(id: String, choice: ApprovalChoice): Boolean
    suspend fun answerClarify(id: String, answers: Map<String, String?>): Boolean

    /** Keep the gateway open for the call (the stored tasks read back, nothing of Bill spoken). */
    fun hold(): AutoCloseable

    /** Bill's runtime when there is one (the call screen draws its task tray). */
    val runtime: CascadeAppRuntime?

    companion object {
        /** On Bill's process runtime (the engine is GPT-Live: no Bill call, nothing of Bill spoken). */
        fun of(rt: CascadeAppRuntime): GptLiveAgentsHost = object : GptLiveAgentsHost {
            override val tasks = DelegationTasks.of(rt.core)
            override val intents = JevAgentIntents({ state, questions, timeoutMs -> rt.openRouter.jev(state, questions, timeoutMs) })
            override val labeler = WriterLabeler({ system, prompt -> (rt.openRouter.write(system, prompt) as? OpenRouterResult.Ok)?.value })
            override val approvals = rt.approvals
            override val clarify = rt.clarify
            override suspend fun answerApproval(id: String, choice: ApprovalChoice) = rt.answerApproval(id, choice)
            override suspend fun answerClarify(id: String, answers: Map<String, String?>) = rt.answerClarify(id, answers)
            override fun hold(): AutoCloseable = rt.holdGateway()
            override val runtime: CascadeAppRuntime = rt
        }

        /** No cards (tests, fakes). */
        val NoCards: StateFlow<List<ApprovalCard>> = MutableStateFlow(emptyList())
        val NoClarify: StateFlow<ClarifyCard?> = MutableStateFlow(null)
    }
}

/** Builds a call's [GptLiveAgentsHost] when agents are on (Settings › GPT-Live, signed in); else null. */
fun interface GptLiveAgentsFactory {
    fun create(): GptLiveAgentsHost?

    companion object {
        val Off = GptLiveAgentsFactory { null }

        /**
         * The app's rule: agents only while switched on ([enabled]: engine GPT-Live and the
         * switch) **and** signed in; only then is Bill's process [runtime] asked for (it is built
         * on first use). Signed out or switched off: nothing is built, the call is today's.
         */
        fun gated(enabled: () -> Boolean, signedIn: () -> Boolean, runtime: () -> CascadeAppRuntime?): GptLiveAgentsFactory =
            GptLiveAgentsFactory { if (!enabled() || !signedIn()) null else runtime()?.let(GptLiveAgentsHost::of) }
    }
}
