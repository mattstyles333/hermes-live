package com.folio.hermeslive.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.folio.hermes.cascade.CascadeTasks
import com.folio.hermes.cascade.TaskTap
import com.folio.hermes.cascade.UpdateMode
import com.folio.hermes.realtime.GrokUi
import com.folio.hermeslive.call.RouteKind
import com.folio.hermeslive.cascade.CascadeAppRuntime
import com.folio.hermeslive.grok.GrokCallEngine
import com.folio.hermeslive.grok.GrokScreen
import com.folio.hermeslive.ui.cascade.CascadeCallScreen
import com.folio.hermeslive.ui.cascade.CascadeChrome
import com.folio.hermeslive.ui.cascade.CascadeIntents
import com.folio.hermeslive.ui.cascade.HapticIntent
import com.folio.hermeslive.ui.ondemand.OnDemandIndicator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * A Grok call on Bill's call screen (grok-voice slice): the same scene, orb (from Grok's output
 * level), task tray, sheets and end transition, on [GrokScreen.state]; the strip shows Grok's tool
 * calls instead of Jev's answers. Card taps go through the shared task API (the actor is not in a
 * Bill call, so it would drop them). Offline, a card offers Bill's offline memo mode.
 */
@Composable
fun GrokCallRoute(
    engine: GrokCallEngine,
    runtime: CascadeAppRuntime,
    onHaptic: (HapticIntent) -> Unit,
    ending: Boolean,
    onCloseEnded: () -> Unit,
    onCallAgain: () -> Unit,
    onUseBill: () -> Unit,
) {
    val call by engine.state.collectAsState()
    val approvals by runtime.approvals.collectAsState()
    val clarify by runtime.clarify.collectAsState()
    val actor = runtime.core.actor
    val scope = rememberCoroutineScope()
    val grokFlow = remember(engine, call.phase) { engine.grokUi ?: MutableStateFlow(GrokUi()) }
    val grok by grokFlow.collectAsState()
    val tasks by actor.ui.collectAsState()
    val session by engine.sessionUi.collectAsState()
    val state = remember(engine, runtime, grokFlow) {
        combine(actor.ui, grokFlow, engine.state) { t, g, c -> GrokScreen.state(t, g, c.level) }
            .stateIn(scope, SharingStarted.Eagerly, GrokScreen.state(actor.ui.value, grokFlow.value, 0f))
    }
    val intents = remember(engine, runtime) { grokIntents(engine, runtime.core.tasks, scope, onHaptic, onCloseEnded, onCallAgain) }
    Box(Modifier.fillMaxSize()) {
        CascadeCallScreen(
            state = state,
            events = actor.events,
            intents = intents,
            chrome = CascadeChrome(
                callStartedAtMs = call.startedAtMs?.let { started -> System.nanoTime() / 1_000_000 - (System.currentTimeMillis() - started) },
                muted = call.muted,
                speakerOn = call.route?.kind == RouteKind.Speaker,
                engine = "GROK VOICE",
                tools = GrokScreen.chips(grok.chips, tasks.tasks),
                stripLabel = "GROK",
                dimmed = session.dimmed && !ending,
            ),
            micLevel = { engine.micLevel },
            approvals = approvalsSlot(approvals, clarify, engine::answerApproval, engine::answerClarify),
            ended = if (ending) callEndOf(call) else null,
        )
        if (!ending && call.inCall) {
            OnDemandIndicator(session, Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 96.dp))
        }
        val notice = call.notice
        val warning = call.warning
        if (!ending && call.inCall && (warning != null || call.offerBillMemo || (notice != null && call.phase != com.folio.hermeslive.call.CallPhase.Live))) {
            Column(
                Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 132.dp, start = 18.dp, end = 18.dp)
                    .fillMaxWidth().background(Hairline, RoundedCornerShape(14.dp)).padding(14.dp).testTag("grok-offline"),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // The idle hang-up or call cap is near (the earcon has played).
                warning?.let { Text(it, style = BodyStyle, color = PrintInk, modifier = Modifier.testTag("grok-warning")) }
                notice?.takeIf { call.offerBillMemo || call.phase != com.folio.hermeslive.call.CallPhase.Live }?.let { Text(it, style = BodyStyle, color = PrintInk) }
                if (call.offerBillMemo) {
                    Text("The Custom engine can take notes offline and send them when you're back.", style = SmallStyle, color = PrintMute)
                    Pill("Continue offline (memo)", filled = true, tag = "grok-use-bill", onClick = onUseBill)
                }
            }
        }
    }
}

/** The screen's intents for a Grok call: card taps through the task API, the rest to the engine. */
internal fun grokIntents(
    engine: GrokCallEngine,
    tasks: CascadeTasks,
    scope: CoroutineScope,
    onHaptic: (HapticIntent) -> Unit,
    onCloseEnded: () -> Unit,
    onCallAgain: () -> Unit,
): CascadeIntents = object : CascadeIntents {
    override fun task(taskId: String, tap: TaskTap) {
        scope.launch {
            when (tap) {
                TaskTap.Cancel -> tasks.cancelTask(taskId)
                is TaskTap.Tell -> tasks.updateTask(taskId, tap.text, tap.mode)
                TaskTap.ReadAll, TaskTap.Play -> tasks.heard(taskId)
                TaskTap.Retry -> tasks.status(taskId).firstOrNull()?.let { tasks.updateTask(taskId, "Please try this again.", UpdateMode.Queue) }
            }
        }
    }

    override fun mute() = engine.toggleMute()
    override fun end() = engine.end()
    override fun speaker() = engine.toggleSpeaker()
    override fun closeEnded() = onCloseEnded()
    override fun callAgain() = onCallAgain()
    override fun haptic(intent: HapticIntent) = onHaptic(intent)
}
