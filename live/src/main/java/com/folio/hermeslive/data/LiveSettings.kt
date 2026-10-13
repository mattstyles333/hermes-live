package com.folio.hermeslive.data

import com.folio.hermes.live.BridgeSettings
import com.folio.hermeslive.call.FallbackRoute
import com.folio.hermeslive.call.GapSettings

/** Hermes Live's settings. Defaults are the shipped behaviour (see LiveSettingsTest). */
data class LiveSettings(
    /** Pause other players while someone talks; let them play while Hermes works. */
    val musicInGaps: Boolean = true,
    /** On the speaker, duck music in the gaps instead of resuming it fully. */
    val duckOnSpeaker: Boolean = true,
    /** Short tones for connected / muted / ended. */
    val earcons: Boolean = true,
    /** Light haptics with the earcons. */
    val haptics: Boolean = true,
    /** Answer approvals by voice ("approve" / "deny"). Off: approvals need a tap. */
    val spokenApprovals: Boolean = false,
    /** Hang up after this many minutes with nobody talking and no Hermes work. 0 = never. */
    val idleTimeoutMinutes: Int = 3,
    /** Longest call, minutes. 0 = no cap. */
    val callCapMinutes: Int = 30,
    /** Opening the app starts talking (when signed in). */
    val startOnOpen: Boolean = true,
    /** Keep the screen on during a call. */
    val keepScreenOn: Boolean = false,
    /** Route without a headset, and after one disconnects. */
    val fallbackRoute: FallbackRoute = FallbackRoute.Speaker,
) {
    fun gapSettings(): GapSettings = GapSettings(enabled = musicInGaps, duckOnSpeaker = duckOnSpeaker)

    fun bridgeSettings(): BridgeSettings = BridgeSettings(
        idleHangUpMs = idleTimeoutMinutes.coerceAtLeast(0) * 60_000L,
        callCapMs = callCapMinutes.coerceAtLeast(0) * 60_000L,
        goodbyeMaxMs = GOODBYE_MAX_MS,
        idleWarningMs = if (idleTimeoutMinutes > 0) IDLE_WARNING_MS else 0L,
    )

    companion object {
        const val GOODBYE_MAX_MS = 4_000L
        const val IDLE_WARNING_MS = 30_000L
        val IDLE_CHOICES = listOf(0, 1, 2, 3, 5, 10)
        val CAP_CHOICES = listOf(0, 10, 15, 30, 45, 60)

        /**
         * Placeholder for the server field. The repo and its Releases are public: no real host
         * is ever built in; the user types their own Hermes dashboard address.
         */
        const val SERVER_HINT = "https://hermes.example.com"

        /** Tailnet shape (cleartext is allowed for *.ts.net only). */
        const val TAILNET_HINT = "http://<machine>.<tailnet>.ts.net:9119"
    }
}
