package com.folio.hermeslive.meeting

import com.folio.hermes.cascade.ports.Clock
import com.folio.hermes.telemetry.TelemetrySink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId
import java.util.UUID

/** What the meeting screen shows. */
sealed interface MeetingScreen {
    /** No meeting: home as usual. */
    data object None : MeetingScreen

    /** Meeting mode needs Bill's speech models on the phone first. */
    data object NeedsModels : MeetingScreen

    /** "What's the meeting?": typed or one spoken sentence, skippable. [loading]: the models are loading. */
    data class Intro(val loading: Boolean, val listening: Boolean = false, val heard: String? = null) : MeetingScreen

    /** Taking notes (or paused: `live.meta.phase`). */
    data class Taking(val live: MeetingLive) : MeetingScreen

    /** A meeting was cut off (the app was killed while it recorded). */
    data class Interrupted(val meta: MeetingMeta, val segments: Int) : MeetingScreen

    /** "Anything to add?". [canSpeak]: the mic service is up for one spoken sentence. */
    data class Ending(val meta: MeetingMeta, val segments: Int, val marks: Int, val canSpeak: Boolean, val listening: Boolean = false) : MeetingScreen

    /** Sent, held or saved. */
    data class Done(val meta: MeetingMeta, val outcome: MeetingOutbox.Outcome?) : MeetingScreen

    /** The mic or the models failed. */
    data class Failed(val message: String) : MeetingScreen
}

/**
 * Meeting mode's screens and their steps, apart from Android (the host supplies the speech, the
 * service and the haptics). One meeting at a time; every step runs on [scope].
 */
