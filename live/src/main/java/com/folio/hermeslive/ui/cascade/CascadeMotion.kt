package com.folio.hermeslive.ui.cascade

import android.animation.ValueAnimator
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * How much the call screen may move.
 * - [reduceMotion]: no orbiting, sparks, trails or expanding rings; only fades and static
 *   satellites. On when the system animator scale is 0 ("Remove animations") or animators are
 *   off, or when the app asks for it.
 * - [durationScale]: the system animator duration scale; the scene's own clock runs at
 *   1 / [durationScale] (Compose's own animations already follow it).
 */
@Immutable
data class CascadeMotion(val reduceMotion: Boolean = false, val durationScale: Float = 1f) {
    companion object {
        /** Reduce motion: fades only. */
        val Reduced = CascadeMotion(reduceMotion = true)

        /** From the system settings, plus the app's own switch. */
        fun fromSystem(animatorScale: Float, animatorsEnabled: Boolean, appReduceMotion: Boolean): CascadeMotion {
            val off = appReduceMotion || !animatorsEnabled || animatorScale <= 0f
            return CascadeMotion(reduceMotion = off, durationScale = if (animatorScale > 0f) animatorScale else 1f)
        }
    }
}

/** Reads the system animator scale once per [appReduceMotion] value. */
@Composable
fun rememberCascadeMotion(appReduceMotion: Boolean = false): CascadeMotion {
    val context = LocalContext.current
    return remember(appReduceMotion) {
        val scale = runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f)
        CascadeMotion.fromSystem(scale, ValueAnimator.areAnimatorsEnabled(), appReduceMotion)
    }
}
