package com.folio.hermeslive.meeting

import com.folio.hermeslive.tasks.HeldSubmit
import com.folio.hermeslive.tasks.TaskSubmitter
import com.folio.hermes.cascade.Transcript
import com.folio.hermes.cascade.TranscriptPass
import com.folio.hermes.cascade.Word
import com.folio.hermes.cascade.ports.Clock
import com.folio.hermes.cascade.ports.MemoryStore
import com.folio.hermes.cascade.ports.SttEvent
import com.folio.hermes.openrouter.OpenRouterResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope

/** 2026-10-14 09:00 UTC: the wall clock at virtual time 0. */
const val WALL0 = 1_791_968_400_000L

/** The test scheduler's time as both clocks (wall = [WALL0] + virtual ms). */
class TestClock(private val scope: TestScope) : Clock {
    override fun nowMs(): Long = scope.testScheduler.currentTime
    override fun wallMs(): Long = WALL0 + scope.testScheduler.currentTime
}

/** Speech the test drives: [say] a segment; [batches] are what the accurate pass returns, in order (none left: null). */
class FakeMeetingSpeech : MeetingSpeech {
    private val ch = Channel<SttEvent>(Channel.UNLIMITED)
    override val events: Flow<SttEvent> = ch.receiveAsFlow()
    val batches = ArrayDeque<AccurateBatch?>()
    var accurateCalls = 0
    var starts = 0
    var stops = 0
    var released = false
    var failStart = false
    val log = ArrayList<String>()

    /** An accurate batch of one segment per text, as the second pass would give. */
    fun accurateNext(text: String, segments: Int = 1) {
        batches.addLast(AccurateBatch(Transcript(text, text.split(" ").map { Word(it, 0.95) }, TranscriptPass.Accurate), segments))
    }

    fun say(text: String) {
        ch.trySend(SttEvent.SpeechStarted)
        ch.trySend(SttEvent.Partial(text.substringBefore(' ')))
        ch.trySend(SttEvent.Final(text))
        ch.trySend(SttEvent.SpeechEndedAt(0))
    }

    /** A long-speech split: a Final with no speech end. */
    fun split(text: String) {
        ch.trySend(SttEvent.Final(text))
    }

    override suspend fun accurate(): AccurateBatch? {
        accurateCalls++
        log += "accurate"
        return batches.removeFirstOrNull()
    }

    override fun start() {
        if (failStart) error("mic busy")
        starts++
        log += "start"
    }

    override fun stop() {
        stops++
        log += "stop"
    }

    override fun release() {
        released = true
    }

    override val levelDb: StateFlow<Double> = MutableStateFlow(-60.0)
}

/** A task table that records every submit. [reply] decides each one. */
class FakeTasks(var reply: (key: String) -> HeldSubmit = { HeldSubmit.Sent("task-$it") }) : TaskSubmitter {
    data class Call(val brief: String, val label: String, val key: String)

    val calls = ArrayList<Call>()

    override suspend fun submit(brief: String, label: String, key: String): HeldSubmit {
        calls += Call(brief, label, key)
        return reply(key)
    }
}

/** A writer that summarises each part as "SUMMARY n" and counts calls. */
class FakeWriter(var result: (Int) -> OpenRouterResult<String> = { OpenRouterResult.Ok("SUMMARY $it") }) : MeetingWriter {
    var calls = 0
    val prompts = ArrayList<String>()

    override suspend fun write(system: String, prompt: String, maxTokens: Int): OpenRouterResult<String> {
        calls++
        prompts += prompt
        return result(calls)
    }
}

fun memoryLog(store: MemoryStore = MemoryStore()) = MeetingLog { store }

/** A capturing telemetry sink. */
class Events : com.folio.hermes.telemetry.TelemetrySink {
    val all = ArrayList<Pair<String, Map<String, Any?>>>()
    override fun event(name: String, attrs: Map<String, Any?>, warn: Boolean) {
        all += name to attrs
    }
    fun names() = all.map { it.first }
}
