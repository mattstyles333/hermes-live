package com.folio.hermeslive.call

import kotlinx.coroutines.flow.StateFlow

/**
 * The paid GPT-Live voice session inside one call, closed and reopened on demand (the local
 * interface until the shared on-demand controller's `RealtimeLink` lands; [CallEngine]
 * implements it). Closing ends the WebRTC session (`session.close`) but keeps the call: the
 * audio mode and route, the Hermes link, the agents and their tasks. Reopening creates a new
 * session with history: the recent transcript plus a "Tasks running: …" note, at most 24
 * messages.
 *
 * Pre-roll: WebRTC sends only the live microphone track (no API takes recorded audio), so what
 * the user said while the session was closed is passed as text from on-device speech-to-text and
 * told to the voice once the new session starts.
 */
interface GptLiveSessionControl {
    /** A voice session is open (from `session.started` until it closes). */
    val sessionOpen: StateFlow<Boolean>

    /** Results and requests from agent tasks waiting to be told: a reason to reopen. */
    val pendingResults: StateFlow<Int>

    /** From the last [reopenSession] to `session.started`, ms; null before the first. */
    val lastReopenMs: Long?

    /** Close the session and keep the call. False when it is not open and idle enough to close. */
    fun closeSession(): Boolean

    /** Open a new session in the same call. [preRoll]: the user's words meanwhile, as text. False unless closed by [closeSession]. */
    fun reopenSession(preRoll: String? = null): Boolean
}
