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
import com.folio.hermeslive.call.CallPhase
import com.folio.hermeslive.call.CallUiState
import com.folio.hermeslive.ui.cascade.CallEnd
import com.folio.hermeslive.ui.cascade.CascadeCallScreen
import com.folio.hermeslive.ui.cascade.EndReason
import com.folio.hermeslive.ui.cascade.CascadeChrome
import com.folio.hermeslive.ui.cascade.CascadeHistoryItem
import com.folio.hermeslive.ui.cascade.CascadeIntents
import com.folio.hermeslive.ui.cascade.EventIntents
import com.folio.hermeslive.ui.cascade.HapticIntent

/** Which main screen the activity shows (below settings, sign-in and "Preparing Bill…"). */
enum class MainScreen {
    /** Bill's call is running. */
    Bill,

    /** Bill's call just ended: the end transition, until it closes (or a new call starts). */
    BillEnded,

    /** Bill's screen with no call (a result notification's Open). */
    BillViewer,

    /** The idle home screen (and GPT-Live's call screen). */
    Home,
}

/**
 * The main screen. [outro]: Bill's call screen was up when the call ended and its end transition
 * has not closed yet, so the screen stays instead of cutting to home.
 */
fun mainScreen(billActive: Boolean, inCall: Boolean, hasSession: Boolean, outro: Boolean, viewing: Boolean): MainScreen = when {
    billActive && hasSession && inCall -> MainScreen.Bill
    billActive && hasSession && outro && !inCall -> MainScreen.BillEnded
    viewing && !inCall -> MainScreen.BillViewer
    else -> MainScreen.Home
}

/** How Bill's call ended, from the engine's state once it is over (null while it runs). */
fun callEndOf(call: CallUiState): CallEnd? {
    if (call.inCall || (call.phase != CallPhase.Ended && call.phase != CallPhase.Failed)) return null
    // The engine names no other reason: a goodbye, the notification, the tile and a headset all end it the same way.
    val reason = if (call.error != null) EndReason.Error else EndReason.HungUp
    return CallEnd(reason, call.summary?.durationMs, call.error)
}

/**
 * Bill's call screen on a running cascade (S-D's [CascadeCallScreen] on the actor's state and
 * events), with the approvals slot filled by the tap-only cards (R-TASK-6). [viewer]: no call is
 * running (a result notification's Open): the task cards and their results, and End closes it.
 * [ending]: the call has ended and the screen plays its end transition; [onCloseEnded] leaves for
 * home and [onCallAgain] starts a new call.
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
    ending: Boolean = false,
    onCloseEnded: () -> Unit = onClose,
    onCallAgain: () -> Unit = {},
) {
    val call by engine.state.collectAsState()
    val approvals by runtime.approvals.collectAsState()
    val clarify by runtime.clarify.collectAsState()
    val actor = runtime.core.actor
    val intents = remember(engine, runtime, viewer) { cascadeIntents(engine, runtime, viewer, onHaptic, onClose, onCloseEnded, onCallAgain) }
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
        approvals = approvalsSlot(approvals, clarify, engine::answerApproval, engine::answerClarify),
        ended = if (ending) callEndOf(call) else null,
    )
}

/** The approvals slot: the first approval (with how many more wait), then a clarify question. Null: none. */
internal fun approvalsSlot(
    approvals: List<ApprovalCard>,
    clarify: ClarifyCard?,
    onApprove: (String, ApprovalChoice) -> Unit,
    onClarify: (String, Map<String, String?>) -> Unit,
): (@Composable () -> Unit)? {
    if (approvals.isEmpty() && clarify == null) return null
    return {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            approvals.firstOrNull()?.let { ApprovalCardView(it, { id, choice: ApprovalChoice -> onApprove(id, choice) }, approvals.size - 1) }
            clarify?.let { ClarifyCardView(it, onClarify) }
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
    onCloseEnded: () -> Unit = onClose,
    onCallAgain: () -> Unit = {},
): CascadeIntents {
    val app = object : CascadeIntents {
        override fun mute() = engine.toggleMute()
        override fun end() = if (viewer) onClose() else engine.end()
        override fun speaker() = engine.toggleSpeaker()
        override fun closeEnded() = onCloseEnded()
        override fun callAgain() = onCallAgain()
        override fun openConversation(conversationId: String) = runtime.core.openConversation(conversationId)
        override fun haptic(intent: HapticIntent) = onHaptic(intent)
    }
    val events = EventIntents(runtime.core.actor::send, app)
    return object : CascadeIntents by events {
        // The pipeline opens and closes the held segment and sends the actor its events (R-STT-8).
        override fun holdToTalk(pressed: Boolean) = engine.holdToTalk(pressed)

        // No call: the actor is restored but not started, and drops card taps. The viewer is
        // read-only (the cards and their results); approvals still go out (the slot answers them).
        override fun task(taskId: String, tap: TaskTap) {
            if (!viewer) events.task(taskId, tap)
        }

        override fun resume(choice: ResumeChoice) {
            if (!viewer) runtime.core.actor.send(Event.ResumeChosen(choice))
        }
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
        Text("Preparing the voice…", style = StatusStyle, color = PrintInk, modifier = Modifier.padding(top = 48.dp))
        val left = prep.bytesToFetch
        val running = prep.phase == PrepPhase.Running
        Text(
            when {
                running -> "Downloading the speech models" + if (left > 0) " (${EngineStatus.megabytes(left)} to go)." else "."
                prep.phase == PrepPhase.Failed -> "The download stopped. Try again, or talk to GPT-Live now."
                wifi -> "The speech models download once, on Wi-Fi."
                else -> "The speech models download once" + (if (left > 0) " (about ${EngineStatus.megabytes(left)})" else "") + ". You're not on Wi-Fi."
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
        Text("The call starts by itself once it is ready.", style = SmallStyle, color = PrintMute)
    }
}
