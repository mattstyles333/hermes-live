package com.folio.hermeslive.cascade

import com.folio.hermeslive.data.CascadeEnvironment
import com.folio.hermeslive.data.CascadeFactory
import com.folio.hermeslive.data.CascadeGate
import com.folio.hermeslive.data.CascadeRuntime
import com.folio.hermeslive.data.CascadeShell
import com.folio.hermeslive.data.KeyCheck
import com.folio.hermeslive.data.KeyStatus
import com.folio.hermeslive.data.LiveSettings
import com.folio.hermeslive.data.VoiceEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** A runtime built while signed out (or before the account was read back) is never kept. */
class AppRuntimeShellTest {
    @Test fun anEmptyRuntimeIsReleasedSoTheNextCallBuildsARealOne() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val real = TestRuntime(scope)
        var signedIn = false
        var builds = 0
        val shell = CascadeShell(
            CascadeGate({ LiveSettings(voiceEngine = VoiceEngine.Cascade) }, { KeyStatus(saved = true, check = KeyCheck.Valid) }),
            CascadeFactory { _ -> builds++; if (signedIn) real.runtime else CascadeRuntime.Empty },
        ) {
            val tmp = kotlin.io.path.createTempDirectory("shell").toFile()
            CascadeEnvironment({ com.folio.hermes.cascade.CascadeSettings() }, { null }, com.folio.hermeslive.data.CascadeStorage(com.folio.hermeslive.data.CascadeDirs(tmp, tmp)), com.folio.hermeslive.data.SpeechModels.None, com.folio.hermes.telemetry.TelemetrySink.None)
        }
        assertNull(shell.appRuntime())
        assertNull("not cached", shell.existing())
        signedIn = true
        assertSame(real.runtime, shell.appRuntime())
        assertEquals(2, builds)
        scope.cancel()
    }
}
