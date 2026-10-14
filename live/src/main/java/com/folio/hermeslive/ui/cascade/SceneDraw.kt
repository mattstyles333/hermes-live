package com.folio.hermeslive.ui.cascade

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.folio.hermes.cascade.CascadeUiState
import com.folio.hermes.cascade.TaskUiStatus
import com.folio.hermes.cascade.TurnPhase
import com.folio.hermeslive.ui.EndRed
import com.folio.hermeslive.ui.Ember
import com.folio.hermeslive.ui.PrintInk
import com.folio.hermeslive.ui.VoidBlack
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Where the orb sits, as a fraction of the screen height (the prototype's 300/844). */
internal const val ORB_Y = 0.34f

/** Base orb radius (dp): Bill, and the smaller listening well. */
internal const val ORB_R = 70f
internal const val WELL_R = 62f

/** The end: how much the orb shrinks, its soft ink glow (dimmer when dropped), the row under it. */
internal const val END_SHRINK = 0.42f
internal const val END_GLOW = 0.42f
internal const val END_GLOW_DROPPED = 0.26f
internal const val END_ROW_DY = 112f
internal const val END_ROW_SCALE = 0.25f

/** Everything the draw pass needs besides the state and the model. Built per composition, not per frame. */
internal class SceneInput(
    val pauseMs: Int,
    val muted: Boolean,
    /** The Jev node's centre in px (x, y), set when it is laid out; negative until then. */
    val jevAnchor: FloatArray,
    val micLevel: () -> Float,
    /** The end transition, or null while the call is live. */
    val endRun: EndRun? = null,
    /** Ms since the end began (a State read in the draw pass, so each tick redraws). */
    val endMs: () -> Long = { 0L },
)

private val LabelText = TextStyle(fontSize = 11.sp, color = PrintInk)

/** Orb mode for a state: the ink listening well while the user speaks or holds to talk. */
internal fun CascadeUiState.inkOrb(): Boolean = turn.phase == TurnPhase.UserSpeaking || turn.holdToTalk

/** The level the orb follows: Bill's output while he speaks, the mic while the user does. */
internal fun CascadeUiState.orbLevel(mic: Float): Float = when {
    bill.speaking -> bill.level
    inkOrb() -> mic
    else -> 0f
}

internal fun CascadeUiState.pausing(): Boolean =
    !turn.holdToTalk && (turn.phase == TurnPhase.Pausing || turn.phase == TurnPhase.Deciding)

/**
 * Draws the scene. Reads only pre-allocated model fields and cached sprites and strokes;
 * colours are value classes. The only allocation is a satellite label's text layout, once per
 * label.
 */
internal fun DrawScope.drawScene(m: SceneModel, s: CascadeUiState, sp: SceneSprites, measurer: TextMeasurer, input: SceneInput) {
    val dp = density
    m.layout(size.width / 2f, size.height * ORB_Y, dp)
    m.sync(s.tasks, offline = !s.connection.online, pausing = s.pausing())
    val cx = m.cx
    val cy = m.cy
    val t = m.time
    val still = m.reduceMotion
    val run = input.endRun
    val endMs = if (run != null) input.endMs() else 0L
    // The end: w winds the live scene down (0..1); c collapses the orb into the summary card.
    val w = if (run != null) EndTimeline.wind(endMs, run.reduced) else 0f
    val c = if (run?.outcome == EndOutcome.Summary) EndTimeline.card(endMs, run.reduced) else 0f
    val live = 1f - w

    // Ambient wash.
    sprite(sp.ambient, cx, cy, 320f * dp, 1f - 0.7f * w, null)

    // Orbits.
    for (sat in m.sats) {
        if (sat.phase != SatPhase.Live) continue
        val rx = sat.orbit * dp
        val ry = rx * SceneModel.ORBIT_Y
        drawOval(PrintInk, Offset(cx - rx, cy - ry), Size(rx * 2, ry * 2), alpha = 0.06f * sat.appear * live, style = sp.hair)
    }

    // Bill's orb, or the listening well. At the end it shrinks and dims from ember to a soft ink glow.
    val breathe = if (still) 1f else 1f + 0.03f * sin(t * 1.6f)
    val baseR = ORB_R + (WELL_R - ORB_R) * m.inkMix
    val shrink = (if (run != null && !run.reduced) 1f - END_SHRINK * w else 1f) * (1f - 0.7f * c)
    val r = (baseR * breathe + m.level * 12f * live) * dp * shrink
    val orbAlpha = if (input.muted) 0.45f else 1f
    if (m.inkMix < 1f) sprite(sp.emberOrb, cx, cy, r * 1.25f, (1f - m.inkMix) * orbAlpha * live, null)
    val glow = if (run?.outcome == EndOutcome.Dropped) END_GLOW_DROPPED else END_GLOW
    val ink = m.inkMix * orbAlpha * live + glow * w * (1f - c)
    if (ink > 0f) sprite(sp.inkOrb, cx, cy, r * 1.25f, ink, null)

    // Voice rings: ember for Bill, ink for the user. They stop at the end.
    val speaking = (s.bill.speaking || s.inkOrb()) && run == null
    if (speaking && m.level > 0.01f) {
        val ringColor = if (s.bill.speaking) Ember else PrintInk
        if (still) {
            drawCircle(ringColor, r + 18f * dp, Offset(cx, cy), alpha = 0.25f * m.level, style = sp.ring)
        } else {
            for (i in 0 until 3) {
                val ph = (t * 0.9f + i / 3f) % 1f
                drawCircle(ringColor, r + ph * 70f * dp, Offset(cx, cy), alpha = (1f - ph) * 0.35f * m.level, style = sp.ring)
            }
        }
    }
    if (m.cutInFlash > 0f) drawCircle(PrintInk, r + 6f * dp, Offset(cx, cy), alpha = 0.5f * m.cutInFlash, style = sp.ripple)

    // The pause ring, its early-call tick and the synapse to Jev.
    val pr = r + 22f * dp
    if (s.pausing() && live > 0f) {
        drawCircle(PrintInk, pr, Offset(cx, cy), alpha = 0.12f * live, style = sp.pauseTrack)
        val p = s.turn.pauseProgress.coerceIn(0f, 1f)
        if (p > 0f) {
            drawArc(Ember, -90f, p * 360f, false, Offset(cx - pr, cy - pr), Size(pr * 2, pr * 2), alpha = 0.9f * live, style = sp.pauseFill)
        }
        val a = SceneModel.ringAngle(SceneModel.earlyTickFraction(input.pauseMs))
        val lit = m.earlyTickLit || s.jev.inFlight
        drawCircle(if (lit) Ember else PrintInk, 3.5f * dp, Offset(cx + cos(a) * pr, cy + sin(a) * pr), alpha = 0.9f * live)
    }
    val synapse = (if (s.jev.inFlight) 1f else m.jevGlow) * live
    if (synapse > 0f) {
        val ax = if (input.jevAnchor[0] >= 0f) input.jevAnchor[0] else 33f * dp
        val ay = if (input.jevAnchor[1] >= 0f) input.jevAnchor[1] else 81f * dp
        val ex = cx - pr * 0.7f
        val ey = cy - pr * 0.7f
        val qx = ax + 27f * dp
        val qy = ay + 119f * dp
        val spacing = 8f * dp
        val shift = if (still) 0f else (t * 40f * dp) % spacing
        val dots = 40
        for (i in 0 until dots) {
            val k = (i * spacing + shift) / (dots * spacing)
            if (k > 1f) break
            val u = 1f - k
            val x = u * u * ax + 2 * u * k * qx + k * k * ex
            val y = u * u * ay + 2 * u * k * qy + k * k * ey
            drawCircle(Ember, 1.3f * dp, Offset(x, y), alpha = 0.55f * synapse)
        }
    }

    // Satellites; at the end the live ones leave their orbits for the "still working" row.
    if (run == null) {
        for (sat in m.sats) drawSatellite(sat, m, sp, measurer, t, still)
    } else {
        drawEndSatellites(m, sp, measurer, t, still, w)
    }

    // Ripples.
    for (i in 0 until m.rippleCount()) {
        val a = m.rippleAlpha(i)
        if (a <= 0f) continue
        drawCircle(if (m.rippleOk(i)) Success else Ember, m.rippleR(i), Offset(m.rippleX(i), m.rippleY(i)), alpha = a.coerceIn(0f, 1f), style = sp.ripple)
    }

    // Sparks.
    val sparkSize = Size(2f * dp, 2f * dp)
    for (i in 0 until m.sparkCount()) {
        val life = m.sparkLife(i)
        if (life <= 0f) continue
        drawRect(Ember, Offset(m.sparkX(i), m.sparkY(i)), sparkSize, alpha = life.coerceIn(0f, 1f))
    }

    // Offline: dim the scene.
    if (m.dim > 0f) drawRect(VoidBlack, alpha = 0.5f * m.dim)
}

/**
 * The satellites during the end: each live one eases from where it stopped on its orbit to its
 * place in the row under the orb (reduce motion: fades from one to the other), smaller, label
 * and trail fading out, pulsing gently while it still works. The rest end as they would.
 */
private fun DrawScope.drawEndSatellites(m: SceneModel, sp: SceneSprites, measurer: TextMeasurer, t: Float, still: Boolean, w: Float) {
    val dp = density
    var n = 0
    for (sat in m.sats) if (sat.phase == SatPhase.Live) n++
    val spacing = EndTimeline.rowSpacingDp(n, size.width / dp) * dp
    val rowY = m.cy + END_ROW_DY * dp
    var i = 0
    for (sat in m.sats) {
        if (sat.phase != SatPhase.Live) {
            drawSatellite(sat, m, sp, measurer, t, still)
            continue
        }
        val rowX = m.cx + (i - (n - 1) / 2f) * spacing
        i++
        val pulse = if (still || sat.status == TaskUiStatus.Queued) 1f else 0.8f + 0.2f * sin(t * 2.4f + i)
        val settled = 1f - END_ROW_SCALE * w
        if (still) {
            drawSatellite(sat, m, sp, measurer, t, true, atX = sat.x, atY = sat.y, fade = 1f - w)
            drawSatellite(sat, m, sp, measurer, t, true, atX = rowX, atY = rowY, fade = w, scaleBy = settled, labelFade = 0f)
        } else {
            drawSatellite(
                sat, m, sp, measurer, t, false, atX = sat.x + (rowX - sat.x) * w, atY = sat.y + (rowY - sat.y) * w, fade = 1f + (pulse - 1f) * w, scaleBy = settled, labelFade = (1f - 2f * w).coerceAtLeast(0f), trailFade = 1f - w)
        }
    }
}

private fun DrawScope.drawSatellite(
    sat: Satellite,
    m: SceneModel,
    sp: SceneSprites,
    measurer: TextMeasurer,
    t: Float,
    still: Boolean,
    /** Where to draw it (px); NaN: where it is. Floats, not a nullable Offset: no boxing per frame. */
    atX: Float = Float.NaN,
    atY: Float = Float.NaN,
    fade: Float = 1f,
    scaleBy: Float = 1f,
    labelFade: Float = 1f,
    trailFade: Float = 1f,
) {
    if (sat.phase == SatPhase.Free) return
    val dp = density
    if (sat.phase == SatPhase.Flying) {
        val e = SceneModel.easeOutCubic(sat.k)
        // A short streak behind it, along the path in.
        val e0 = (e - 0.35f).coerceAtLeast(0f)
        for (i in 1..8) {
            val a = e0 + (e - e0) * (i - 1) / 8f
            val b = e0 + (e - e0) * i / 8f
            drawLine(
                Success,
                Offset(sat.fromX + (m.cx - sat.fromX) * a, sat.fromY + (m.cy - sat.fromY) * a),
                Offset(sat.fromX + (m.cx - sat.fromX) * b, sat.fromY + (m.cy - sat.fromY) * b),
                strokeWidth = (1f + i * 0.35f) * dp, alpha = i / 8f * 0.6f * (1f - sat.k * 0.5f),
            )
        }
        sprite(sp.glow, sat.x, sat.y, (24f * (1f - e) + 8f) * dp, 0.85f * (1f - sat.k * 0.7f), sp.tintOk)
        drawCircle(Success, (7f * (1f - e) + 3f) * dp, Offset(sat.x, sat.y), alpha = 1f - sat.k * 0.5f)
        return
    }
    var alpha = sat.appear * fade
    var scale = scaleBy
    when (sat.phase) {
        SatPhase.Failing -> alpha *= 1f - sat.k
        SatPhase.Shrinking -> if (still) alpha *= 1f - sat.k else scale = 1f - SceneModel.easeOutCubic(sat.k)
        SatPhase.Fading -> alpha *= 1f - sat.k
        else -> Unit
    }
    if (alpha <= 0.001f || scale <= 0.001f) return

    // Trail, longer with steps.
    if (sat.phase == SatPhase.Live && !still && sat.trailLen > 1 && trailFade > 0f) {
        val n = sat.trailLen
        for (i in 1 until n) {
            val a = i / n.toFloat()
            drawLine(
                Ember, Offset(m.cx + sat.trailX(i - 1), m.cy + sat.trailY(i - 1)), Offset(m.cx + sat.trailX(i), m.cy + sat.trailY(i)),
                strokeWidth = (1f + a * 2f) * dp, alpha = a * 0.45f * alpha * trailFade,
            )
        }
    }

    val x = if (atX.isNaN()) sat.x else atX
    val y = if (atY.isNaN()) sat.y else atY
    val queued = sat.status == TaskUiStatus.Queued
    val waiting = sat.status == TaskUiStatus.Waiting
    val tint: ColorFilter = when {
        sat.phase == SatPhase.Failing || sat.status == TaskUiStatus.Failed || sat.notSent -> sp.tintRed
        waiting || queued -> sp.tintInk
        else -> sp.tintEmber
    }
    val pulse = if (waiting && !still) 0.55f + 0.45f * abs(sin(t * 4f)) else 1f
    val glowAlpha = when {
        queued -> 0.25f
        waiting -> 0.8f * pulse
        else -> 0.9f
    }
    val r = (7f + sat.flash * 6f) * dp * scale
    sprite(sp.glow, x, y, r * 3f, glowAlpha * alpha, tint)
    drawCircle(
        if (sat.phase == SatPhase.Failing) EndRed else PrintInk, 3.5f * dp * scale, Offset(x, y),
        alpha = (if (queued) 0.4f else 0.95f) * alpha,
    )
    if (!queued && sat.steps > 0) {
        val ar = 10f * dp * scale
        drawArc(
            Ember, -90f, min(1f, sat.steps / 6f) * 360f, false, Offset(x - ar, y - ar), Size(ar * 2, ar * 2),
            alpha = 0.8f * alpha, style = sp.arc,
        )
    }
    if (sat.labelFor != sat.label) {
        sat.labelLayout = measurer.measure(sat.label, LabelText)
        sat.labelFor = sat.label
    }
    sat.labelLayout?.let { layout ->
        if (labelFade > 0f) drawText(layout, topLeft = Offset(x - layout.size.width / 2f, y + 16f * dp * scale), alpha = 0.7f * alpha * labelFade * (if (queued) 0.6f else 1f))
    }
}

/** Draws [image] centred at ([x], [y]) with radius [radius]. */
private fun DrawScope.sprite(image: ImageBitmap, x: Float, y: Float, radius: Float, alpha: Float, filter: ColorFilter?) {
    if (alpha <= 0f || radius <= 0f) return
    val d = (radius * 2f).toInt().coerceAtLeast(1)
    drawImage(
        image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(image.width, image.height),
        dstOffset = IntOffset((x - radius).toInt(), (y - radius).toInt()),
        dstSize = IntSize(d, d),
        alpha = alpha.coerceIn(0f, 1f),
        colorFilter = filter,
        filterQuality = FilterQuality.Medium,
    )
}
