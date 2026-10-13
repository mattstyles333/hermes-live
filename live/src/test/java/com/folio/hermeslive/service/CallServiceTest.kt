package com.folio.hermeslive.service

import android.Manifest
import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.folio.hermeslive.call.CallEngine
import com.folio.hermeslive.call.CallPhase
import com.folio.hermeslive.call.FakeAudio
import com.folio.hermeslive.call.FakeEffects
import com.folio.hermeslive.call.FakeLink
import com.folio.hermeslive.call.FakeMemory
import com.folio.hermeslive.call.FakeTransport
import com.folio.hermeslive.data.LiveSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CallServiceTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val transports = mutableListOf<FakeTransport>()
    private val engine = CallEngine(
        scope = scope,
        settings = { LiveSettings() },
        links = { FakeLink() },
        transports = { events -> FakeTransport(events).also { transports += it } },
        audio = FakeAudio(),
        effects = FakeEffects(),
        memory = FakeMemory(),
    )

    @After fun tearDown() = scope.cancel()

    private fun startService(): CallService {
        val controller = Robolectric.buildService(CallService::class.java, Intent(app, CallService::class.java).setAction(CallService.ACTION_START))
        val service = controller.get()
        service.engineProvider = { engine }
        controller.create().startCommand(0, 1)
        shadowOf(Looper.getMainLooper()).idle()
        return service
    }

    @Test fun aStartedCallKeepsTheServiceInTheForeground() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val service = startService()
        assertEquals(CallPhase.Connecting, engine.state.value.phase)
        assertFalse("the service stopped itself as the call began", shadowOf(service).isStoppedBySelf)
        assertNotNull(shadowOf(service).lastForegroundNotification)
        assertFalse(shadowOf(service).isForegroundStopped)

        transports.single().started()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(CallPhase.Live, engine.state.value.phase)
        assertFalse(shadowOf(service).isStoppedBySelf)

        engine.end()
        transports.single().closedBy("client_closed", 3.0)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(CallPhase.Ended, engine.state.value.phase)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test fun withoutTheMicNothingStarts() {
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val service = startService()
        assertEquals(CallPhase.Idle, engine.state.value.phase)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(transports.isEmpty())
    }
}
