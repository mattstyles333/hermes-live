package com.folio.hermeslive.hooks

import com.folio.hermeslive.audio.CascadeAudioFactory
import okhttp3.Dns
import okhttp3.EventListener

/**
 * Build-type seams, release flavour: none. The system DNS, and the phone's mic and speaker.
 * Debug builds have a test version of this object (`src/debug`); staging is release-like (`src/staging`).
 */
object BuildHooks {
    val dns: Dns = Dns.SYSTEM

    /** No network timing in release. */
    val eventListenerFactory: EventListener.Factory = EventListener.Factory { EventListener.NONE }

    /** The Hermes address as typed: no test address in release. */
    fun serverUrl(raw: String): String = raw

    /** The cascade's audio override: never in release. */
    val cascadeAudio: CascadeAudioFactory? get() = null
}
