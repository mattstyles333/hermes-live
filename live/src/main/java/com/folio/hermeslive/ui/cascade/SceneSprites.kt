package com.folio.hermeslive.ui.cascade

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import com.folio.hermeslive.ui.EndRed
import com.folio.hermeslive.ui.Ember
import com.folio.hermeslive.ui.PrintInk

/** The one success tone (v1 visuals). */
internal val Success = Color(0xFF8FB98A)

/** The orb's hot centre. */
internal val EmberHot = Color(0xFFFFDEB4)

/**
 * Gradients rendered once into bitmaps and drawn scaled, plus cached strokes and tints, so the
 * frame loop draws without allocating shaders, brushes or strokes.
 */
internal class SceneSprites(dp: Float) {
    /** Bill: hot centre, ember body, transparent edge (the prototype's orb gradient). */
    val emberOrb: ImageBitmap = radial(
        SIZE,
        floatArrayOf(0f, 0.55f, 1f),
        arrayOf(EmberHot.copy(alpha = 0.95f), Ember.copy(alpha = 0.55f), Ember.copy(alpha = 0f)),
        highlight = EmberHot.copy(alpha = 0.35f),
    )

    /** The listening well: cooler ink. */
    val inkOrb: ImageBitmap = radial(
        SIZE,
        floatArrayOf(0f, 0.6f, 1f),
        arrayOf(PrintInk.copy(alpha = 0.9f), PrintInk.copy(alpha = 0.25f), PrintInk.copy(alpha = 0f)),
        highlight = PrintInk.copy(alpha = 0.25f),
    )

    /** A white soft disc, tinted per use. */
    val glow: ImageBitmap = radial(128, floatArrayOf(0f, 1f), arrayOf(Color.White.copy(alpha = 0.9f), Color.White.copy(alpha = 0f)), highlight = null)

    /** The ambient wash behind everything. */
    val ambient: ImageBitmap = radial(128, floatArrayOf(0f, 1f), arrayOf(Ember.copy(alpha = 0.07f), Color.Transparent), highlight = null)

    val tintEmber = ColorFilter.tint(Ember, BlendMode.Modulate)
    val tintOk = ColorFilter.tint(Success, BlendMode.Modulate)
    val tintInk = ColorFilter.tint(PrintInk, BlendMode.Modulate)
    val tintRed = ColorFilter.tint(EndRed, BlendMode.Modulate)

    val hair = Stroke(1f * dp)
    val ring = Stroke(1.5f * dp)
    val ripple = Stroke(2f * dp)
    val pauseTrack = Stroke(3f * dp)
    val pauseFill = Stroke(3f * dp, cap = StrokeCap.Round)
    val arc = Stroke(1.5f * dp, cap = StrokeCap.Round)

    private fun radial(size: Int, stops: FloatArray, colors: Array<Color>, highlight: Color?): ImageBitmap {
        val image = ImageBitmap(size, size)
        val canvas = Canvas(image)
        val half = size / 2f
        val paint = Paint().apply {
            shader = RadialGradientShader(Offset(half, half), half, colors.toList(), stops.toList())
        }
        canvas.drawCircle(Offset(half, half), half, paint)
        if (highlight != null) {
            // The prototype's off-centre hot spot (up and to the left).
            val c = Offset(half * 0.84f, half * 0.80f)
            val r = half * 0.42f
            val hi = Paint().apply { shader = RadialGradientShader(c, r, listOf(highlight, highlight.copy(alpha = 0f))) }
            canvas.drawCircle(c, r, hi)
        }
        return image
    }

    private companion object {
        const val SIZE = 384
    }
}
