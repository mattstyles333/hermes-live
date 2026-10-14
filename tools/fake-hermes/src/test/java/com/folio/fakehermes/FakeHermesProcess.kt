package com.folio.fakehermes

import com.folio.hermes.HermesConfig
import com.folio.hermes.HermesEndpoint
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The Python fake Hermes as a subprocess on a free port, with its own temp data dir.
 *
 * The client reaches it as `http://fake-hermes.ts.net:<port>`: `HermesEndpoint.of` allows
 * cleartext only for tailnet names, and [client]'s DNS maps that name to 127.0.0.1. So the tests use
 * the public :hermes API exactly as the app does.
 */
class FakeHermesProcess(
    scenario: String = "scenarios/test.json",
    val dataDir: File = Files.createTempDirectory("fake-hermes").toFile(),
    private val extraArgs: List<String> = emptyList(),
) : Closeable {
    private val home = File(System.getProperty("fakehermes.dir") ?: error("fakehermes.dir not set"))
    private val scenarioFile = File(home, scenario)
    private var process: Process? = null
    var port: Int = 0
        private set

    val client: OkHttpClient = OkHttpClient.Builder()
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                if (hostname == HOST) listOf(InetAddress.getLoopbackAddress()) else Dns.SYSTEM.lookup(hostname)
        })
        .build()

    init {
        start()
    }

    /** Start (or start again on the same data dir, keeping the port when it was already chosen). */
    fun start() {
        val args = mutableListOf(
            "python3", "-B", File(home, "fake_hermes.py").absolutePath,
            "--port", port.toString(),
            "--scenario", scenarioFile.absolutePath,
            "--data-dir", dataDir.absolutePath,
        ) + extraArgs
        val p = ProcessBuilder(args).redirectErrorStream(true).start()
        process = p
        val reader = p.inputStream.bufferedReader()
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            val line = reader.readLine() ?: error("fake hermes exited: ${p.waitFor()}")
            val match = Regex("FAKE_HERMES_LISTENING port=(\\d+)").find(line)
            if (match != null) {
                port = match.groupValues[1].toInt()
                break
            }
        }
        check(port != 0) { "fake hermes did not start" }
        // Keep draining its output so a full pipe never blocks the server.
        Thread({ runCatching { reader.forEachLine { if (System.getenv("FAKE_HERMES_ECHO") != null) println("[fake] $it") } } }, "fake-hermes-out")
            .apply { isDaemon = true }.start()
    }

    /** `kill -9`, as a crash would. */
    fun kill() {
        process?.destroyForcibly()?.waitFor(5, TimeUnit.SECONDS)
        process = null
    }

    override fun close() {
        kill()
        dataDir.deleteRecursively()
    }

    val baseUrl: String get() = "http://$HOST:$port"

    /** Short timings so drops and stalls resolve in seconds (as FakeHermesServer.config()). */
    fun config(): HermesConfig = HermesConfig(
        endpoint = HermesEndpoint.of(baseUrl),
        pingIntervalMs = 200,
        heartbeatTimeoutMs = 1_000,
        rpcTimeoutMs = 3_000,
        promptSubmitTimeoutMs = 3_000,
        connectTimeoutMs = 3_000,
        reconnectBaseDelayMs = 50,
        reconnectMaxDelayMs = 300,
        turnInactivityTimeoutMs = 8_000,
        interruptSettleMs = 2_000,
        busyRetryWindowMs = 2_000,
        busyRetryIntervalMs = 50,
    )

    // ── admin and assertion API ────────────────────────────────────────────────────────────

    fun admin(method: String, path: String, body: String? = null): JsonElement {
        val builder = Request.Builder().url("$baseUrl$path")
        when (method) {
            "GET" -> builder.get()
            "DELETE" -> builder.delete()
            else -> builder.method(method, (body ?: "{}").toRequestBody("application/json".toMediaType()))
        }
        client.newCall(builder.build()).execute().use { r ->
            val text = r.body.string()
            check(r.isSuccessful) { "$method $path -> ${r.code}: $text" }
            return Json.parseToJsonElement(text.ifEmpty { "{}" })
        }
    }

    fun get(path: String): JsonObject = admin("GET", path).jsonObject
    fun post(path: String, body: String = "{}"): JsonObject = admin("POST", path, body).jsonObject

    companion object {
        const val HOST = "fake-hermes.ts.net"
        const val USER = "fake"
        const val PASSWORD = "fake-password"
    }
}