class MeetingFlow(
    private val log: MeetingLog,
    private val outbox: MeetingOutbox,
    private val settings: () -> MeetingSettings,
    private val modelsReady: () -> Boolean,
    /** Loads the speech (models: slow, off the main thread). */
    private val loadSpeech: suspend () -> MeetingSpeech,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val cleaner: () -> SegmentCleaner? = { null },
    private val ack: (Ack) -> Unit = {},
    private val telemetry: TelemetrySink = TelemetrySink.None,
) {
    private val lock = Mutex()
    private val _screen = MutableStateFlow<MeetingScreen>(MeetingScreen.None)
    private val sessionFlow = MutableStateFlow<MeetingSession?>(null)
    private var speech: MeetingSpeech? = null
    private var liveJob: Job? = null
    private var entry = "app"

    val screen: StateFlow<MeetingScreen> = _screen.asStateFlow()

    /** The mic belongs to meeting mode (from the intro until the meeting is sent or saved). */
    val busy: Boolean get() = speech != null || _screen.value.let { it is MeetingScreen.Intro || it is MeetingScreen.Taking }

    /**
     * The Meeting button or the shortcut: show what fits. A meeting cut off by a kill comes back as
     * [MeetingScreen.Interrupted]; one that ended but was not sent as its "Anything to add?".
     * Returns true when the mic service should start (the intro).
     */
    fun open(from: String = "app"): Boolean {
        entry = from
        val current = _screen.value
        if (current !is MeetingScreen.None && current !is MeetingScreen.Done && current !is MeetingScreen.Failed && current !is MeetingScreen.NeedsModels) return false
        log.active()?.let { cut ->
            _screen.value = MeetingScreen.Interrupted(cut, log.transcript(cut.id).segments.size)
            return false
        }
        if (!modelsReady()) {
            _screen.value = MeetingScreen.NeedsModels
            event("meeting.models_missing", emptyMap())
            return false
        }
        _screen.value = MeetingScreen.Intro(loading = true)
        return true
    }

    /** At start-up: a meeting cut off by a kill is offered again (Resume / End / Discard). */
    fun restore() {
        if (_screen.value !is MeetingScreen.None) return
        log.active()?.let { _screen.value = MeetingScreen.Interrupted(it, log.transcript(it.id).segments.size) }
    }

    /** The mic service is in the foreground: load the models (the intro, or a resumed meeting). */
    suspend fun serviceStarted() {
        val s = try {
            lock.withLock { speech ?: loadSpeech().also { speech = it } }
        } catch (e: Exception) {
            fail("Couldn't load the speech models.", "models")
            return
        }
        val screen = _screen.value
        when {
            screen is MeetingScreen.Intro -> {
                if (sessionFlow.value == null) newSession(s)
                _screen.value = screen.copy(loading = false)
            }
            screen is MeetingScreen.Ending -> _screen.value = screen.copy(canSpeak = true)
        }
    }

    private fun newSession(s: MeetingSpeech): MeetingSession {
        val meta = MeetingMeta(id = UUID.randomUUID().toString(), startedAt = clock.wallMs())
        return session(s, meta)
    }

    private fun session(s: MeetingSpeech, meta: MeetingMeta): MeetingSession {
        val session = MeetingSession(
            log, s, clock, scope, meta,
            wakeWord = { settings().wakeWord },
            cleaner = cleaner,
            onTask = { request, at, seg -> task(request, at, seg) },
            ack = ack,
            telemetry = telemetry,
        )
        session.attach()
        sessionFlow.value = session
        return session
    }

    /** "What's the meeting?" by voice: one sentence. */
    fun speakTitle() = scope.launch {
        val session = sessionFlow.value ?: return@launch
        val intro = _screen.value as? MeetingScreen.Intro ?: return@launch
        _screen.value = intro.copy(listening = true)
        val heard = runCatching { session.captureOne() }.getOrNull()
        (_screen.value as? MeetingScreen.Intro)?.let { _screen.value = it.copy(listening = false, heard = heard ?: it.heard) }
    }

    /** Start taking notes with [title] (blank: skipped). */
    fun start(title: String) = scope.launch {
        val session = sessionFlow.value ?: return@launch
        try {
            session.begin(title, entry)
            showLive(session)
        } catch (e: Exception) {
            fail("The microphone could not start.", "mic")
        }
    }

    private fun showLive(session: MeetingSession) {
        liveJob?.cancel()
        liveJob = scope.launch { session.live.collect { _screen.value = MeetingScreen.Taking(it) } }
    }

    fun pause() = scope.launch { sessionFlow.value?.pause() }

    fun resume() = scope.launch {
        val session = sessionFlow.value ?: return@launch
        try {
            session.record()
        } catch (e: Exception) {
            fail("The microphone could not start.", "mic")
        }
    }

    fun mark() = scope.launch { sessionFlow.value?.mark() }

    /** End the meeting: "Anything to add?" next. */
    fun end() = scope.launch {
        val session = sessionFlow.value ?: return@launch
        if (!session.current.phase.live) return@launch
        liveJob?.cancel()
        val ended = session.end()
        val v = session.live.value
        _screen.value = MeetingScreen.Ending(ended, v.segments, v.marks, canSpeak = speech != null)
    }

    /** A meeting cut off by a kill: record on (the mic service has just started). */
    fun resumeInterrupted() = scope.launch {
        val cut = (_screen.value as? MeetingScreen.Interrupted)?.meta ?: return@launch
        val s = try {
            lock.withLock { speech ?: loadSpeech().also { speech = it } }
        } catch (e: Exception) {
            fail("Couldn't load the speech models.", "models")
            return@launch
        }
        val session = session(s, cut)
        try {
            session.resumeAfterKill()
            event("meeting.started", mapOf("entry" to "resume_after_kill"))
            showLive(session)
        } catch (e: Exception) {
            fail("The microphone could not start.", "mic")
        }
    }

    /** A meeting cut off by a kill: end it where it stopped, then "Anything to add?" (typed: no mic). */
    fun endInterrupted() {
        val cut = (_screen.value as? MeetingScreen.Interrupted)?.meta ?: return
        val t = log.transcript(cut.id)
        val last = listOfNotNull(t.segments.maxOfOrNull { it.at }, t.markers.maxOfOrNull { it.at }, cut.pausedAt, cut.startedAt).max()
        val paused = cut.pausedMs + (cut.pausedAt?.let { last - it } ?: 0L).coerceAtLeast(0)
        val ended = cut.copy(phase = MeetingPhase.Ended, endedAt = last, pausedAt = null, pausedMs = paused)
        log.put(ended)
        log.clearActive(cut.id)
        _screen.value = MeetingScreen.Ending(ended, t.segments.size, t.markers.size, canSpeak = false)
    }

    /** Discard a meeting (cut off, or at "Anything to add?"): its transcript is deleted. */
    fun discard() = scope.launch {
        val id = when (val s = _screen.value) {
            is MeetingScreen.Interrupted -> s.meta.id
            is MeetingScreen.Ending -> s.meta.id
            else -> return@launch
        }
        log.delete(id)
        event("meeting.deleted", mapOf("count" to 1, "reason" to "discarded"))
        finish(MeetingScreen.None)
    }

    /** "Anything to add?": typed text. */
    fun add(text: String) {
        val ending = _screen.value as? MeetingScreen.Ending ?: return
        val t = text.trim().take(MAX_ADDITION).ifEmpty { return }
        val meta = (log.meta(ending.meta.id) ?: ending.meta).let { it.copy(additions = it.additions + t) }
        log.put(meta)
        _screen.value = ending.copy(meta = meta)
    }

    /** "Anything to add?": one spoken sentence. */
    fun speakAddition() = scope.launch {
        val ending = _screen.value as? MeetingScreen.Ending ?: return@launch
        val session = sessionFlow.value ?: return@launch
        _screen.value = ending.copy(listening = true)
        val heard = runCatching { session.captureOne() }.getOrNull()
        val now = _screen.value as? MeetingScreen.Ending ?: return@launch
        _screen.value = now.copy(listening = false)
        heard?.let(::add)
    }

    /** Send the notes to Hermes (held on the phone if it can't go now). */
    fun send() = scope.launch {
        val ending = _screen.value as? MeetingScreen.Ending ?: return@launch
        val outcome = outbox.queue(ending.meta.id)
        finish(MeetingScreen.Done(log.meta(ending.meta.id) ?: ending.meta, outcome))
    }

    /** Keep the transcript on the phone, send nothing. */
    fun save() = scope.launch {
        val ending = _screen.value as? MeetingScreen.Ending ?: return@launch
        val saved = (log.meta(ending.meta.id) ?: ending.meta).copy(phase = MeetingPhase.Saved)
        log.put(saved)
        finish(MeetingScreen.Done(saved, null))
    }

    /** Leave the Done or Needs-models screen. */
    fun close() {
        if (_screen.value is MeetingScreen.Done || _screen.value is MeetingScreen.NeedsModels || _screen.value is MeetingScreen.Failed) _screen.value = MeetingScreen.None
    }

    /**
     * Nobody came back to "Anything to add?": free the mic service and the models; the screen stays
     * (typed additions, Send and Keep still work).
     */
    fun releaseMic() = scope.launch {
        val ending = _screen.value as? MeetingScreen.Ending ?: return@launch
        sessionFlow.value?.close()
        sessionFlow.value = null
        val s = lock.withLock { speech.also { speech = null } }
        runCatching { s?.release() }
        _screen.value = ending.copy(canSpeak = false, listening = false)
    }

    /** Leave the intro without a meeting. */
    fun cancelIntro() = scope.launch {
        if (_screen.value !is MeetingScreen.Intro) return@launch
        finish(MeetingScreen.None)
    }

    /** Sign-out: stop at once, nothing kept in memory (the store is wiped by the caller). */
    fun signedOut() = scope.launch { finish(MeetingScreen.None) }

    private suspend fun task(request: String, at: Long, seg: Int) {
        val meta = sessionFlow.value?.current ?: return
        val task = MeetingTask(meta.id, seg, MeetingBrief.taskBrief(request, meta, at, zone()), WakePhrase.taskLabel(request), at)
        outbox.task(task)
    }

    private fun fail(message: String, reason: String) {
        event("meeting.failed", mapOf("reason" to reason))
        scope.launch { finish(MeetingScreen.Failed(message)) }
    }

    /** Release the mic and models, then show [next]. */
    private suspend fun finish(next: MeetingScreen) {
        liveJob?.cancel()
        sessionFlow.value?.close()
        sessionFlow.value = null
        val s = lock.withLock { speech.also { speech = null } }
        runCatching { s?.release() }
        _screen.value = next
    }

    private fun event(name: String, attrs: Map<String, Any?>) = runCatching { telemetry.event(name, attrs, false) }

    /** History (Settings › Meeting mode). */
    fun history(): List<MeetingMeta> = log.all().filterNot { it.phase.live }

    fun transcriptText(id: String): String {
        val t = log.transcript(id)
        return MeetingBrief.transcriptLines(t, zone()).joinToString("\n").ifEmpty { "(nothing was transcribed)" }
    }

    /** History's Send: a held meeting is tried again with its own key; a sent one goes again as a new request. */
    fun resend(id: String) = scope.launch { outbox.queue(id) }

    fun delete(id: String) {
        if (sessionFlow.value?.id == id) return
        log.delete(id)
        event("meeting.deleted", mapOf("count" to 1, "reason" to "user"))
    }

    companion object {
        const val MAX_ADDITION = 2_000
    }
}

/** The screen's live view as [MeetingLive], or null. */
fun MeetingScreen.liveOrNull(): MeetingLive? = (this as? MeetingScreen.Taking)?.live
