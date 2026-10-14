package com.folio.hermeslive.ui.cascade

import androidx.compose.ui.text.TextLayoutResult
import com.folio.hermes.cascade.CascadeEvent
import com.folio.hermes.cascade.SendState
import com.folio.hermes.cascade.TaskUi
import com.folio.hermes.cascade.TaskUiStatus
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** What a satellite is doing. */
internal enum class SatPhase {
    /** Slot unused. */
    Free,

    /** Orbiting (or parked, or waiting): mirrors a non-terminal task. */
    Live,

    /** Done: flies into the orb, then a success ripple. */
    Flying,

    /** Failed: red, then fades out. */
    Failing,

    /** Cancelled: shrinks away. */
    Shrinking,

    /** Gone from the state (or bumped past the cap): fades out. */
    Fading,
}

/** One agent satellite. Mutable and pooled: the scene never allocates one per frame. */
internal class Satellite {
    var id: String? = null
    var label: String = ""
    var phase: SatPhase = SatPhase.Free
    var status: TaskUiStatus = TaskUiStatus.Running

    /** Two submits failed: red and parked until retried (R-DUR-4). */
    var notSent: Boolean = false
    var orbit: Float = 0f
    var angle: Float = 0f
    var steps: Int = 0
    var flash: Float = 0f
    var appear: Float = 0f

    /** Progress of the terminal animation, 0..1. */
    var k: Float = 0f

    /** Where the fly-in started (px). */
    var fromX: Float = 0f
    var fromY: Float = 0f
    var x: Float = 0f
    var y: Float = 0f

    /** Trail of past positions (px from the orb centre, so a re-layout keeps it), a ring buffer. */
    val trail = FloatArray(SceneModel.TRAIL_MAX * 2)
    var trailHead: Int = 0
    var trailLen: Int = 0

    /** The label under the satellite, measured once per label (not per frame). */
    var labelLayout: TextLayoutResult? = null
    var labelFor: String? = null

    /** Orbiting now: running and sent. */
    val moves: Boolean get() = status.orbits && !notSent

    fun reset() {
        id = null
        label = ""
        phase = SatPhase.Free
        status = TaskUiStatus.Running
        notSent = false
        steps = 0
        flash = 0f
        appear = 0f
        k = 0f
        trailHead = 0
        trailLen = 0
        labelLayout = null
        labelFor = null
    }

    fun pushTrail(px: Float, py: Float, max: Int) {
        trail[trailHead * 2] = px
        trail[trailHead * 2 + 1] = py
        trailHead = (trailHead + 1) % SceneModel.TRAIL_MAX
        trailLen = min(trailLen + 1, max)
    }

    /** The [i]th trail point from oldest (0) to newest (trailLen-1). */
    fun trailX(i: Int): Float = trail[idx(i) * 2]
    fun trailY(i: Int): Float = trail[idx(i) * 2 + 1]
    private fun idx(i: Int): Int = (trailHead - trailLen + i + SceneModel.TRAIL_MAX * 2) % SceneModel.TRAIL_MAX
}

/** How often the scene needs a frame. */
internal enum class FrameNeed {
    /** Nothing moves: draw only when the state changes. */
    None,

    /** Only the idle orb breathes: a low frame rate is enough. */
    Calm,

    /** Something is moving: every frame. */
    Full,
}

/**
 * The scene behind the call screen, as plain Kotlin: satellites, sparks, ripples, the Jev glow.
 * Pure and steppable ([step] with a fixed dt replays any moment deterministically; sparks use a
 * seeded RNG). The state is the truth: [sync] settles every satellite from the task list, so a
 * dropped [CascadeEvent] never leaves a ghost. Events only add decoration.
 *
 * Positions are in px around the orb centre ([cx], [cy]), set by the draw pass ([layout]).
 * Pools are pre-allocated: nothing is allocated per frame.
 */
internal class SceneModel(seed: Long = 7L) {
    private val rng = Random(seed)

    /** Scene time in seconds (already scaled by the animator scale). */
    var time: Float = 0f
        private set

    var reduceMotion: Boolean = false

    /** The call ended: satellites stop on their orbits (the draw pass moves them to the row). */
    var ending: Boolean = false

    // Geometry (px), set by the draw pass.
    var cx: Float = 0f
        private set
    var cy: Float = 0f
        private set
    var dp: Float = 1f
        private set

    // Jev
    var jevGlow: Float = 0f
        private set
    var earlyTickLit: Boolean = false
        private set
    var cutInFlash: Float = 0f
        private set

