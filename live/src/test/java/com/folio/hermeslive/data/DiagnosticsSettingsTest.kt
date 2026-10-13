package com.folio.hermeslive.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.folio.hermeslive.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DiagnosticsSettingsTest {
    private val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("diag_test", Context.MODE_PRIVATE)
    private val store = SettingsStore(prefs)

    @Test fun debugBuildsCarryNoEndpoint() {
        assertEquals("", BuildConfig.DIAGNOSTICS_URL)
        assertNull("no built-in URL and no override: off", SettingsStore.diagnosticsEndpoint(prefs, BuildConfig.DIAGNOSTICS_URL))
    }

    @Test fun switchOffWinsOverAnyUrl() {
        store.update { it.copy(diagnostics = false, diagnosticsUrl = "https://o.example.com") }
        assertNull(SettingsStore.diagnosticsEndpoint(prefs, "https://logs.example.com"))
    }

    @Test fun overrideWinsThenDefault() {
        assertEquals("https://logs.example.com", SettingsStore.diagnosticsEndpoint(prefs, "https://logs.example.com"))
        store.update { it.copy(diagnosticsUrl = "https://o.example.com") }
        assertEquals("https://o.example.com", SettingsStore.diagnosticsEndpoint(prefs, "https://logs.example.com"))
        assertEquals("https://o.example.com", SettingsStore(prefs).settings.value.diagnosticsUrl)
    }
}
