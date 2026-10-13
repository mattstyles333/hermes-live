package com.folio.hermeslive.ui

import com.folio.hermes.ApprovalChoice
import com.folio.hermes.ApprovalRequest
import com.folio.hermes.ClarifyQuestion
import com.folio.hermes.ClarifyRequest
import com.folio.hermes.live.Speaker
import com.folio.hermeslive.call.ApprovalCard
import com.folio.hermeslive.call.CallActivity
import com.folio.hermeslive.call.CallPhase
import com.folio.hermeslive.call.CallSummary
import com.folio.hermeslive.call.CallUiState
import com.folio.hermeslive.call.Caption
import com.folio.hermeslive.call.ClarifyCard
import com.folio.hermeslive.call.RouteKind
import com.folio.hermeslive.call.RouteOption
import kotlinx.serialization.json.JsonObject

/** Made-up call states for UI tests and screenshots. No real data. */
object DemoStates {
    val speaker = RouteOption(2, RouteKind.Speaker, "")
    val routes = listOf(RouteOption(1, RouteKind.Earpiece, ""), speaker, RouteOption(7, RouteKind.Bluetooth, "Buds"))
    private val chat = listOf(
        Caption(Speaker.User, "What's on my list for tomorrow?"),
        Caption(Speaker.Assistant, "Let me ask Hermes to check your tasks."),
        Caption(Speaker.User, "And the weather in Leeds."),
    )
    private val live = CallUiState(phase = CallPhase.Live, route = speaker, routes = routes, startedAtMs = 0, captions = chat)

    val idle = CallUiState()
    val connecting = CallUiState(phase = CallPhase.Connecting)
    val listening = live.copy(activity = CallActivity.Listening)
    val userSpeaking = live.copy(activity = CallActivity.UserSpeaking, level = 0.6f)
    val thinking = live.copy(activity = CallActivity.Thinking, tool = "web search")
    val music = live.copy(activity = CallActivity.Thinking, tool = "web search", musicInGap = true)
    val speaking = live.copy(
        activity = CallActivity.HermesSpeaking, level = 0.7f,
        captions = chat + Caption(Speaker.Assistant, "Tomorrow you have two tasks: renew the domain and call the dentist. Leeds will be dry, 16 degrees."),
    )
    val muted = live.copy(muted = true)
    val approval = live.copy(
        activity = CallActivity.Thinking,
        approvals = listOf(
            ApprovalCard(
                ApprovalRequest(
                    "srq-1", "rt", "apr-1", "rm -rf ./build/cache", "Delete the build cache",
                    listOf(ApprovalChoice.Once, ApprovalChoice.Session, ApprovalChoice.Deny), "terminal", JsonObject(emptyMap()),
                ),
            ),
        ),
    )
    val clarify = live.copy(
        activity = CallActivity.Thinking,
        clarify = ClarifyCard(
            ClarifyRequest("srq-2", "rt", listOf(ClarifyQuestion("q1", "Which night suits you?", listOf("Friday", "Saturday"), false)), JsonObject(emptyMap())),
        ),
    )
    val warning = live.copy(warning = "It has gone quiet. Hanging up in 30 seconds unless you speak.")
    val ended = CallUiState(phase = CallPhase.Ended, summary = CallSummary(252_000, 238.0, "Call ended."), captions = chat)
    val failed = CallUiState(phase = CallPhase.Failed, error = "Voice is not set up on this Hermes server (no OpenAI key).")
}
