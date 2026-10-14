package com.folio.hermeslive.ui.ondemand

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.folio.hermeslive.call.ondemand.OnDemandUi
import com.folio.hermeslive.call.ondemand.SessionView
import com.folio.hermeslive.ui.Ember
import com.folio.hermeslive.ui.Hairline
import com.folio.hermeslive.ui.PrintMute
import com.folio.hermeslive.ui.SmallStyle

/** The indicator's words for [ui], or null when nothing shows (save cost off, or the session open). */
fun sessionIndicatorText(ui: OnDemandUi): String? = SessionView.Listening.label.takeIf { ui.dimmed }

/**
 * A small pill while a realtime engine's paid session is closed to save cost (ondemand slice):
 * "Listening (on phone)". The screen dims its orb at the same time ([OnDemandUi.dimmed]).
 * Shared by the Grok and GPT-Live call screens.
 */
@Composable
fun OnDemandIndicator(ui: OnDemandUi, modifier: Modifier = Modifier) {
    val text = sessionIndicatorText(ui) ?: return
    Row(
        modifier.background(Hairline, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp).testTag("ondemand-indicator")
            .semantics {
                contentDescription = "$text. The voice session is closed to save cost; speak to reconnect."
                liveRegion = LiveRegionMode.Polite
            },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).background(Ember, CircleShape))
        Text(text, style = SmallStyle, color = PrintMute)
    }
}
