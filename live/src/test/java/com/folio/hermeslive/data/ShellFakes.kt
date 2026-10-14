package com.folio.hermeslive.data

import android.content.Context
import com.folio.hermes.openrouter.OpenRouter
import com.folio.hermes.openrouter.OpenRouterConfig
import com.folio.hermes.telemetry.TelemetrySink
import com.folio.hermeslive.service.Briefing
import com.folio.hermeslive.service.OffCallHooks
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/** OpenRouter over HTTP that never leaves the test: `/key` answers [keyStatus], `/audio/speech` a short PCM clip. */
class FakeOpenRouterServer(var keyStatus: Int = 200) : Interceptor {
    val requests = mutableListOf<okhttp3.Request>()

    @Synchronized override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        requests += request
        val builder = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("x")
        return if (request.url.encodedPath.endsWith("/audio/speech")) {
            builder.code(200).body(ByteArray(480).toResponseBody("audio/pcm;rate=24000;channels=1".toMediaType())).build()
        } else {
            builder.code(keyStatus).body("{\"data\":{}}".toResponseBody("application/json".toMediaType())).build()
        }
    }

    fun client(): OkHttpClient = OkHttpClient.Builder().addInterceptor(this).build()
}

class CountingRuntime : CascadeRuntime {
    var closed = false
    override fun close() {
        closed = true
    }
}

class CountingFactory : CascadeFactory {
    val built = mutableListOf<CountingRuntime>()
    override fun create(env: CascadeEnvironment): CascadeRuntime = CountingRuntime().also { built += it }
}

class FakeSpeechModels(initial: List<SpeechModelStatus> = emptyList()) : SpeechModels {
    private val _status = MutableStateFlow(initial)
    override val status: StateFlow<List<SpeechModelStatus>> = _status.asStateFlow()
    val downloads = AtomicInteger()
    override suspend fun download(id: String): Boolean {
        downloads.incrementAndGet()
        _status.value = _status.value.map { if (it.id == id) it.copy(state = ModelState.Ready, bytesDone = it.bytesTotal) else it }
        return true
    }
    override fun delete(id: String) = Unit
}

class FakeOffCallHooks(initialRunning: Int = 0, var briefing: Briefing? = null) : OffCallHooks {
    val running = MutableStateFlow(initialRunning)
    override val tasksRunning: StateFlow<Int> = this.running.asStateFlow()
    val held = AtomicInteger()
    val released = AtomicInteger()
    val briefed = mutableListOf<String?>()
    override fun holdGateway(): AutoCloseable {
        held.incrementAndGet()
        return AutoCloseable { released.incrementAndGet() }
    }
    override suspend fun briefing(taskId: String?): Briefing? {
        briefed += taskId
        return briefing
    }
}

class FakeHost(
    override val cascadeFactory: CountingFactory = CountingFactory(),
    override val speechModels: FakeSpeechModels = FakeSpeechModels(),
    override val offCallHooks: FakeOffCallHooks = FakeOffCallHooks(),
) : CascadeHost

class FakeNetwork(var online: Boolean = true, var metered: Boolean = false) : NetworkHints {
    override fun online(): Boolean = online
    override fun metered(): Boolean = metered
}

/** A [VoiceShell] on fakes, in its own prefs and directory, counting every OpenRouter client it builds. */
class TestShell(context: Context, name: String, scope: CoroutineScope, val sink: TelemetrySink = SafeTelemetry(TelemetrySink.None)) {
    val server = FakeOpenRouterServer()
    val host = FakeHost()
    val network = FakeNetwork()
    val openRouters = AtomicInteger()
    val box = SoftwareSecretBox()
    val settings = SettingsStore(context.getSharedPreferences("$name-settings", Context.MODE_PRIVATE))
    val keys = OpenRouterKeyStore(context.getSharedPreferences("$name-key", Context.MODE_PRIVATE), box, sink)

    /** Grok's xAI key (its own box and prefs) and a checker that never leaves the test. */
    val xaiKeys = OpenRouterKeyStore(context.getSharedPreferences("$name-xai", Context.MODE_PRIVATE), SoftwareSecretBox(), sink, item = "xai_key")
    var xaiAnswer: KeyCheckResult = KeyCheckResult.Valid

    /** Signed in to Hermes (GPT-Live's parallel agents need it); signed out by default. */
    var signedIn = false
    val xaiChecks = AtomicInteger()
    private val config = OpenRouterConfig(baseUrl = "https://openrouter.example/api/v1", retryBackoffMs = 1)
    val shell = VoiceShell(
        context = context,
        settings = settings,
        keys = keys,
        client = server.client(),
        host = host,
        network = network,
        telemetry = sink,
        scope = scope,
        openRouterConfig = config,
        openRouters = { key ->
            openRouters.incrementAndGet()
            OpenRouter(config, key = key, client = server.client(), telemetry = sink)
        },
        dirs = CascadeDirs(java.io.File(context.noBackupFilesDir, "cascade-$name"), java.io.File(context.cacheDir, "share-$name")),
        xaiKeys = xaiKeys,
        xaiChecker = KeyChecker { xaiChecks.incrementAndGet(); xaiAnswer },
        signedIn = { signedIn },
    )
}
