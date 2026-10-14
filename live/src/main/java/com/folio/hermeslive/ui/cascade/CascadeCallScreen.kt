package com.folio.hermeslive.ui.cascade

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.derivedStateOf
import androidx.activity.compose.BackHandler
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.folio.hermes.cascade.CascadeEvent
import com.folio.hermes.cascade.CascadeUiState
import com.folio.hermes.cascade.DecisionRecord
import com.folio.hermes.cascade.TaskTap
import com.folio.hermeslive.ui.VoidBlack
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** How long the orb must be held before hold-to-talk starts. */
const val HOLD_MS = 300L

/** The default screen clock: the same as `Clock.System.nowMs()` in `:hermes` (task times use it). */
val MonotonicMs: () -> Long = { System.nanoTime() / 1_000_000 }

/**
 * The cascade call screen (v1 "Call screen visuals", spec v2 S-D). Pure: [state] and [events]
 * in, [intents] out. It never vibrates, plays or sends by itself.
 *
 * @param state the actor's `CascadeUiState` stream (the truth; every visual settles from it).
 * @param events the actor's one-shot `CascadeEvent`s (decoration: sparks, stagger, fly-outs, haptics).
 * @param intents user intents; see [EventIntents] for the mapping onto actor events.
 * @param chrome app-level facts the state lacks: call start, mute, speaker, engine label.
 * @param micLevel the mic level 0..1 for the listening well (read only while drawing).
 * @param proximityNear the proximity sensor is near (at the ear): nothing is drawn and no frames run.
 * @param motion the animator scale and reduce motion ([rememberCascadeMotion]).
 * @param pauseMs the pause setting, for the early-call tick's position on the pause ring.
 * @param decisions the decision log's records, newest first (read when the Jev sheet opens; first 20 shown).
 * @param history recent conversations for the list under the Resume card.
 * @param clock the clock task times are on (`Clock.nowMs`).
 * @param wallClock wall time, for "12 min ago".
 * @param approvals approval and clarify cards (tap only, R-TASK-6), drawn above the tray. Their
 *   content is not in `CascadeUiState`; the engine supplies them (null: none).
 * @param ended how the call ended, once it has (null while it runs): the screen winds down
 *   ([EndRun], [EndTimeline]) and stays until [CascadeIntents.closeEnded] or
 *   [CascadeIntents.callAgain].
 */
@Composable
fun CascadeCallScreen(
    state: StateFlow<CascadeUiState>,
    events: Flow<CascadeEvent>,
    intents: CascadeIntents,
    modifier: Modifier = Modifier,
    chrome: CascadeChrome = CascadeChrome(),
    micLevel: () -> Float = { 0f },
    proximityNear: Boolean = false,
    motion: CascadeMotion = rememberCascadeMotion(),
    pauseMs: Int = 1000,
    decisions: () -> List<DecisionRecord> = { emptyList() },
    history: List<CascadeHistoryItem> = emptyList(),
    clock: () -> Long = MonotonicMs,
    wallClock: () -> Long = System::currentTimeMillis,
    approvals: (@Composable () -> Unit)? = null,
    ended: CallEnd? = null,
) {
    val ui = state.collectAsState()
    CascadeCallContent(
        ui, events, intents, modifier, chrome, micLevel, proximityNear, motion, pauseMs, decisions, history, clock, wallClock,
        approvals = approvals, ended = ended,
    )
}

/**
 * [CascadeCallScreen] on a [State]; [scene] and [memory] injectable for renders, [animate] false
 * for still renders. With [animate] false, [endAtMs] is the moment of the end transition to draw.
 */
