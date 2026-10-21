package com.folio.hermeslive.cascade

import com.folio.hermes.ApprovalChoice
import com.folio.hermes.ApprovalRequest
import com.folio.hermes.BusyMode
import com.folio.hermes.ClarifyRequest
import com.folio.hermes.GatewayEvent
import com.folio.hermes.TurnUpdate
import com.folio.hermes.cascade.AudioClip
import com.folio.hermes.cascade.CascadeCore
import com.folio.hermes.cascade.CascadePorts
import com.folio.hermes.cascade.CascadeSettings
import com.folio.hermes.cascade.PhraseBank
import com.folio.hermes.cascade.Transcript
import com.folio.hermes.cascade.ports.HermesPort
import com.folio.hermes.cascade.ports.MemoryStore
import com.folio.hermes.cascade.ports.OpenRouterPort
import com.folio.hermes.cascade.ports.SttEvent
import com.folio.hermes.cascade.registry.SessionProbe
import com.folio.hermes.openrouter.FailureKind
import com.folio.hermes.openrouter.JevAnswer
import com.folio.hermes.openrouter.JevQuestion
import com.folio.hermes.openrouter.OpenRouterResult
import com.folio.hermes.openrouter.SpeechFormat
import com.folio.hermes.telemetry.TelemetrySink
import com.folio.hermeslive.audio.AudioSource
import com.folio.hermeslive.audio.PlaybackMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.JsonObject

/** OpenRouter that never leaves the test: Jev unavailable, no writer, short silent clips. */
class QuietOpenRouter : OpenRouterPort {
    val clips = mutableListOf<String>()
    override suspend fun jev(state: JsonObject, questions: Map<String, JevQuestion>, timeoutMs: Long): OpenRouterResult<Map<String, JevAnswer>> =
        OpenRouterResult.Failed(FailureKind.NoKey)
    override fun sentences(system: String, prompt: String): Flow<String> = emptyFlow()
    override suspend fun write(system: String, prompt: String): OpenRouterResult<String> = OpenRouterResult.Failed(FailureKind.NoKey)
    override suspend fun clip(text: String): OpenRouterResult<AudioClip> {
        clips += text
        return OpenRouterResult.Ok(AudioClip(ByteArray(480), SpeechFormat.Pcm()))
    }
    override fun isCached(text: String): Boolean = true
}

/** Hermes with no tasks. */
class IdleHermes : HermesPort {
    override fun open(taskId: String, label: String, storedSessionId: String?) = Unit
    override fun submit(taskId: String, text: String, voiceContext: String, queued: Boolean, interruptFirst: Boolean): Flow<TurnUpdate> = emptyFlow()
    override suspend fun interrupt(taskId: String): Boolean = false
    override suspend fun busyMode(): BusyMode? = null
    override val sessions: Flow<Pair<String, String>> = emptyFlow()
    override val connected: Flow<Boolean> = flowOf(true)
}

class FakeGateway : GatewayControl {
    var connects = 0
    var disconnects = 0
    val approved = mutableListOf<Pair<String, ApprovalChoice>>()
    val clarified = mutableListOf<Pair<String, Map<String, String?>>>()
    override val events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 16)
    override fun connect() { connects++ }
    override fun disconnect() { disconnects++ }
    override suspend fun answerApproval(request: ApprovalRequest, choice: ApprovalChoice) { approved += request.id to choice }
    override suspend fun answerClarify(request: ClarifyRequest, answers: Map<String, String?>) { clarified += request.id to answers }
}

/** A runtime on fakes: the real actor and slots, a fake gateway, an in-memory store. */
class TestRuntime(
    scope: CoroutineScope,
    settings: CascadeSettings = CascadeSettings(),
    hermes: HermesPort = IdleHermes(),
    clock: com.folio.hermes.cascade.ports.Clock = com.folio.hermes.cascade.ports.Clock.System,
) {
    val gateway = FakeGateway()
    val openRouter = QuietOpenRouter()
    val running = MutableStateFlow(0)
    val stt = SttSlot()
    val audio = AudioSlot()
    val core = CascadeCore.create(
        scope = scope,
        ports = CascadePorts(openRouter, hermes, { _, _ -> SessionProbe.NeverPrompted }, audio, MemoryStore(), clock = clock, stt = stt),
        bank = PhraseBank.load(),
        settings = { settings },
        telemetry = TelemetrySink.None,
    )
    val runtime = CascadeAppRuntime(core, stt, audio, gateway, openRouter, { settings }, running, scope)
}

/** A [CallSpeech] the test drives: events by hand, counts start and release. */
class FakeSpeech(val source: AudioSource, val highAccuracy: Boolean) : CallSpeech {
    val channel = Channel<SttEvent>(Channel.UNLIMITED)
    var started = 0
    var released = 0
    val holds = mutableListOf<Boolean>()
    val dropped = mutableListOf<String>()
    override val events: Flow<SttEvent> = channel.receiveAsFlow()
    override suspend fun accurate(turnId: String): Transcript? = null
    override fun drop(turnId: String) { dropped += turnId }
    override fun start() { started++; source.open() }
    override fun release() { released++ }
    override fun holdToTalk(pressed: Boolean) { holds += pressed }
    override val inputLevelDb: Double = -40.0
}

class FakeSpeechFactory(var failWith: Exception? = null) : CallSpeechFactory {
    val built = mutableListOf<FakeSpeech>()
    val hotwords = mutableListOf<() -> List<String>>()
    val clouds = mutableListOf<com.folio.hermeslive.stt.CloudStt?>()
    val fallbacks = mutableListOf<(com.folio.hermeslive.stt.LiveFallback) -> Unit>()
    override fun create(
        source: AudioSource, playback: PlaybackMonitor, settings: () -> CascadeSettings, hotwords: () -> List<String>,
        speech: com.folio.hermeslive.data.LiveSettings, cloud: com.folio.hermeslive.stt.CloudStt?, onFallback: (com.folio.hermeslive.stt.LiveFallback) -> Unit,
    ): CallSpeech {
        failWith?.let { throw it }
        this.hotwords += hotwords
        clouds += cloud
        fallbacks += onFallback
        val big = com.folio.hermeslive.data.SpeechModelChoice.accurate(speech) != com.folio.hermeslive.stt.SttCatalog.parakeet110m
        return FakeSpeech(source, big).also { built += it }
    }
}

/** A source of constant non-zero samples (so muting is visible). */
class ToneSource : AudioSource {
    var opened = 0
    override var onRouteChanged: (() -> Unit)? = null
    override fun open() { opened++ }
    override fun read(buffer: ShortArray): Int { buffer.fill(1000); return buffer.size }
    override fun close() = Unit
}

/** Bill's output for JVM tests: plays nothing, is never playing. */
class SilentOut : com.folio.hermes.cascade.ports.AudioOut, PlaybackMonitor {
    override suspend fun play(clip: AudioClip): Long = clip.durationMs
    override fun stop() = Unit
    override val playing: Boolean get() = false
    override fun levelDb(): Double = com.folio.hermeslive.audio.Dbfs.FLOOR
    override fun playedMs(): Long = 0
}
