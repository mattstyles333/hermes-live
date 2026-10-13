package com.folio.hermes.telemetry

import java.nio.file.Files
import java.util.UUID
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * One real send to a collector, only when `TELEMETRY_LIVE_URL` is set (never in CI; never commit
 * the URL). Prints the run id to look up in the collector.
 */
class OtlpLiveTest {
    @Test fun sendsOneEventAndOneCrashToARealCollector() {
        val raw = System.getenv("TELEMETRY_LIVE_URL")
        assumeTrue("TELEMETRY_LIVE_URL not set", !raw.isNullOrBlank())
        val runId = UUID.randomUUID().toString().take(8)
        val dir = Files.createTempDirectory("telemetry-live").toFile()
        val core = TelemetryCore(
            store = PendingStore(dir),
            resource = mapOf(
                "service.name" to "hermes-live",
                "service.version" to "1.0.2-e2e",
                "deployment.environment" to "phone",
                "device.model" to "jvm-test",
                "os.version" to "jvm",
                "app.install_id" to "e2e-$runId",
            ),
            endpoint = { DiagnosticsEndpoint.logsUrl(raw) },
            client = OkHttpClient(),
        )
        core.add(Records.record(System.currentTimeMillis(), Severity.Info, "telemetry.e2e", mapOf("run_id" to runId, "ok" to true, "count" to 1)))
        core.writeCrash("main", IllegalStateException("e2e crash for run $runId", RuntimeException("cause with Bearer abc123")))
        val result = core.flush(force = true)
        println("telemetry e2e run_id=$runId result=$result")
        dir.deleteRecursively()
        assertTrue("$result", result is FlushResult.Sent)
    }
}
