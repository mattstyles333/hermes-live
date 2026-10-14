package com.folio.hermes.cascade

import com.folio.hermes.BusyMode
import com.folio.hermes.SubmitStatus
import com.folio.hermes.TurnStatus
import com.folio.hermes.TurnUpdate
import com.folio.hermes.cascade.ports.AudioOut
import com.folio.hermes.cascade.ports.Clock
import com.folio.hermes.cascade.ports.HermesPort
import com.folio.hermes.cascade.ports.OpenRouterPort
import com.folio.hermes.openrouter.FailureKind
import com.folio.hermes.openrouter.JevAnswer
import com.folio.hermes.openrouter.JevQuestion
import com.folio.hermes.openrouter.OpenRouterException
import com.folio.hermes.openrouter.OpenRouterResult
import com.folio.hermes.openrouter.SpeechFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

/*
 * In-memory fakes for the ports (R-ARC-3), on a TestScope's virtual clock. No network, no keys.
 */

/** The TestScope's virtual time as the actor's clock (wall time = a fixed epoch plus virtual time). */
class TestClock(private val scope: TestScope, private val epochMs: Long = 1_760_000_000_000L) : Clock {
    override fun nowMs(): Long = scope.testScheduler.currentTime
    override fun wallMs(): Long = epochMs + scope.testScheduler.currentTime
}

/** A clip of [ms] milliseconds at 24 kHz mono s16le. */
fun clipOf(ms: Long): AudioClip {
    val format = SpeechFormat.Pcm()
    return AudioClip(ByteArray(format.bytesFor(ms).toInt()), format)
}

/** Scripted OpenRouter: Jev answers, writer sentences, plans and clips, with virtual delays. */
class FakeOpenRouter(private val clock: Clock) : OpenRouterPort {
    class JevScript(val delayMs: Long, val result: OpenRouterResult<Map<String, JevAnswer>>)

    class JevCall(val at: Long, val state: JsonObject, val questions: Set<String>, val timeoutMs: Long) {
        var cancelled = false
        var answeredAt: Long? = null
    }

    val jevScripts = ArrayDeque<JevScript>()

    /** Used when no script is queued. */
    var jevDefault: JevScript = JevScript(100, OpenRouterResult.Ok(mapOf("turn" to answer("done"), "action" to answer("reply"))))
    val jevCalls = mutableListOf<JevCall>()

    /** Writer sentences per prompt, then [writerFailure] (thrown after them) if set. */
    var writes: (system: String, prompt: String) -> List<String> = { _, _ -> listOf("Sure thing.") }
    var writerDelayMs = 100L
    var writerFailure: FailureKind? = null
    val writerPrompts = mutableListOf<String>()

    var plan: (prompt: String) -> OpenRouterResult<String> = { OpenRouterResult.Ok("LABEL: stock check\nPROMPT: Check RB2140 stock.\nSAY: Checking the stock now.") }
    val planPrompts = mutableListOf<String>()

    /** Lines cached (no TTS latency); everything else takes [ttsDelayMs]. Clips are [clipMs] long. */
    val cached = mutableSetOf<String>()
    var ttsDelayMs = 300L
    var clipMs = 500L
    val clips = mutableListOf<String>()

    override suspend fun jev(state: JsonObject, questions: Map<String, JevQuestion>, timeoutMs: Long): OpenRouterResult<Map<String, JevAnswer>> {
        val call = JevCall(clock.nowMs(), state, questions.keys, timeoutMs)
        jevCalls += call
        val script = jevScripts.removeFirstOrNull() ?: jevDefault
        try {
            delay(script.delayMs)
        } catch (e: CancellationException) {
            call.cancelled = true
            throw e
        }
        call.answeredAt = clock.nowMs()
        return script.result
    }

    override fun sentences(system: String, prompt: String): Flow<String> = flow {
        writerPrompts += prompt
        for (s in writes(system, prompt)) {
            delay(writerDelayMs)
            emit(s)
        }
        writerFailure?.let { throw OpenRouterException(OpenRouterResult.Failed(it)) }
    }

    override suspend fun write(system: String, prompt: String): OpenRouterResult<String> {
        planPrompts += prompt
        delay(writerDelayMs)
        return plan(prompt)
    }

    override suspend fun clip(text: String): OpenRouterResult<AudioClip> {
        clips += text
        if (text !in cached) delay(ttsDelayMs)
        return OpenRouterResult.Ok(clipOf(clipMs))
    }

    override fun isCached(text: String): Boolean = text in cached
}

/** Plays a clip in virtual time; [stop] ends it early (returns the time played). */
class FakeAudioOut(private val clock: Clock) : AudioOut {
    private var stopSignal: CompletableDeferred<Unit>? = null
    val played = mutableListOf<Pair<Long, Long>>()

    override suspend fun play(clip: AudioClip): Long {
        val start = clock.nowMs()
        val signal = CompletableDeferred<Unit>()
        stopSignal = signal
        withTimeoutOrNull(clip.durationMs) { signal.await() }
        val ms = minOf(clock.nowMs() - start, clip.durationMs)
        played += start to ms
        return ms
    }

    override fun stop() {
        stopSignal?.complete(Unit)
    }
}

/** Hermes sessions under test control: each submit is a channel the test pushes updates into. */
class FakeHermes : HermesPort {
    data class Submit(val taskId: String, val text: String, val voiceContext: String, val queued: Boolean, val interruptFirst: Boolean) {
        val updates = Channel<TurnUpdate>(Channel.UNLIMITED)
    }

    val opened = mutableListOf<Triple<String, String, String?>>()
    val submits = mutableListOf<Submit>()
    val interrupts = mutableListOf<String>()
    var busy: BusyMode? = BusyMode.Interrupt

    /** Accept and start every submit at once (default). */
    var autoStart = true

    override fun open(taskId: String, label: String, storedSessionId: String?) {
        if (opened.none { it.first == taskId }) opened += Triple(taskId, label, storedSessionId)
    }

    override fun submit(taskId: String, text: String, voiceContext: String, queued: Boolean, interruptFirst: Boolean): Flow<TurnUpdate> {
        val s = Submit(taskId, text, voiceContext, queued, interruptFirst)
        submits += s
        if (autoStart) {
            s.updates.trySend(TurnUpdate.Accepted("rt-$taskId", if (queued) SubmitStatus.Queued else SubmitStatus.Streaming))
            s.updates.trySend(TurnUpdate.Started)
        }
        return s.updates.consumeAsFlow()
    }

    /** Finish the latest submit of [taskId] with [text]. */
    fun complete(taskId: String, text: String, status: TurnStatus = TurnStatus.Complete) {
        val s = submits.last { it.taskId == taskId }
        s.updates.trySend(TurnUpdate.Completed(text, status, null))
        s.updates.close()
    }

    override suspend fun interrupt(taskId: String): Boolean {
        interrupts += taskId
        submits.lastOrNull { it.taskId == taskId }?.let {
            it.updates.trySend(TurnUpdate.Completed("", TurnStatus.Interrupted, null))
            it.updates.close()
        }
        return true
    }

    override suspend fun busyMode(): BusyMode? = busy

    override val sessions = MutableSharedFlow<Pair<String, String>>()
    override val connected = MutableStateFlow(true)
}
