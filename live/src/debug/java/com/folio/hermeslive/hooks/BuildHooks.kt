package com.folio.hermeslive.hooks

import com.folio.hermeslive.audio.CascadeAudioFactory
import java.net.InetAddress
import okhttp3.Dns

/**
 * Build-type seams, debug flavour: test overrides for the emulator end-to-end tests (S-F2b). The
 * instrumentation runs in the app's process, so a test sets these directly; nothing here reads
 * a file, an argument or the network. Release and staging have a fixed version with no overrides.
 *
 * - [hosts]: name → IP for the app's HTTP client. A fake Hermes on the host is
 *   `hosts = mapOf(FAKE_HERMES_HOST to "10.0.2.2")`, then sign in to
 *   `http://fake-hermes.ts.net:<port>`: the `ts.net` name passes `HermesEndpoint` and the
 *   cleartext rule (`network_security_config.xml`), and resolves to the emulator's host.
 * - [cascadeAudio]: the next cascade call's audio, e.g. `CorpusAudioSource` in and a
 *   [CapturingAudioOut] out (see `live/src/androidTest/README.md`, "Debug hooks").
 */
object BuildHooks {
    const val FAKE_HERMES_HOST = "fake-hermes.ts.net"

    @Volatile var hosts: Map<String, String> = emptyMap()

    val dns: Dns = Dns { host ->
        hosts[host.lowercase()]?.let { listOf(InetAddress.getByName(it)) } ?: Dns.SYSTEM.lookup(host)
    }

    @Volatile var cascadeAudio: CascadeAudioFactory? = null

    /** Back to the defaults (call in a test's teardown). */
    fun reset() {
        hosts = emptyMap()
        cascadeAudio = null
    }
}
