package com.folio.hermeslive.call

import com.folio.hermes.ApprovalChoice
import kotlinx.coroutines.flow.StateFlow

/**
 * One voice engine as the service, notification, tile, headset buttons, activity and provider
 * see it. [CallEngine] is GPT-Live (today's call, unchanged); `CascadeCallEngine` is Bill.
 * [CallSwitch] picks one per call.
 */
interface VoiceCall {
    /** Everything the UI, notification, tile and provider show. */
    val state: StateFlow<CallUiState>

    fun start(request: CallRequest = CallRequest())
    fun end()
    fun toggleMute()
    fun selectRoute(option: RouteOption)
    fun submitText(text: String)
    fun answerApproval(id: String, choice: ApprovalChoice)
    fun answerClarify(id: String, answers: Map<String, String?>)
    fun dismissSummary()
}
