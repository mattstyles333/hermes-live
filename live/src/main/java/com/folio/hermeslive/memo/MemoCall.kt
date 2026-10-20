package com.folio.hermeslive.memo

import com.folio.hermes.ApprovalChoice
import com.folio.hermes.telemetry.TelemetrySink
import com.folio.hermeslive.audio.AudioSource
import com.folio.hermeslive.call.CallAudio
import com.folio.hermeslive.call.CallEffects
import com.folio.hermeslive.call.CallPhase
import com.folio.hermeslive.call.CallRequest
import com.folio.hermeslive.call.CallUiState
import com.folio.hermeslive.call.Earcon
import com.folio.hermeslive.call.FocusMode
import com.folio.hermeslive.call.MemoCause
import com.folio.hermeslive.call.RouteChoice
import com.folio.hermeslive.call.RouteKind
import com.folio.hermeslive.call.RouteOption
import com.folio.hermeslive.call.RouteSelector
import com.folio.hermeslive.call.VoiceCall
import com.folio.hermeslive.data.LiveSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.util.UUID
import kotlin.math.abs

/**
 * An offline memo as a call (offline-memo slice): the first branch of `CallSwitch.plan` when a memo
 * is asked for, or GPT-Live can't run (the phone offline). It runs in `CallService` like any call
 * (microphone foreground service, CallStyle notification, tile, headset End, mute).
 *
 * Start: the call's audio (communication mode, focus held), then the start line through the
 * phone's own voice ([MemoSpeech]; none ready within [MemoSettings.ttsReadyMs]: an earcon and a
 * haptic instead), and only then the mic, so the line is never recorded. Recording: 16 kHz mono
 * PCM through [AudioSource] into WAV chunks of [MemoSettings.chunkMs] under the memo's own
 * directory, its record (`cascade.memo.<id>`, state `recording`) written before the first sample.
 * Muted, or during another phone call, nothing is written. It stops on End (any of the call's End
 * paths) or at [MemoSettings.capMs] of wall time (and says so); a mic or storage failure keeps
 * what was already recorded. Under
 * [MemoSettings.minMs] it is discarded; else it becomes `recorded`, the end line plays, and
 * [onSaved] hands it to the sender.
 *
 * Every method runs on [scope]'s thread (Main); the capture loop runs on [io].
 */
