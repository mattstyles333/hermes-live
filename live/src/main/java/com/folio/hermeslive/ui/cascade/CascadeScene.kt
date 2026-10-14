package com.folio.hermeslive.ui.cascade

import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import com.folio.hermes.cascade.CascadeUiState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay

/** Frame period while only the idle orb breathes (about 20 fps). */
internal const val CALM_FRAME_MS = 50L

/**
 * The one canvas behind the call screen. It reads the state only in the draw pass, so levels
 * and pause progress never recompose anything. It asks for frames only while something moves
 * ([SceneModel.frameNeed]); otherwise it sleeps until [wake] fires (a state change or an
 * event). When [near] (the proximity sensor) it draws nothing and runs no frames.
 */
@Composable
internal fun CascadeScene(
    ui: State<CascadeUiState>,
    model: SceneModel,
    motion: CascadeMotion,
    near: Boolean,
    input: SceneInput,
    wake: Channel<Unit>,
    animate: Boolean,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val sprites = remember(density.density) { SceneSprites(density.density) }
    val measurer = rememberTextMeasurer(cacheSize = 8)
    val frame = remember { mutableLongStateOf(0L) }
    model.reduceMotion = motion.reduceMotion
    val description by remember { derivedStateOf(structuralEqualityPolicy()) { sceneDescription(ui.value) } }

    if (animate && !near) {
        LaunchedEffect(model, motion) {
            var last = -1L
            while (true) {
                val s = ui.value
                model.sync(s.tasks, offline = !s.connection.online, pausing = s.pausing())
                val need = model.frameNeed(speaking = s.bill.speaking || s.inkOrb(), pausing = s.pausing(), jevInFlight = s.jev.inFlight)
                if (need == FrameNeed.None) {
                    model.settle(s.orbLevel(input.micLevel()), s.inkOrb())
                    frame.longValue++
                    last = -1L
                    wake.receive()
                    continue
                }
                if (need == FrameNeed.Calm) delay(CALM_FRAME_MS)
                withInfiniteAnimationFrameNanos { now ->
                    val dt = if (last < 0) 0f else ((now - last) / 1e9f).coerceIn(0f, 0.05f)
                    last = now
                    val cur = ui.value
                    model.step(dt / motion.durationScale, cur.jev.inFlight, cur.orbLevel(input.micLevel()), cur.inkOrb())
                    frame.longValue = now
                }
            }
        }
    }

    Canvas(
        modifier.fillMaxSize()
            .testTag("cascade-scene")
            .semantics { contentDescription = description },
    ) {
        frame.longValue // redraw on each frame
        if (near) return@Canvas
        drawScene(model, ui.value, sprites, measurer, input)
    }
}

/** What the scene shows, for TalkBack (everything is also in the cards and captions). */
internal fun sceneDescription(s: CascadeUiState): String {
    val who = orbStatus(s)
    val running = s.tasks.count { it.status.live }
    val agents = when (running) {
        0 -> ""
        1 -> " One agent working."
        else -> " $running agents working."
    }
    val offline = if (!s.connection.online) " Offline." else ""
    return "Bill: $who.$agents$offline"
}
