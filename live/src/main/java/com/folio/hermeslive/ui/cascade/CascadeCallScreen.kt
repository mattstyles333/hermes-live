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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
) {
    val ui = state.collectAsState()
    CascadeCallContent(
        ui, events, intents, modifier, chrome, micLevel, proximityNear, motion, pauseMs, decisions, history, clock, wallClock,
        approvals = approvals,
    )
}

/** [CascadeCallScreen] on a [State]; [scene] and [memory] injectable for renders, [animate] false for still renders. */
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
    val input = remember(pauseMs, chrome.muted, micLevel) { SceneInput(pauseMs, chrome.muted, jevAnchor, micLevel) }

    Box(modifier.fillMaxSize().background(VoidBlack).testTag("cascade-screen")) {
        CascadeScene(ui, scene, motion, proximityNear, input, wake, animate && !proximityNear)
        if (proximityNear) return@Box
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val orbY = maxHeight * ORB_Y
            HoldTarget(Modifier.align(Alignment.TopCenter).offset(y = orbY - 90.dp).size(180.dp), memory, current, sections.holding)
            Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))) {
                TopBar(chrome, now, sections.top)
                JevStrip(sections.jev, memory.chipEpoch, motion, onAnchor = { jevAnchor[0] = it.x; jevAnchor[1] = it.y }) { memory.decisionsOpen = true }
                ConnectionBanner(sections.banner)
                Flyouts(memory, motion)
            }
            Box(Modifier.align(Alignment.TopCenter).offset(y = orbY + 148.dp)) { CaptionView(sections.caption, memory, onWord, motion) }
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                    .padding(horizontal = 14.dp),
            ) {
                approvals?.invoke()
                ResumeCard(sections.resume, history, wall, current)
                TaskTray(sections.tasks, now, memory, trayActions, motion, Modifier.padding(top = 8.dp))
                Controls(chrome, current)
            }
        }
        TaskSheetHost(memory, sections.tasks, now, sheetActions)
        if (memory.decisionsOpen) {
            val list = remember { decisions().take(20) }
            SheetFrame("decisions", onClose = { memory.decisionsOpen = false }) { DecisionsContent(list) { memory.decisionsOpen = false } }
        }
    }
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
