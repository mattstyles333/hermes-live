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

/** Everything the draw pass needs besides the state and the model. Built per composition, not per frame. */
internal class SceneInput(
    val pauseMs: Int,
    val muted: Boolean,
    /** The Jev node's centre in px (x, y), set when it is laid out; negative until then. */
    val jevAnchor: FloatArray,
    val micLevel: () -> Float,
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

    // Ambient wash.
    sprite(sp.ambient, cx, cy, 320f * dp, 1f, null)

    // Orbits.
    for (sat in m.sats) {
        if (sat.phase != SatPhase.Live) continue
        val rx = sat.orbit * dp
        val ry = rx * SceneModel.ORBIT_Y
        drawOval(PrintInk, Offset(cx - rx, cy - ry), Size(rx * 2, ry * 2), alpha = 0.06f * sat.appear, style = sp.hair)
    }

    // Bill's orb, or the listening well.
    val breathe = if (still) 1f else 1f + 0.03f * sin(t * 1.6f)
    val baseR = ORB_R + (WELL_R - ORB_R) * m.inkMix
    val r = (baseR * breathe + m.level * 12f) * dp
    val orbAlpha = if (input.muted) 0.45f else 1f
    if (m.inkMix < 1f) sprite(sp.emberOrb, cx, cy, r * 1.25f, (1f - m.inkMix) * orbAlpha, null)
    if (m.inkMix > 0f) sprite(sp.inkOrb, cx, cy, r * 1.25f, m.inkMix * orbAlpha, null)

    // Voice rings: ember for Bill, ink for the user.
    val speaking = s.bill.speaking || s.inkOrb()
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
    if (s.pausing()) {
        drawCircle(PrintInk, pr, Offset(cx, cy), alpha = 0.12f, style = sp.pauseTrack)
        val p = s.turn.pauseProgress.coerceIn(0f, 1f)
        if (p > 0f) {
            drawArc(Ember, -90f, p * 360f, false, Offset(cx - pr, cy - pr), Size(pr * 2, pr * 2), alpha = 0.9f, style = sp.pauseFill)
        }
        val a = SceneModel.ringAngle(SceneModel.earlyTickFraction(input.pauseMs))
        val lit = m.earlyTickLit || s.jev.inFlight
        drawCircle(if (lit) Ember else PrintInk, 3.5f * dp, Offset(cx + cos(a) * pr, cy + sin(a) * pr), alpha = 0.9f)
    }
    val synapse = if (s.jev.inFlight) 1f else m.jevGlow
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

    // Satellites.
    for (sat in m.sats) drawSatellite(sat, m, sp, measurer, t, still)

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

private fun DrawScope.drawSatellite(sat: Satellite, m: SceneModel, sp: SceneSprites, measurer: TextMeasurer, t: Float, still: Boolean) {
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
    var alpha = sat.appear
    var scale = 1f
    when (sat.phase) {
        SatPhase.Failing -> alpha *= 1f - sat.k
        SatPhase.Shrinking -> if (still) alpha *= 1f - sat.k else scale = 1f - SceneModel.easeOutCubic(sat.k)
        SatPhase.Fading -> alpha *= 1f - sat.k
        else -> Unit
    }
    if (alpha <= 0.001f || scale <= 0.001f) return

    // Trail, longer with steps.
    if (sat.phase == SatPhase.Live && !still && sat.trailLen > 1) {
        val n = sat.trailLen
        for (i in 1 until n) {
            val a = i / n.toFloat()
            drawLine(
                Ember, Offset(m.cx + sat.trailX(i - 1), m.cy + sat.trailY(i - 1)), Offset(m.cx + sat.trailX(i), m.cy + sat.trailY(i)),
                strokeWidth = (1f + a * 2f) * dp, alpha = a * 0.45f * alpha,
            )
        }
    }

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
    sprite(sp.glow, sat.x, sat.y, r * 3f, glowAlpha * alpha, tint)
    drawCircle(
        if (sat.phase == SatPhase.Failing) EndRed else PrintInk, 3.5f * dp * scale, Offset(sat.x, sat.y),
        alpha = (if (queued) 0.4f else 0.95f) * alpha,
    )
    if (!queued && sat.steps > 0) {
        val ar = 10f * dp * scale
        drawArc(
            Ember, -90f, min(1f, sat.steps / 6f) * 360f, false, Offset(sat.x - ar, sat.y - ar), Size(ar * 2, ar * 2),
            alpha = 0.8f * alpha, style = sp.arc,
        )
    }
    if (sat.labelFor != sat.label) {
        sat.labelLayout = measurer.measure(sat.label, LabelText)
        sat.labelFor = sat.label
    }
    sat.labelLayout?.let { layout ->
        drawText(layout, topLeft = Offset(sat.x - layout.size.width / 2f, sat.y + 16f * dp * scale), alpha = 0.7f * alpha * (if (queued) 0.6f else 1f))
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
