package com.folio.hermes.telemetry

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * Crash and error reporting for Hermes Live and Folio, as OTLP logs (SigNoz or any OTLP/HTTP
 * collector). No content, ever: callers pass event names and safe attributes (codes, enum
 * reasons, durations, counts, booleans), and everything passes through [Scrub].
 *
 * - [install] first thing in `Application.onCreate`. Idempotent; never throws.
 * - Uncaught exceptions are written synchronously to `filesDir/telemetry/pending/`, then the
 *   previous handler runs (the app still crashes as before).
 * - With an endpoint, previous exits (native crashes, ANRs, low-memory kills…) are reported once.
 * - [event] / [warn] / [error] queue in memory (≤200); a background thread sends every 30 s
 *   and when the app leaves the screen.
 * - Off when [install]'s endpoint is null or blank: no request, no exit scan; only a crash file
 *   is written (capped at 50, dropped after 3 days).
 *
 * Before [install] (and in unit tests that never call it) every call is a no-op.
 */
object Telemetry {
    @Volatile private var core: TelemetryCore? = null
    @Volatile private var executor: ScheduledExecutorService? = null

    val isInstalled: Boolean get() = core != null

    /**
     * Start telemetry for [service] ("hermes-live" or "folio"). [endpoint] is asked on the
     * background thread at every send: return the effective URL (see [DiagnosticsEndpoint.effective]).
     */
    @Synchronized
    fun install(context: Context, service: String, version: String, endpoint: () -> String?): Boolean {
        if (core != null) return false
        return try {
            val app = context.applicationContext ?: context
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val installId = prefs.getString(KEY_INSTALL_ID, null) ?: UUID.randomUUID().toString().also {
                prefs.edit().putString(KEY_INSTALL_ID, it).apply()
            }
            val c = TelemetryCore(
                store = PendingStore(File(app.filesDir, "telemetry/pending")),
                resource = resource(service, version, installId),
                endpoint = { DiagnosticsEndpoint.logsUrl(endpoint()) },
                client = client(),
                exits = AndroidExitSource(app),
                marks = PrefsExitMarks(prefs),
            )
            core = c
            installCrashHandler(c)
            val ex = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "telemetry").apply { isDaemon = true } }
            executor = ex
            ex.schedule({ safeFlush(false) }, FIRST_FLUSH_DELAY_S, TimeUnit.SECONDS)
            ex.scheduleWithFixedDelay({ safeFlush(false) }, FLUSH_PERIOD_S, FLUSH_PERIOD_S, TimeUnit.SECONDS)
            app.registerComponentCallbacks(object : ComponentCallbacks2 {
                override fun onTrimMemory(level: Int) {
                    // UI_HIDDEN: the app just left the screen. Send before Android freezes it.
                    @Suppress("DEPRECATION")
                    if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) flushAsync()
                }

                override fun onConfigurationChanged(newConfig: Configuration) = Unit

                @Deprecated("Deprecated in Java")
                override fun onLowMemory() = Unit
            })
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** Something worth counting happened ([name] like "call.start"). */
    fun event(name: String, attrs: Map<String, Any?> = emptyMap()) = add(Severity.Info, name, attrs, null)

    /** Something went wrong but the app carried on. [throwable]'s message is scrubbed; pass none if it may echo content. */
    fun warn(name: String, throwable: Throwable? = null, attrs: Map<String, Any?> = emptyMap()) = add(Severity.Warn, name, attrs, throwable)

    fun error(name: String, throwable: Throwable? = null, attrs: Map<String, Any?> = emptyMap()) = add(Severity.Error, name, attrs, throwable)

    /** Send now and wait (call off the main thread). Ignores the backoff. Off before [install]. */
    fun flush(): FlushResult = try {
        core?.flush(force = true) ?: FlushResult.Off
    } catch (t: Throwable) {
        FlushResult.Failed(-1, t.javaClass.simpleName)
    }

    /** Send soon on the telemetry thread. */
    fun flushAsync() {
        try {
            executor?.execute { safeFlush(false) }
        } catch (t: Throwable) {
            // shut down
        }
    }

    /** Tests only: forget the installed instance and stop its thread. */
    @Synchronized
    fun resetForTests() {
        executor?.shutdownNow()
        executor = null
        core = null
    }

    private fun add(severity: Severity, name: String, attrs: Map<String, Any?>, throwable: Throwable?) {
        val c = core ?: return
        try {
            c.add(Records.record(System.currentTimeMillis(), severity, name, attrs, throwable))
        } catch (t: Throwable) {
            // never let reporting break the caller
        }
    }

    private fun safeFlush(force: Boolean) {
        try {
            core?.flush(force)
        } catch (t: Throwable) {
            // next round
        }
    }

    private fun installCrashHandler(c: TelemetryCore) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                c.writeCrash(thread.name, throwable)
            } catch (t: Throwable) {
                // the crash still goes to the previous handler
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun resource(service: String, version: String, installId: String): Map<String, String> = linkedMapOf(
        "service.name" to service,
        "service.version" to version,
        "deployment.environment" to "phone",
        "device.model" to (Build.MODEL ?: "unknown"),
        "device.manufacturer" to (Build.MANUFACTURER ?: "unknown"),
        "os.name" to "android",
        "os.version" to "${Build.VERSION.RELEASE ?: "?"} (${Build.VERSION.SDK_INT})",
        "app.install_id" to installId,
    )

    private fun client(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private const val PREFS = "folio_telemetry"
    private const val KEY_INSTALL_ID = "install_id"
    private const val FIRST_FLUSH_DELAY_S = 5L
    private const val FLUSH_PERIOD_S = 30L
}
