package com.folio.hermeslive.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.folio.hermeslive.call.CallActivity
import com.folio.hermeslive.call.CallPhase
import com.folio.hermeslive.call.CallUiState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** What the orb shows. */
enum class OrbMood { Idle, Connecting, Listening, UserSpeaking, Thinking, HermesSpeaking, Muted, Music }

fun orbMood(state: CallUiState): OrbMood = when {
    state.phase == CallPhase.Connecting || state.phase == CallPhase.Reconnecting -> OrbMood.Connecting
    !state.inCall -> OrbMood.Idle
    state.activity == CallActivity.HermesSpeaking -> OrbMood.HermesSpeaking
    state.muted || state.onOtherCall -> OrbMood.Muted
    state.activity == CallActivity.UserSpeaking -> OrbMood.UserSpeaking
    state.musicInGap -> OrbMood.Music
    state.activity == CallActivity.Thinking -> OrbMood.Thinking
    else -> OrbMood.Listening
}

/** A calm, print-like orb that breathes, ripples with whoever is speaking and orbits while Hermes works. */
@Composable
fun Orb(mood: OrbMood, level: Float, modifier: Modifier = Modifier) {
    val time = rememberInfiniteTransition(label = "orb")
    val t by time.animateFloat(0f, 1f, infiniteRepeatable(tween(12_000, easing = LinearEasing), RepeatMode.Restart), label = "t")
    val smooth by animateFloatAsState(level.coerceIn(0f, 1f), spring(stiffness = 300f), label = "level")
    val tint by animateColorAsState(
        when (mood) {
            OrbMood.HermesSpeaking -> Ember
            OrbMood.Idle, OrbMood.Muted -> PrintMute
            else -> PrintInk
        },
        tween(400), label = "tint",
    )
    Canvas(modifier.semantics { contentDescription = "Hermes ${mood.name}" }) {
        val c = center
        val base = size.minDimension / 2f * 0.52f
        val phase = t * 2f * PI.toFloat()
        val breathe = 1f + 0.025f * sin(phase * 3f) // one breath every 4 s
        val r = base * breathe

        // Halo
        val haloAlpha = when (mood) {
            OrbMood.Idle, OrbMood.Muted -> 0.06f
            OrbMood.HermesSpeaking, OrbMood.UserSpeaking -> 0.12f + 0.20f * smooth
            else -> 0.10f
        }
        drawCircle(
            Brush.radialGradient(listOf(tint.copy(alpha = haloAlpha), Color.Transparent), c, r * 1.9f),
            r * 1.9f, c,
        )

        when (mood) {
            OrbMood.Connecting -> drawCircle(
                tint.copy(alpha = 0.5f), r * 1.18f, c,
                style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 10.dp.toPx()), phase * 40f)),
            )
            OrbMood.Thinking -> {
                drawCircle(tint.copy(alpha = 0.14f), r * 1.2f, c, style = Stroke(1.5.dp.toPx()))
                drawArc(
                    Ember.copy(alpha = 0.9f), (t * 360f * 7.5f) % 360f, 70f, false,
                    Offset(c.x - r * 1.2f, c.y - r * 1.2f), Size(r * 2.4f, r * 2.4f),
                    style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round),
                )
            }
            OrbMood.Music -> for (i in 0 until 3) {
                val a = phase * 2f + i * (2f * PI.toFloat() / 3f)
                drawCircle(PrintMute.copy(alpha = 0.8f), 3.dp.toPx(), Offset(c.x + cos(a) * r * 1.35f, c.y + sin(a) * r * 1.35f))
            }
            OrbMood.UserSpeaking, OrbMood.HermesSpeaking -> for (i in 1..3) {
                val spread = (phase * 4f / (2f * PI.toFloat()) + i / 3f) % 1f
                drawCircle(
                    tint.copy(alpha = (0.35f * (1f - spread)) * (0.4f + smooth)),
                    r * (1.05f + spread * (0.35f + smooth * 0.35f)), c, style = Stroke(1.5.dp.toPx()),
                )
            }
            else -> Unit
        }

        // Core: a waveform edge while someone speaks, a plain disc otherwise.
        val speaking = mood == OrbMood.HermesSpeaking || mood == OrbMood.UserSpeaking
        val coreAlpha = when (mood) {
            OrbMood.Idle -> 0.35f
            OrbMood.Muted -> 0.28f
            else -> 0.85f
        }
        val fill = Brush.radialGradient(listOf(tint.copy(alpha = coreAlpha), tint.copy(alpha = coreAlpha * 0.55f)), c, r)
        if (speaking) {
            val path = Path()
            val steps = 96
            for (k in 0..steps) {
                val th = k / steps.toFloat() * 2f * PI.toFloat()
                val wobble = smooth * (0.10f * sin(6 * th + phase * 9f) + 0.05f * sin(11 * th - phase * 15f))
                val rr = r * (1f + wobble)
                val x = c.x + cos(th) * rr
                val y = c.y + sin(th) * rr
                if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            drawPath(path, fill)
        } else {
            drawCircle(fill, r, c)
        }
        if (mood == OrbMood.Muted) {
            drawLine(VoidBlack.copy(alpha = 0.9f), Offset(c.x - r * 0.45f, c.y - r * 0.45f), Offset(c.x + r * 0.45f, c.y + r * 0.45f), 3.dp.toPx(), StrokeCap.Round)
        }
    }
}
