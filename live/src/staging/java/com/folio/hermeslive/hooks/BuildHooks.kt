package com.folio.hermeslive.hooks

import com.folio.hermeslive.audio.CascadeAudioFactory
import java.net.InetAddress
import okhttp3.Dns
import okhttp3.EventListener

/**
 * Build-type seams, staging flavour (release-like, emulator only): the fake Hermes address
 * ([TestServer]: `http://10.0.2.2:<port>` works), and nothing else. No audio override: staging
 * uses the mic and speaker like release.
 */
object BuildHooks {
    /** A test's network timing ([NetTimings]): host, path and phase times of each call while set. */
    @Volatile var netEvents: EventListener.Factory? = null

    val eventListenerFactory: EventListener.Factory = EventListener.Factory { call -> netEvents?.create(call) ?: EventListener.NONE }

    val dns: Dns = Dns { host ->
        if (host.equals(TestServer.HOST, ignoreCase = true)) listOf(InetAddress.getByName(TestServer.EMULATOR_HOST)) else Dns.SYSTEM.lookup(host)
    }

    fun serverUrl(raw: String): String = TestServer.alias(raw)

    val cascadeAudio: CascadeAudioFactory? get() = null
}
