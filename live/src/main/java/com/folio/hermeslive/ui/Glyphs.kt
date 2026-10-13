package com.folio.hermeslive.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val VoidKnob = Color(0xFF050607)

/** Thin-stroke glyphs in Folio's style (no icon font, no extended icon set). */
enum class GlyphKind { Mic, MicOff, Speaker, Earpiece, Headset, Bluetooth, EndCall, Gear, Back, Talk }

@Composable
fun Glyph(kind: GlyphKind, color: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Canvas(modifier.size(size)) { drawGlyph(kind, color) }
}

private fun DrawScope.drawGlyph(kind: GlyphKind, color: Color) {
    val w = size.minDimension
    val s = Stroke(width = w * 0.075f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun p(x: Float, y: Float) = Offset(x * w, y * w)
    when (kind) {
        GlyphKind.Mic, GlyphKind.MicOff -> {
            drawRoundRect(color, p(0.37f, 0.12f), Size(0.26f * w, 0.44f * w), CornerRadius(0.13f * w), style = s)
            drawArc(color, 0f, 180f, false, p(0.24f, 0.30f), Size(0.52f * w, 0.42f * w), style = s)
            drawLine(color, p(0.5f, 0.72f), p(0.5f, 0.86f), s.width, StrokeCap.Round)
            drawLine(color, p(0.38f, 0.86f), p(0.62f, 0.86f), s.width, StrokeCap.Round)
            if (kind == GlyphKind.MicOff) drawLine(color, p(0.18f, 0.14f), p(0.82f, 0.86f), s.width, StrokeCap.Round)
        }
        GlyphKind.Speaker -> {
            val body = Path().apply {
                moveTo(0.14f * w, 0.40f * w); lineTo(0.30f * w, 0.40f * w); lineTo(0.50f * w, 0.22f * w)
                lineTo(0.50f * w, 0.78f * w); lineTo(0.30f * w, 0.60f * w); lineTo(0.14f * w, 0.60f * w); close()
            }
            drawPath(body, color, style = s)
            drawArc(color, -40f, 80f, false, p(0.46f, 0.34f), Size(0.24f * w, 0.32f * w), style = s)
            drawArc(color, -45f, 90f, false, p(0.44f, 0.20f), Size(0.42f * w, 0.60f * w), style = s)
        }
        GlyphKind.Earpiece -> {
            drawRoundRect(color, p(0.30f, 0.10f), Size(0.40f * w, 0.80f * w), CornerRadius(0.08f * w), style = s)
            drawLine(color, p(0.44f, 0.18f), p(0.56f, 0.18f), s.width, StrokeCap.Round)
        }
        GlyphKind.Headset -> {
            drawArc(color, 180f, 180f, false, p(0.18f, 0.18f), Size(0.64f * w, 0.64f * w), style = s)
            drawRoundRect(color, p(0.14f, 0.50f), Size(0.16f * w, 0.28f * w), CornerRadius(0.06f * w), style = s)
            drawRoundRect(color, p(0.70f, 0.50f), Size(0.16f * w, 0.28f * w), CornerRadius(0.06f * w), style = s)
        }
        GlyphKind.Bluetooth -> {
            val rune = Path().apply {
                moveTo(0.30f * w, 0.32f * w); lineTo(0.68f * w, 0.66f * w); lineTo(0.50f * w, 0.84f * w)
                lineTo(0.50f * w, 0.16f * w); lineTo(0.68f * w, 0.34f * w); lineTo(0.30f * w, 0.68f * w)
            }
            drawPath(rune, color, style = s)
        }
        GlyphKind.EndCall -> {
            // A handset lying down: a wide arc with two ear pads.
            drawArc(color, 200f, 140f, false, p(0.12f, 0.30f), Size(0.76f * w, 0.56f * w), style = Stroke(w * 0.11f, cap = StrokeCap.Round))
            drawLine(color, p(0.16f, 0.50f), p(0.24f, 0.62f), w * 0.11f, StrokeCap.Round)
            drawLine(color, p(0.84f, 0.50f), p(0.76f, 0.62f), w * 0.11f, StrokeCap.Round)
        }
        GlyphKind.Gear -> {
            // Settings as two sliders.
            drawLine(color, p(0.16f, 0.34f), p(0.84f, 0.34f), s.width, StrokeCap.Round)
            drawLine(color, p(0.16f, 0.66f), p(0.84f, 0.66f), s.width, StrokeCap.Round)
            drawCircle(VoidKnob, 0.09f * w, p(0.36f, 0.34f))
            drawCircle(color, 0.09f * w, p(0.36f, 0.34f), style = s)
            drawCircle(VoidKnob, 0.09f * w, p(0.64f, 0.66f))
            drawCircle(color, 0.09f * w, p(0.64f, 0.66f), style = s)
        }
        GlyphKind.Back -> {
            val chevron = Path().apply { moveTo(0.62f * w, 0.20f * w); lineTo(0.32f * w, 0.50f * w); lineTo(0.62f * w, 0.80f * w) }
            drawPath(chevron, color, style = s)
        }
        GlyphKind.Talk -> {
            val bars = listOf(0.22f, 0.40f, 0.62f, 0.40f, 0.22f)
            bars.forEachIndexed { i, h ->
                val x = (0.22f + i * 0.14f) * w
                drawLine(color, Offset(x, (0.5f - h / 2) * w), Offset(x, (0.5f + h / 2) * w), w * 0.08f, StrokeCap.Round)
            }
        }
    }
}
