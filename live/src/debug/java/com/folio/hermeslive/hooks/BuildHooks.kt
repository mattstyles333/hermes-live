package com.folio.hermeslive.hooks

import com.folio.hermeslive.audio.CascadeAudioFactory
import java.net.InetAddress
import okhttp3.Dns

/**
 * Build-type seams, debug flavour: test overrides for the emulator end-to-end tests (S-F2b). The
 * instrumentation runs in the app's process, so a test sets these directly; nothing here reads
 * a file, an argument or the network. Release and staging have a fixed version with no overrides.
 *
 * - The fake Hermes (`tools/fake-hermes`, on the host): sign in to `http://10.0.2.2:18119`
 *   (typed or from a test). [serverUrl] turns it into `http://fake-hermes.ts.net:18119`, which
 *   passes `HermesEndpoint` and the cleartext rule unchanged, and [dns] maps that name back to
 *   10.0.2.2. Release has neither, so release behaviour is unchanged.
 * - [hosts]: more name → IP mappings for the app's HTTP client.
 * - [cascadeAudio]: the next cascade call's audio, e.g. `CorpusAudioSource` in and a
 *   [CapturingAudioOut] out (see `live/src/androidTest/README.md`, "Debug hooks").
 */
object BuildHooks {
    const val FAKE_HERMES_HOST = TestServer.HOST

    @Volatile var hosts: Map<String, String> = emptyMap()

    val dns: Dns = Dns { host ->
        val h = host.lowercase()
        when {
            h == FAKE_HERMES_HOST -> listOf(InetAddress.getByName(TestServer.EMULATOR_HOST))
            else -> hosts[h]?.let { listOf(InetAddress.getByName(it)) } ?: Dns.SYSTEM.lookup(host)
        }
    }

    /** `http://10.0.2.2[:port][/path]` → `http://fake-hermes.ts.net[:port][/path]`; anything else as typed. */
    fun serverUrl(raw: String): String = TestServer.alias(raw)

    @Volatile var cascadeAudio: CascadeAudioFactory? = null

    /** Back to the defaults (call in a test's teardown). */
    fun reset() {
        hosts = emptyMap()
        cascadeAudio = null
    }
}