    /** Offline dim, eased toward [dimTarget]. */
    var dim: Float = 0f
        private set
    private var dimTarget: Float = 0f

    val sats = Array(MAX_SATS) { Satellite() }

    // Sparks pool.
    private val sx = FloatArray(SPARKS)
    private val sy = FloatArray(SPARKS)
    private val svx = FloatArray(SPARKS)
    private val svy = FloatArray(SPARKS)
    private val slife = FloatArray(SPARKS)
    private var sparkNext = 0

    // Ripples pool.
    private val rx = FloatArray(RIPPLES)
    private val ry = FloatArray(RIPPLES)
    private val rr = FloatArray(RIPPLES)
    private val ra = FloatArray(RIPPLES)
    private val rok = BooleanArray(RIPPLES)
    private var rippleNext = 0

    private var lastTasks: List<TaskUi>? = null

    /** A satellite left: a waiting task may take its slot at the next [sync]. */
    private var slotFreed = false
    private var lastPausing = false

    /** Smoothed orb level (Bill's output, or the mic while the user speaks). */
    var level: Float = 0f
        private set

    /** 0 = Bill's ember orb, 1 = the ink listening well (eased). */
    var inkMix: Float = 0f
        private set

    fun layout(cx: Float, cy: Float, dp: Float) {
        if (cx == this.cx && cy == this.cy && dp == this.dp) return
        this.cx = cx
        this.cy = cy
        this.dp = dp
        for (sat in sats) if (sat.phase == SatPhase.Live) place(sat)
    }

    /** Jumps the eased values to their targets (static renders, reduce motion). */
    fun settle(targetLevel: Float, ink: Boolean) {
        level = targetLevel.coerceIn(0f, 1f)
        inkMix = if (ink) 1f else 0f
        dim = dimTarget
    }

    // ── state ───────────────────────────────────────────────────────────────────────────────

    /** Settles the satellites from [tasks] (identity-checked: cheap to call every frame). */
    fun sync(tasks: List<TaskUi>, offline: Boolean, pausing: Boolean) {
        dimTarget = if (offline) 1f else 0f
        if (pausing && !lastPausing) earlyTickLit = false
        lastPausing = pausing
        if (tasks === lastTasks && !slotFreed) return
        lastTasks = tasks
        slotFreed = false

        // Satellites whose task left the state, or turned terminal.
        for (sat in sats) {
            val id = sat.id ?: continue
            if (sat.phase != SatPhase.Live) continue
            val task = tasks.firstOrNull { it.id == id }
            when {
                task == null -> sat.phase = SatPhase.Fading.also { sat.k = 0f }
                task.status == TaskUiStatus.Done -> finish(sat)
                task.status == TaskUiStatus.Failed -> sat.phase = SatPhase.Failing.also { sat.k = 0f; sat.status = TaskUiStatus.Failed }
                task.status == TaskUiStatus.Cancelled -> sat.phase = SatPhase.Shrinking.also { sat.k = 0f; sat.status = TaskUiStatus.Cancelled }
                else -> update(sat, task)
            }
        }

        // Who deserves a satellite: non-terminal tasks, by start order, Queued last; at most MAX_SATS.
        val wanted = tasks.asSequence()
            .filter { it.status.live }
            .sortedWith(compareBy<TaskUi> { it.status == TaskUiStatus.Queued }.thenBy { it.startedAtMs })
            .take(MAX_SATS)
            .toList()
        val wantedIds = wanted.mapTo(HashSet()) { it.id }
        // Bumped past the cap (a queued one making room for a running one).
        for (sat in sats) {
            if (sat.phase == SatPhase.Live && sat.id !in wantedIds) sat.phase = SatPhase.Fading.also { sat.k = 0f }
        }
        for (task in wanted) {
            if (sats.any { it.id == task.id && it.phase != SatPhase.Free }) continue
            val slot = sats.indexOfFirst { it.phase == SatPhase.Free }
            if (slot < 0) break
            spawn(sats[slot], slot, task)
        }
    }

    private fun spawn(sat: Satellite, slot: Int, task: TaskUi) {
        sat.reset()
        sat.id = task.id
        sat.label = shortLabel(task.label)
        sat.phase = SatPhase.Live
        sat.status = task.status
        sat.notSent = task.send == SendState.NotSent
        sat.steps = task.steps
        sat.orbit = orbitFor(slot, task.status)
        sat.angle = START_ANGLE + slot * GOLDEN
        sat.appear = if (reduceMotion) 1f else 0f
        place(sat)
    }