@Composable
internal fun CascadeCallContent(
    ui: State<CascadeUiState>,
    events: Flow<CascadeEvent>,
    intents: CascadeIntents,
    modifier: Modifier = Modifier,
    chrome: CascadeChrome = CascadeChrome(),
    micLevel: () -> Float = { 0f },
    proximityNear: Boolean = false,
    motion: CascadeMotion = CascadeMotion(),
    pauseMs: Int = 1000,
    decisions: () -> List<DecisionRecord> = { emptyList() },
    history: List<CascadeHistoryItem> = emptyList(),
    clock: () -> Long = MonotonicMs,
    wallClock: () -> Long = System::currentTimeMillis,
    approvals: (@Composable () -> Unit)? = null,
    scene: SceneModel = remember { SceneModel() },
    memory: ScreenMemory = remember { ScreenMemory() },
    animate: Boolean = true,
    ended: CallEnd? = null,
    endAtMs: Long = 0L,
) {
    Probe("root")
    val current by rememberUpdatedState(intents)
    val wake = remember { Channel<Unit>(Channel.CONFLATED) }
    val sections = remember(ui) { Sections(ui) }
    val now = remember { mutableLongStateOf(clock()) }
    val wall = remember { mutableLongStateOf(wallClock()) }
    val jevAnchor = remember { floatArrayOf(-1f, -1f) }
    val scope = rememberCoroutineScope()
    val cancelJobs = remember { HashMap<String, Job>() }

    LaunchedEffect(events) {
        events.collect { e ->
            scene.onEvent(e)
            memory.onEvent(e)
            when (e) {
                is CascadeEvent.TurnCommitted -> current.haptic(HapticIntent.TurnCommitted)
                is CascadeEvent.TaskFinished -> current.haptic(HapticIntent.TaskFinished)
                else -> Unit
            }
            wake.trySend(Unit)
        }
    }
    LaunchedEffect(ui) {
        snapshotFlow { ui.value }.collect {
            memory.observe(it)
            wake.trySend(Unit)
        }
    }
    if (animate) {
        LaunchedEffect(clock, wallClock) { tick(now, wall, clock, wallClock) }
    }
    DisposableEffect(Unit) {
        onDispose {
            // Leaving during the Undo window sends the cancel (the user swiped it away); a held orb is released.
            for (id in memory.pendingCancels.keys.toList()) current.task(id, TaskTap.Cancel)
            memory.pendingCancels.clear()
            if (memory.holding) {
                memory.holding = false
                current.holdToTalk(false)
            }
        }
    }

    val swipeCancel: (String, String) -> Unit = remember {
        { id, label ->
            memory.pendingCancels[id] = label
            if (memory.sheetTaskId == id) memory.sheetTaskId = null
            cancelJobs.remove(id)?.cancel()
            cancelJobs[id] = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                delay(UNDO_MS)
                cancelJobs.remove(id)
                if (memory.pendingCancels.remove(id) != null) current.task(id, TaskTap.Cancel)
            }
        }
    }
    val trayActions = remember {
        TrayActions(
            open = { memory.sheetTaskId = it },
            retry = { current.task(it, TaskTap.Retry) },
            play = { current.task(it, TaskTap.Play) },
            swipeCancel = swipeCancel,
            undo = { id ->
                memory.pendingCancels.remove(id)
                cancelJobs.remove(id)?.cancel()
            },
        )
    }
    val sheetActions = remember {
        SheetActions(close = { memory.sheetTaskId = null }, cancel = swipeCancel, tap = { id, tap -> current.task(id, tap) })
    }
    val onWord: (Int, String) -> Unit = remember { { index, choice -> memory.lastTurnId?.let { current.wordChosen(it, index, choice) } } }
    // The end (EndCall.kt): the call's start is remembered while it runs (the chrome drops it at the end).
    chrome.callStartedAtMs?.let { started -> SideEffect { memory.callStartMs = started } }
    val endRun = remember(ended) {
        ended?.let { EndRun.start(it, memory.endTapped, ui.value.tasks, memory.callStartMs ?: chrome.callStartedAtMs, motion.reduceMotion) }
    }
    // A new call after this end (Talk again, Reconnect) starts untapped and without the old call's start.
    DisposableEffect(endRun) {
        onDispose {
            if (endRun != null) {
                memory.endTapped = false
                memory.callStartMs = null
            }
        }
    }
    val endMs = remember(endRun) { mutableLongStateOf(if (animate) 0L else endAtMs) }
    val endActions = remember(endRun) {
        // Each at most once per end, on its own: Close and Back still work after a Talk again that did not take.
        fun once(block: () -> Unit): () -> Unit {
            var sent = false
            return { if (!sent) { sent = true; block() } }
        }
        EndActions(close = once { current.closeEnded() }, again = once { current.callAgain() })
    }
    if (endRun != null) {
        scene.ending = true
        if (animate) EndClock(endRun, endMs, motion, endActions.close)
        BackHandler { endActions.close() }
    } else {
        scene.ending = false
    }
    val live: () -> Float = remember(endRun) { { if (endRun == null) 1f else 1f - EndTimeline.wind(endMs.longValue, endRun.reduced) } }
    val input = remember(pauseMs, chrome.muted, micLevel, endRun) { SceneInput(pauseMs, chrome.muted, jevAnchor, micLevel, endRun) { endMs.longValue } }
    val fade = Modifier.graphicsLayer { alpha = live() }
    // The caption and the Jev strip are gone by half-way, before the end's own text comes in.
    val early = Modifier.graphicsLayer { alpha = (2f * live() - 1f).coerceIn(0f, 1f) }

    Box(
        modifier.fillMaxSize().background(VoidBlack).testTag("cascade-screen")
            .semantics { if (endRun != null) paneTitle = endRun.announcement },
    ) {
        CascadeScene(ui, scene, motion, proximityNear, input, wake, animate && !proximityNear)
        if (proximityNear) return@Box
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val orbY = maxHeight * ORB_Y
            if (endRun == null) HoldTarget(Modifier.align(Alignment.TopCenter).offset(y = orbY - 90.dp).size(180.dp), memory, current, sections.holding)
            Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))) {
                TopBar(chrome, now, sections.top, endedAfterMs = endRun?.summary?.durationMs, live = live)
                Column(early) {
                    val strip = chrome.tools?.let { tools -> remember(tools) { mutableStateOf(JevModel(tools, inFlight = tools.any { it.choice == "…" }, latencyMs = null)) } } ?: sections.jev
                    JevStrip(strip, memory.chipEpoch, motion, label = chrome.stripLabel, onAnchor = { jevAnchor[0] = it.x; jevAnchor[1] = it.y }) {
                        if (chrome.tools == null) memory.decisionsOpen = true
                    }
                    ConnectionBanner(sections.banner)
                    Flyouts(memory, motion)
                }
            }
            Box(Modifier.align(Alignment.TopCenter).offset(y = orbY + 148.dp).then(early)) { CaptionView(sections.caption, memory, onWord, motion) }
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                    .padding(horizontal = 14.dp),
            ) {
                approvals?.invoke()
                if (endRun == null) ResumeCard(sections.resume, history, wall, current)
                // The tray stays (tasks run on); after a call with nothing left running it collapses with the orb.
                val trayFade = if (endRun?.outcome == EndOutcome.Summary) Modifier.graphicsLayer { alpha = 1f - EndTimeline.card(endMs.longValue, endRun.reduced) } else Modifier
                TaskTray(sections.tasks, now, memory, trayActions, motion, Modifier.padding(top = 8.dp).then(trayFade))
                EndAwareControls(chrome, current, memory, endRun, endMs, endActions, live)
            }
            if (endRun != null) EndStatus(endRun, endMs, sections.tasks, endActions)
        }
        TaskSheetHost(memory, sections.tasks, now, sheetActions)
        if (memory.decisionsOpen) {
            val list = remember { decisions().take(20) }
            SheetFrame("decisions", onClose = { memory.decisionsOpen = false }) { DecisionsContent(list) { memory.decisionsOpen = false } }
        }
    }
}

