package com.folio.hermeslive.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.folio.hermes.cascade.TaskTap
import com.folio.hermeslive.call.ApprovalCard
import com.folio.hermeslive.call.ClarifyCard
import com.folio.hermeslive.cascade.CascadeAppRuntime
import com.folio.hermeslive.cascade.CascadeCallEngine
import com.folio.hermeslive.data.EngineStatus
import com.folio.hermeslive.data.ModelState
import com.folio.hermeslive.data.PrepPhase
import com.folio.hermeslive.data.PrepState
import com.folio.hermes.ApprovalChoice
import com.folio.hermes.cascade.Event
import com.folio.hermes.cascade.DecisionRecord
import com.folio.hermes.cascade.ResumeChoice
import com.folio.hermeslive.ui.cascade.CascadeCallScreen
import com.folio.hermeslive.ui.cascade.CascadeChrome
import com.folio.hermeslive.ui.cascade.CascadeHistoryItem
import com.folio.hermeslive.ui.cascade.CascadeIntents
import com.folio.hermeslive.ui.cascade.EventIntents
import com.folio.hermeslive.ui.cascade.HapticIntent

/**
 * Bill's call screen on a running cascade (S-D's [CascadeCallScreen] on the actor's state and
 * events), with the approvals slot filled by the tap-only cards (R-TASK-6). [viewer]: no call is
 * running (a result notification's Open): the task cards and their results, and End closes it.
 */
@Composable
fun CascadeCallRoute(
    engine: CascadeCallEngine,
    runtime: CascadeAppRuntime,
    pauseMs: Int,
    viewer: Boolean,
    decisions: () -> List<DecisionRecord>,
    onHaptic: (HapticIntent) -> Unit,
    onClose: () -> Unit,
) {
    val call by engine.state.collectAsState()
    val approvals by runtime.approvals.collectAsState()
    val clarify by runtime.clarify.collectAsState()
    val actor = runtime.core.actor
    val intents = remember(engine, runtime, viewer) { cascadeIntents(engine, runtime, viewer, onHaptic, onClose) }
    val history = remember(runtime, call.phase) {
        runCatching { runtime.core.history() }.getOrDefault(emptyList()).filterNot { it.current }.map {
            CascadeHistoryItem(it.id, it.title, it.endedWallMs ?: it.startedWallMs, it.taskIds.size)
        }
    }
    CascadeCallScreen(
        state = actor.ui,
        events = actor.events,
        intents = intents,
        chrome = CascadeChrome(
            callStartedAtMs = if (viewer) null else call.startedAtMs?.let { started -> System.nanoTime() / 1_000_000 - (System.currentTimeMillis() - started) },
            muted = call.muted,
            speakerOn = call.route?.kind == com.folio.hermeslive.call.RouteKind.Speaker,
        ),
        micLevel = { engine.micLevel },
        pauseMs = pauseMs,
        decisions = decisions,
        history = history,
        approvals = approvalsSlot(approvals, clarify, engine),
    )
}

/** The approvals slot: the first approval (with how many more wait), then a clarify question. Null: none. */
private fun approvalsSlot(approvals: List<ApprovalCard>, clarify: ClarifyCard?, engine: CascadeCallEngine): (@Composable () -> Unit)? {
    if (approvals.isEmpty() && clarify == null) return null
    return {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            approvals.firstOrNull()?.let { ApprovalCardView(it, { id, choice: ApprovalChoice -> engine.answerApproval(id, choice) }, approvals.size - 1) }
            clarify?.let { ClarifyCardView(it, engine::answerClarify) }
        }
    }
}

/** The screen's intents: actor events through [EventIntents]; hold-to-talk through the pipeline; app intents to the engine. */
internal fun cascadeIntents(
    engine: CascadeCallEngine,
    runtime: CascadeAppRuntime,
    viewer: Boolean,
    onHaptic: (HapticIntent) -> Unit,
    onClose: () -> Unit,
): CascadeIntents {
    val app = object : CascadeIntents {
        override fun mute() = engine.toggleMute()
        override fun end() = if (viewer) onClose() else engine.end()
        override fun speaker() = engine.toggleSpeaker()
        override fun openConversation(conversationId: String) = runtime.core.openConversation(conversationId)
        override fun haptic(intent: HapticIntent) = onHaptic(intent)
    }
    val events = EventIntents(runtime.core.actor::send, app)
    return object : CascadeIntents by events {
        // The pipeline opens and closes the held segment and sends the actor its events (R-STT-8).
        override fun holdToTalk(pressed: Boolean) = engine.holdToTalk(pressed)

        // No call: a Play or a Read needs Bill's voice, so only the card actions that send work.
        override fun task(taskId: String, tap: TaskTap) {
            if (viewer && (tap == TaskTap.ReadAll || tap == TaskTap.Play)) return
            events.task(taskId, tap)
        }

        override fun resume(choice: ResumeChoice) = runtime.core.actor.send(Event.ResumeChosen(choice))
    }
}

/**
 * "Preparing Bill…" (spec v2 §14 item 10): Bill is chosen but his speech models are not on the
 * phone. It shows the download, starts it on Wi-Fi by itself, and offers GPT-Live for this call.
 */
@Composable
fun PreparingBill(
    prep: PrepState,
    wifi: Boolean,
    onDownload: () -> Unit,
    onUseGptLive: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().background(VoidBlack).windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp).testTag("preparing-bill"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Preparing Bill…", style = StatusStyle, color = PrintInk, modifier = Modifier.padding(top = 48.dp))
        val left = prep.bytesToFetch
        val running = prep.phase == PrepPhase.Running
        Text(
            when {
                running -> "Downloading his speech models" + if (left > 0) " (${EngineStatus.megabytes(left)} to go)." else "."
                prep.phase == PrepPhase.Failed -> "The download stopped. Try again, or talk to GPT-Live now."
                wifi -> "His speech models download once, on Wi-Fi."
                else -> "His speech models download once" + (if (left > 0) " (about ${EngineStatus.megabytes(left)})" else "") + ". You're not on Wi-Fi."
            },
            style = BodyStyle, color = PrintMute,
        )
        prep.models.filter { it.state != ModelState.Ready }.forEach { m ->
            Text(m.label, style = SmallStyle, color = PrintInk)
            LinearProgressIndicator(progress = { m.fraction }, modifier = Modifier.fillMaxWidth(), color = Ember, trackColor = Hairline)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!running) Pill(if (wifi) "Download" else "Download anyway", filled = true, tag = "prepare-download", onClick = onDownload)
            Pill("Use GPT-Live this time", filled = running, tag = "prepare-gpt-live", onClick = onUseGptLive)
            Pill("Not now", filled = false, tag = "prepare-cancel", onClick = onCancel)
        }
        Text("The call starts by itself once Bill is ready.", style = SmallStyle, color = PrintMute)
    }
}
