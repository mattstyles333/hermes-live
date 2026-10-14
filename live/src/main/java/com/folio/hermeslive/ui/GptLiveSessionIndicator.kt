package com.folio.hermeslive.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.folio.hermeslive.call.ondemand.OnDemandUi
import com.folio.hermeslive.call.ondemand.SessionView

/** The orb's alpha while the paid session is closed. */
const val DIMMED_ALPHA = 0.45f

/**
 * GPT-Live's on-demand session state as a small pill ("Listening (on phone)" while closed,
 * "Connecting…" while it reopens; nothing while connected or when on-demand is off). The hub may
 * swap it for the ondemand slice's shared `OnDemandIndicator(ui)`.
 */
@Composable
fun GptLiveSessionIndicator(ui: OnDemandUi, modifier: Modifier = Modifier) {
    val label = sessionLabel(ui)
    AnimatedVisibility(label != null, modifier, enter = fadeIn(), exit = fadeOut()) {
        Text(
            label.orEmpty(),
            style = SmallStyle,
            color = PrintMute,
            modifier = Modifier.padding(top = 8.dp).background(Hairline, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 5.dp)
                .testTag("ondemand-indicator").semantics { contentDescription = label.orEmpty() },
        )
    }
}

/** What the pill says, or null (connected, or on-demand off). */
fun sessionLabel(ui: OnDemandUi): String? = when {
    !ui.enabled -> null
    ui.view == SessionView.Connected -> null
    else -> ui.view.label
}
