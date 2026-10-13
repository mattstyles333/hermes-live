package com.folio.hermeslive.hooks

import com.folio.hermeslive.audio.CascadeAudioFactory
import java.net.InetAddress
import okhttp3.Dns

/**
 * Build-type seams, staging flavour (release-like, emulator only): the fake Hermes address
 * ([TestServer]: `http://10.0.2.2:<port>` works), and nothing else. No audio override: staging
 * uses the mic and speaker like release.
 */
object BuildHooks {
    val dns: Dns = Dns { host ->
        if (host.equals(TestServer.HOST, ignoreCase = true)) listOf(InetAddress.getByName(TestServer.EMULATOR_HOST)) else Dns.SYSTEM.lookup(host)
    }

    fun serverUrl(raw: String): String = TestServer.alias(raw)

    val cascadeAudio: CascadeAudioFactory? get() = null
}
