package com.folio.hermeslive.ui.cascade

import com.folio.hermes.cascade.CascadeEvent
import com.folio.hermes.cascade.CascadeUiState

/** One scene frame at 60 fps. */
internal const val FRAME_MS = 16L

/**
 * Steps [this] scene from 0 to [untilMs] at 60 fps, as the frame loop would, feeding it the
 * state at each frame and the events up to it. The prototype's `?at=` for renders and tests.
 * [widthPx] × [heightPx] is the canvas the scene will be drawn on.
 */
internal fun SceneModel.replay(
    untilMs: Long,
    widthPx: Float,
    heightPx: Float,
    density: Float,
    stateAt: (Long) -> CascadeUiState,
    eventsBetween: (Long, Long) -> List<CascadeEvent> = { _, _ -> emptyList() },
    micLevel: Float = 0.5f,
) {
    layout(widthPx / 2f, heightPx * ORB_Y, density)
    var t = 0L
    while (t < untilMs) {
        val next = minOf(untilMs, t + FRAME_MS)
        for (e in eventsBetween(t, next)) onEvent(e)
        val s = stateAt(next)
        sync(s.tasks, offline = !s.connection.online, pausing = s.pausing())
        step((next - t) / 1000f, s.jev.inFlight, s.orbLevel(micLevel), s.inkOrb())
        t = next
    }
    val end = stateAt(untilMs)
    if (reduceMotion) settle(end.orbLevel(micLevel), end.inkOrb())
}

/** Replays the scripted call [fake] up to [atMs]. */
internal fun SceneModel.replay(fake: FakeCascadeUi, atMs: Long, widthPx: Float, heightPx: Float, density: Float) =
    replay(atMs, widthPx, heightPx, density, fake::stateAt, { a, b -> fake.eventsBetween(a, b).map { it.second } })
