package com.folio.hermeslive.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.folio.hermes.cascade.CascadeTasks
import com.folio.hermes.cascade.TaskTap
import com.folio.hermeslive.call.CallEngine
import com.folio.hermeslive.call.GptLiveScreen
import com.folio.hermeslive.call.RouteKind
import com.folio.hermeslive.cascade.CascadeAppRuntime
import com.folio.hermeslive.ui.cascade.CascadeCallScreen
import com.folio.hermeslive.ui.cascade.CascadeChrome
import com.folio.hermeslive.ui.cascade.CascadeIntents
import com.folio.hermeslive.ui.cascade.HapticIntent
import com.folio.hermeslive.ui.ondemand.OnDemandIndicator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * A GPT-Live call with parallel agents on Bill's call screen (gptlive-parallel slice), as Grok's
 * is: the scene, the orb (from GPT-Live's levels), the task tray with its cards, sheets and swipe
 * to cancel, and the end transition, on [GptLiveScreen.state]. Card taps go through the shared
 * task API; approvals and clarify are the task sessions' tap-only cards.
 */
@Composable
fun GptLiveCallRoute(
    engine: CallEngine,
    runtime: CascadeAppRuntime,
    onHaptic: (HapticIntent) -> Unit,
    ending: Boolean,
    onCloseEnded: () -> Unit,
    onCallAgain: () -> Unit,
) {
    val call by engine.state.collectAsState()
    val approvals by runtime.approvals.collectAsState()
    val clarify by runtime.clarify.collectAsState()
    val actor = runtime.core.actor
    val scope = rememberCoroutineScope()
    val state = remember(engine, runtime) {
        combine(actor.ui, engine.state) { t, c -> GptLiveScreen.state(t, c) }
            .stateIn(scope, SharingStarted.Eagerly, GptLiveScreen.state(actor.ui.value, engine.state.value))
    }
    val intents = remember(engine, runtime) { gptLiveIntents(engine, runtime.core.tasks, scope, onHaptic, onCloseEnded, onCallAgain) }
    val session by engine.onDemandUi.collectAsState()
    Box(Modifier.fillMaxSize()) {
        CascadeCallScreen(
            state = state,
            events = actor.events,
            intents = intents,
            chrome = CascadeChrome(
                callStartedAtMs = call.startedAtMs?.let { started -> System.nanoTime() / 1_000_000 - (System.currentTimeMillis() - started) },
                muted = call.muted,
                speakerOn = call.route?.kind == RouteKind.Speaker,
                engine = ENGINE_LABEL,
                tools = emptyList(),
                stripLabel = "AGENTS",
                // The paid session is closed to save cost: the scene dims, as in a Grok call.
                dimmed = session.dimmed && !ending,
            ),
            micLevel = { if (call.activity == com.folio.hermeslive.call.CallActivity.UserSpeaking) call.level else 0f },
            approvals = approvalsSlot(approvals, clarify, engine::answerApproval, engine::answerClarify),
            ended = if (ending) callEndOf(call) else null,
        )
        if (call.inCall && !ending) {
            OnDemandIndicator(session, Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 96.dp))
        }
        // The idle hang-up or call cap is near (GPT-Live also says it).
        val warning = call.warning
        if (call.inCall && !ending && warning != null) {
            Text(
                warning, style = BodyStyle, color = PrintInk,
                modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 132.dp, start = 18.dp, end = 18.dp)
                    .fillMaxWidth().background(Hairline, RoundedCornerShape(14.dp)).padding(14.dp).testTag("gptlive-warning"),
            )
        }
    }
}

/** The chrome's engine name. The hub's naming slice may rename user-facing strings. */
internal const val ENGINE_LABEL = "GPT-LIVE · AGENTS"

/** The screen's intents for a GPT-Live agents call: card taps through the task API, the rest to the engine. */
internal fun gptLiveIntents(
    engine: CallEngine,
    tasks: CascadeTasks,
    scope: CoroutineScope,
    onHaptic: (HapticIntent) -> Unit,
    onCloseEnded: () -> Unit,
    onCallAgain: () -> Unit,
): CascadeIntents = object : CascadeIntents {
    override fun task(taskId: String, tap: TaskTap) {
        scope.launch { tasks.apiTap(taskId, tap) }
    }

    override fun mute() = engine.toggleMute()
    override fun end() = engine.end()
    override fun speaker() = engine.toggleSpeaker()
    override fun closeEnded() = onCloseEnded()
    override fun callAgain() = onCallAgain()
    override fun haptic(intent: HapticIntent) = onHaptic(intent)
}