class MemoCall(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val store: () -> MemoStore,
    private val audio: CallAudio,
    private val effects: CallEffects,
    private val speech: () -> MemoSpeech,
    private val mic: () -> AudioSource,
    private val settings: () -> LiveSettings,
    /** Wall clock, epoch ms (the record's `created_at`). */
    private val clock: () -> Long,
    private val telemetry: TelemetrySink,
    private val limits: MemoSettings = MemoSettings(),
    private val onSaved: (MemoRecord) -> Unit = {},
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : VoiceCall, CallAudio.Listener {
    private val _state = MutableStateFlow(CallUiState())
    override val state: StateFlow<CallUiState> = _state.asStateFlow()

    private var id: String? = null
    private var request = CallRequest()
    private var tts: MemoSpeech? = null
    private var startJob: Job? = null
    private var recorder: Deferred<Capture>? = null
    private var finishing = false
    private var skipLine = false
    @Volatile private var stopRequested = false
    @Volatile private var silent = false
    private var otherCall = false
    private var devices: List<RouteOption> = emptyList()
    private var route = RouteChoice(null, manual = false)

    /** The memo the mic is writing now (the sender leaves its `recording` record alone). */
    @Volatile var recordingId: String? = null
        private set

    override fun start(request: CallRequest) {
        if (_state.value.inCall) return
        val memoId = newId()
        id = memoId
        this.request = request
        finishing = false
        skipLine = false
        stopRequested = false
        silent = request.muted
        otherCall = false
        recorder = null
        _state.value = CallUiState(phase = CallPhase.Live, memo = true, muted = request.muted, notice = lines(request.memoCause).notice, startedAtMs = clock())
        audio.begin(this)
        devices = audio.devices()
        route = RouteChoice(RouteSelector.auto(devices, settings().fallbackRoute), manual = false)
        applyRoute()
        audio.setFocus(FocusMode.Hold)
        event("memo.start", mapOf("entry" to request.entry, "cause" to request.memoCause.wire))
        startJob = scope.launch {
            say(lines(request.memoCause).start)
            if (!finishing) record(memoId)
        }
    }

    /** End (button, notification, tile, headset). A second press during the end line cuts it. */
    override fun end() {
        if (!_state.value.inCall) return
        if (finishing) {
            skipLine = true
            tts?.stop()
            return
        }
        finish("ended")
    }

    override fun toggleMute() {
        if (!_state.value.inCall || finishing) return
        val muted = !_state.value.muted
        _state.update { it.copy(muted = muted) }
        silent = muted || otherCall
        effects.earcon(if (muted) Earcon.Muted else Earcon.Unmuted)
    }

    override fun selectRoute(option: RouteOption) {
        if (!_state.value.inCall) return
        route = RouteChoice(devices.firstOrNull { it.id == option.id } ?: option, manual = true)
        applyRoute()
    }

    /** Typed text has nowhere to go offline. */
    override fun submitText(text: String) = Unit
    override fun answerApproval(id: String, choice: ApprovalChoice) = Unit
    override fun answerClarify(id: String, answers: Map<String, String?>) = Unit

    override fun dismissSummary() {
        if (_state.value.inCall) return
        _state.value = CallUiState()
    }

    override fun onDevicesChanged(devices: List<RouteOption>) {
        if (!_state.value.inCall) return
        route = RouteSelector.onDevicesChanged(this.devices, devices, route, settings().fallbackRoute)
        this.devices = devices
        applyRoute()
    }

    override fun onBecomingNoisy() {
        if (!_state.value.inCall) return
        route = RouteSelector.onNoisy(devices, settings().fallbackRoute)
        applyRoute()
    }

    /** Another call has the audio: write nothing until it is over. */
    override fun onOtherCall(active: Boolean) {
        if (!_state.value.inCall || active == otherCall) return
        otherCall = active
        silent = active || _state.value.muted
        if (!active) audio.reassert()
        _state.update { it.copy(onOtherCall = active, notice = if (active) "Paused for your other call." else "Recording again.") }
    }

    private fun applyRoute() {
        val r = route.route
        if (r != null) audio.select(r)
        effects.proximity(_state.value.inCall && r?.kind == RouteKind.Earpiece)
        _state.update { it.copy(route = r, routes = devices) }
    }

    /** Say [line] in the phone's voice (cut at [MemoSettings.lineCapMs]); no voice ready in time: an earcon and a haptic. */
    private suspend fun say(line: String) {
        val s = tts ?: runCatching(speech).getOrNull()?.also { tts = it }
        val ready = s != null && withTimeoutOrNull(limits.ttsReadyMs) { runCatching { s.awaitReady() }.getOrDefault(false) } == true
        if (!ready) {
            effects.earcon(Earcon.Connected)
            effects.haptic(strong = false)
            return
        }
        if (withTimeoutOrNull(limits.lineCapMs) { s!!.speak(line) } == null) s!!.stop()
    }

    private fun record(memoId: String) {
        val record = MemoRecord(memoId, createdAt = _state.value.startedAtMs ?: clock())
        recordingId = memoId
        val memos = store()
        if (runCatching { memos.put(record) }.isFailure) {
            recorder = scope.async { Capture(0, 0, false, Failure.Storage) }
            finish("storage_failed")
            return
        }
        val capture = scope.async(io) { capture(memos, record) }
        recorder = capture
        scope.launch {
            val result = capture.await()
            if (!finishing) finish(result.failure?.reason ?: if (result.capped) "cap" else "mic_ended")
        }
    }

    /** Why the capture stopped early: the mic (open or read) or the phone's storage (a record or a file write). */
    private enum class Failure(val reason: String) { Mic("mic_failed"), Storage("storage_failed") }

    private class Capture(val samples: Long, val chunks: Int, val capped: Boolean, val failure: Failure? = null)

    private class StorageFailure(cause: Throwable) : Exception(cause)

    /**
     * Read the mic until stopped. While muted or during another call the frames are read and
     * dropped (nothing is written); the cap counts wall time from the first frame either way.
     */
    private suspend fun capture(memos: MemoStore, record: MemoRecord): Capture {
        val rate = AudioSource.SAMPLE_RATE
        val chunkSamples = limits.chunkMs * rate / 1000
        val capSamples = limits.capMs * rate / 1000
        val source = runCatching { mic().also { it.open() } }.getOrElse { return Capture(0, 0, false, Failure.Mic) }
        val startedAt = clock()
        var writer: WavWriter? = null
        var chunks = 0
        var total = 0L
        var capped = false
        val buffer = ShortArray(rate / 10)
        fun storage(block: () -> Unit) = try {
            block()
        } catch (e: Exception) {
            throw StorageFailure(e)
        }
        try {
            while (currentCoroutineContext().isActive && !stopRequested && !capped) {
                val n = source.read(buffer)
                if (n < 0) break
                if (n == 0) {
                    delay(POLL_MS)
                    continue
                }
                var off = 0
                while (!silent && off < n && total < capSamples) {
                    val w = writer?.takeIf { it.samples < chunkSamples } ?: run {
                        writer?.let { storage { it.close() } }
                        writer = null
                        // Counted before its first sample: after a kill the sender repairs every file the record names.
                        storage { memos.put(record.copy(chunks = chunks + 1)) }
                        lateinit var opened: WavWriter
                        storage { opened = WavWriter.open(memos.chunk(record.id, chunks), rate) }
                        chunks++
                        opened.also { writer = it }
                    }
                    val take = minOf((n - off).toLong(), chunkSamples - w.samples, capSamples - total).toInt()
                    storage { w.write(buffer, off, take) }
                    off += take
                    total += take
                }
                level(buffer, n, silent)
                capped = clock() - startedAt >= limits.capMs || total >= capSamples
                yield()
            }
            return Capture(total, chunks, capped)
        } catch (e: CancellationException) {
            throw e
        } catch (e: StorageFailure) {
            return Capture(total, chunks, capped, Failure.Storage)
        } catch (e: Exception) {
            return Capture(total, chunks, capped, Failure.Mic)
        } finally {
            runCatching { writer?.close() }
            runCatching { source.close() }
        }
    }

    private fun level(buffer: ShortArray, n: Int, silent: Boolean) {
        var peak = 0
        if (!silent) for (i in 0 until n) peak = maxOf(peak, abs(buffer[i].toInt()))
        _state.update { it.copy(level = (peak / 32768f * 2f).coerceIn(0f, 1f)) }
    }

    private fun finish(reason: String) {
        if (finishing) return
        finishing = true
        val memoId = id ?: return
        _state.update { it.copy(phase = CallPhase.Ending, notice = "Saving…", level = 0f) }
        if (recorder == null) tts?.stop()
        scope.launch {
            startJob?.cancel()
            stopRequested = true
            val capture = recorder?.await()
            val memos = store()
            val durationMs = (capture?.samples ?: 0L) * 1000 / AudioSource.SAMPLE_RATE
            // What was recorded counts, even if the mic or the storage failed after it.
            val record = runCatching { memos.get(memoId) }.getOrNull()
            val keep = capture != null && record != null && durationMs >= limits.minMs
            val saved = keep && runCatching { memos.put(record!!.copy(state = MemoState.Recorded, chunks = capture!!.chunks, durationMs = durationMs)) }.isSuccess
            if (!saved) runCatching { memos.delete(memoId) }
            recordingId = null
            val failure = capture?.failure ?: Failure.Storage.takeIf { keep && !saved }
            event(
                "memo.end",
                mapOf("reason" to (failure?.reason ?: reason), "duration_s" to durationMs / 1000, "chunks" to (capture?.chunks ?: 0), "saved" to saved),
            )
            val notice = when {
                saved && failure != null -> lines(request.memoCause).saved + " The recording stopped early."
                saved -> lines(request.memoCause).saved
                failure == Failure.Storage -> "Couldn't save the memo on this phone. Nothing was saved."
                failure == Failure.Mic -> "The microphone is not available. Nothing was saved."
                else -> "Too short. Nothing was saved."
            }
            if (saved && !skipLine) say(endLine(request.memoCause, capped = capture!!.capped))
            tts?.let { runCatching { it.shutdown() } }
            tts = null
            effects.proximity(false)
            audio.end()
            _state.value = CallUiState(phase = CallPhase.Ended, memo = true, notice = notice, error = if (!saved && failure != null) notice else null)
            if (saved) runCatching { memos.get(memoId) }.getOrNull()?.let { runCatching { onSaved(it) } }
        }
    }

    private fun event(name: String, attrs: Map<String, Any?>) = runCatching { telemetry.event(name, attrs, false) }

    companion object {
        const val POLL_MS = 10L

        /** What a memo says and shows for its [MemoCause]: spoken [start] and [end], the screen's [notice] and [saved]. */
        data class Lines(val start: String, val end: String, val notice: String, val saved: String)

        fun lines(cause: MemoCause): Lines = when (cause) {
            MemoCause.Asked -> Lines(
                "Recording. I'll send this to Hermes when you're done.",
                "Saved. I'll send it to Hermes.",
                "Recording a memo for Hermes.",
                "Memo saved. It goes to Hermes as a task.",
            )
            MemoCause.Offline -> Lines(
                "You're offline. I'm recording; I'll send this to Hermes when you're back.",
                "Saved. I'll send it when you're online.",
                "You're offline. Recording a memo for Hermes.",
                "Memo saved. It goes to Hermes as a task once you're online.",
            )
            MemoCause.HermesUnreachable, MemoCause.VoiceUnavailable -> Lines(
                "Hermes can't take a call right now. I'm recording; I'll send this to Hermes when I can.",
                "Saved. I'll send it to Hermes when it's back.",
                "Hermes can't take a call. Recording a memo for it.",
                "Memo saved. It goes to Hermes as a task when it's back.",
            )
            MemoCause.CallDropped -> Lines(
                "The call dropped. I'm recording; I'll send this to Hermes.",
                "Saved. I'll send it to Hermes when it's back.",
                "The call dropped. Recording a memo for Hermes.",
                "Memo saved. It goes to Hermes as a task when it's back.",
            )
        }

        fun endLine(cause: MemoCause, capped: Boolean): String =
            (if (capped) "That's ten minutes, the most for one memo. " else "") + lines(cause).end
    }
}