/**
 * The controls while the call runs; at the end the End button stays pressed and the rest fade.
 * After a call that left tasks running (once settled), "Talk again" and "Close" take their place.
 */
@Composable
private fun EndAwareControls(
    chrome: CascadeChrome,
    intents: CascadeIntents,
    memory: ScreenMemory,
    run: EndRun?,
    ms: State<Long>,
    actions: EndActions,
    live: () -> Float,
) {
    val settled by remember(run) { derivedStateOf { run != null && run.phaseAt(ms.value) == EndPhase.Settled } }
    if (run?.outcome == EndOutcome.StillWorking && settled) {
        EndButtons(actions) { 1f }
        return
    }
    if (run?.outcome == EndOutcome.Dropped && settled) {
        // The dropped card has the buttons; the tray keeps its place.
        Spacer(Modifier.height(CONTROLS_HEIGHT))
        return
    }
    Controls(
        chrome, intents,
        onEnd = {
            // The press shows at once; the engine's own end haptic ticks in the same call.
            memory.endTapped = true
            intents.end()
        },
        ending = run != null,
        press = { if (run != null) EndTimeline.press(ms.value) else if (memory.endTapped) 1f else 0f },
        live = live,
    )
}

@Composable
private fun BoxScope.TaskSheetHost(memory: ScreenMemory, tasks: State<TaskCards>, now: State<Long>, actions: SheetActions) {
    val id = memory.sheetTaskId ?: return
    val card = tasks.value.list.firstOrNull { it.id == id }
    if (card == null) {
        LaunchedEffect(id) { memory.sheetTaskId = null }
        return
    }
    SheetFrame("task-sheet", onClose = actions.close) { TaskSheetContent(card, now.value, actions) }
}

/** Holding the orb talks (R-STT-8). Release always sends `false`, also on cancel and dispose. */
@Composable
private fun HoldTarget(modifier: Modifier, memory: ScreenMemory, intents: CascadeIntents, holding: State<Boolean>) {
    val current by rememberUpdatedState(intents)
    fun start() {
        if (memory.holding) return
        memory.holding = true
        current.holdToTalk(true)
    }
    fun stop() {
        if (!memory.holding) return
        memory.holding = false
        current.holdToTalk(false)
    }
    val holdingNow = memory.holding
    Box(
        modifier
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var ended = false
                    withTimeoutOrNull(HOLD_MS) {
                        waitForUpOrCancellation()
                        ended = true
                    }
                    if (!ended) {
                        start()
                        try {
                            waitForUpOrCancellation()
                        } finally {
                            stop()
                        }
                    }
                }
            }
            .semantics {
                contentDescription = if (holding.value || holdingNow) "Bill, listening while you hold" else "Bill. Hold to talk"
                customActions = listOf(
                    if (holdingNow) CustomAccessibilityAction("Stop and send") { stop(); true }
                    else CustomAccessibilityAction("Start talking") { start(); true },
                )
            }
            .testTag("orb-hold"),
    )
}

/** Ticks the clocks on the second. */
private suspend fun tick(now: MutableLongState, wall: MutableLongState, clock: () -> Long, wallClock: () -> Long) {
    while (true) {
        val t = clock()
        now.longValue = t
        wall.longValue = wallClock()
        delay(1000 - (t % 1000).coerceIn(0, 999))
    }
}