    private fun update(sat: Satellite, task: TaskUi) {
        if (task.steps > sat.steps) {
            // A ToolStarted event may already have flashed it: one burst per step.
            if (!reduceMotion && sat.flash < 0.9f) burst(sat.x, sat.y)
            sat.flash = 1f
        }
        sat.steps = task.steps
        sat.status = task.status
        sat.notSent = task.send == SendState.NotSent
        sat.label = shortLabel(task.label)
        val slot = sats.indexOf(sat)
        sat.orbit = orbitFor(slot, task.status)
    }

    private fun finish(sat: Satellite) {
        sat.status = TaskUiStatus.Done
        sat.k = 0f
        sat.fromX = sat.x
        sat.fromY = sat.y
        sat.phase = if (reduceMotion) SatPhase.Fading else SatPhase.Flying
        if (reduceMotion) ripple(cx, cy, ok = true, startR = 60f * dp)
    }

    // ── events (decoration only) ────────────────────────────────────────────────────────────

    fun onEvent(event: CascadeEvent) {
        when (event) {
            is CascadeEvent.JevFired -> {
                jevGlow = 1f
                if (event.early) earlyTickLit = true
            }
            is CascadeEvent.JevAnswered -> Unit
            is CascadeEvent.UserCutIn -> cutInFlash = 1f
            is CascadeEvent.ToolStarted -> sats.firstOrNull { it.id == event.id && it.phase == SatPhase.Live }?.let {
                it.flash = 1f
                if (!reduceMotion) burst(it.x, it.y)
            }
            else -> Unit
        }
    }

    // ── time ────────────────────────────────────────────────────────────────────────────────

    /** Advances the scene by [dt] seconds (already scaled). Allocation-free. */
    fun step(dt: Float, jevInFlight: Boolean, targetLevel: Float, ink: Boolean) {
        time += dt
        level += (targetLevel.coerceIn(0f, 1f) - level) * min(1f, dt * 14f)
        inkMix += ((if (ink) 1f else 0f) - inkMix) * min(1f, dt * 6f)
        jevGlow = if (jevInFlight) 1f else (jevGlow - dt * 2.5f).coerceAtLeast(0f)
        cutInFlash = (cutInFlash - dt * 3f).coerceAtLeast(0f)
        dim += (dimTarget - dim) * min(1f, dt * 5f)
        if (kotlin.math.abs(dimTarget - dim) < 0.002f) dim = dimTarget

        for (i in sats.indices) stepSat(sats[i], dt)

        for (i in 0 until SPARKS) {
            if (slife[i] <= 0f) continue
            sx[i] += svx[i] * dt
            sy[i] += svy[i] * dt
            slife[i] -= dt * 1.6f
        }
        for (i in 0 until RIPPLES) {
            if (ra[i] <= 0f) continue
            rr[i] += dt * 140f * dp
            ra[i] -= dt * 0.9f
        }
    }

    private fun stepSat(sat: Satellite, dt: Float) {
        when (sat.phase) {
            SatPhase.Free -> return
            SatPhase.Live -> {
                sat.appear = min(1f, sat.appear + dt * 3f)
                if (!reduceMotion && sat.moves && !ending) sat.angle += dt * (0.55f - sat.orbit / 600f)
                sat.flash = (sat.flash - dt * 2.5f).coerceAtLeast(0f)
                place(sat)
                if (!reduceMotion && !ending) sat.pushTrail(sat.x - cx, sat.y - cy, trailMax(sat.steps))
            }
            SatPhase.Flying -> {
                sat.k = min(1f, sat.k + dt * 1.4f)
                val e = easeOutCubic(sat.k)
                sat.x = sat.fromX + (cx - sat.fromX) * e
                sat.y = sat.fromY + (cy - sat.fromY) * e
                if (sat.k >= 1f) {
                    ripple(cx, cy, ok = true, startR = 60f * dp)
                    free(sat)
                }
            }
            SatPhase.Failing -> {
                sat.k = min(1f, sat.k + dt / 1.4f)
                if (sat.k >= 1f) free(sat)
            }
            SatPhase.Shrinking -> {
                sat.k = min(1f, sat.k + dt / 0.5f)
                if (sat.k >= 1f) free(sat)
            }
            SatPhase.Fading -> {
                sat.k = min(1f, sat.k + dt / 0.4f)
                if (sat.k >= 1f) free(sat)
            }
        }
    }

    private fun free(sat: Satellite) {
        sat.reset()
        slotFreed = true
    }

    private fun place(sat: Satellite) {
        sat.x = cx + cos(sat.angle) * sat.orbit * dp
        sat.y = cy + sin(sat.angle) * sat.orbit * dp * ORBIT_Y
    }

    private fun burst(x: Float, y: Float) {
        repeat(SPARKS_PER_TOOL) {
            val i = sparkNext
            sparkNext = (sparkNext + 1) % SPARKS
            sx[i] = x
            sy[i] = y
            svx[i] = (rng.nextFloat() - 0.5f) * 60f * dp
            svy[i] = (rng.nextFloat() - 0.5f) * 60f * dp
            slife[i] = 1f
        }
    }

    private fun ripple(x: Float, y: Float, ok: Boolean, startR: Float) {
        val i = rippleNext
        rippleNext = (rippleNext + 1) % RIPPLES
        rx[i] = x
        ry[i] = y
        rr[i] = startR
        ra[i] = 0.7f
        rok[i] = ok
    }

    // ── reads for the draw pass ─────────────────────────────────────────────────────────────

    fun sparkCount(): Int = SPARKS
    fun sparkLife(i: Int): Float = slife[i]
    fun sparkX(i: Int): Float = sx[i]
    fun sparkY(i: Int): Float = sy[i]
    fun rippleCount(): Int = RIPPLES
    fun rippleAlpha(i: Int): Float = ra[i]
    fun rippleR(i: Int): Float = rr[i]
    fun rippleX(i: Int): Float = rx[i]
    fun rippleY(i: Int): Float = ry[i]
    fun rippleOk(i: Int): Boolean = rok[i]

    /** Satellites on screen (for TalkBack and tests). */
    fun visibleSatellites(): Int = sats.count { it.phase != SatPhase.Free }

    /** How often frames are needed now. */
    fun frameNeed(speaking: Boolean, pausing: Boolean, jevInFlight: Boolean): FrameNeed {
        val transient = hasTransient()
        if (reduceMotion) return if (transient) FrameNeed.Full else FrameNeed.None
        if (transient || speaking || pausing || jevInFlight) return FrameNeed.Full
        for (sat in sats) if (sat.phase == SatPhase.Live && sat.moves && !ending) return FrameNeed.Full
        return FrameNeed.Calm
    }

    private fun hasTransient(): Boolean {
        if (jevGlow > 0f || cutInFlash > 0f || dim != dimTarget) return true
        for (sat in sats) {
            if (sat.phase == SatPhase.Free) continue
            if (sat.phase != SatPhase.Live || sat.appear < 1f || sat.flash > 0f) return true
        }
        for (i in 0 until SPARKS) if (slife[i] > 0f) return true
        for (i in 0 until RIPPLES) if (ra[i] > 0f) return true
        return false
    }

    companion object {
        const val MAX_SATS = 6
        const val TRAIL_MAX = 72
        const val SPARKS = 96
        const val SPARKS_PER_TOOL = 10
        const val RIPPLES = 8
        const val ORBIT_Y = 0.62f
        private const val START_ANGLE = -1.2f
        private const val GOLDEN = 2.399963f
        private const val OUTER = 196f

        fun trailMax(steps: Int): Int = min(TRAIL_MAX, 12 + steps * 10)

        /** Orbit radius in dp: one ring per slot; Queued parks on the outer orbit. */
        fun orbitFor(slot: Int, status: TaskUiStatus): Float = if (status == TaskUiStatus.Queued) OUTER else 112f + slot * 14f

        fun easeOutCubic(k: Float): Float {
            val u = 1f - k
            return 1f - u * u * u
        }

        /** "Stock check · RB2140" → "Stock check". */
        fun shortLabel(label: String): String = label.substringBefore(" · ").take(22)

        /** The early-call tick's position on the pause ring (R-RT-1: at min(500, pause/2) ms). */
        fun earlyTickFraction(pauseMs: Int): Float {
            val p = pauseMs.coerceAtLeast(1)
            return min(500, p / 2).toFloat() / p
        }

        /** Angle (radians) of a ring fraction measured from 12 o'clock, clockwise. */
        fun ringAngle(fraction: Float): Float = (-PI / 2 + fraction * 2 * PI).toFloat()
    }
}

/** Tasks that still get a satellite. */
internal val TaskUiStatus.live: Boolean
    get() = this == TaskUiStatus.Starting || this == TaskUiStatus.Running || this == TaskUiStatus.Waiting || this == TaskUiStatus.Queued

/** Satellites that move along their orbit (Waiting stops; Queued is parked). */
internal val TaskUiStatus.orbits: Boolean
    get() = this == TaskUiStatus.Starting || this == TaskUiStatus.Running
