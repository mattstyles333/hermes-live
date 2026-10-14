package com.folio.hermeslive.call.ondemand

import com.folio.hermes.telemetry.TelemetrySink
import com.folio.hermeslive.stt.VoiceDetector

/** A link whose every signal the test sets. Records what it was sent. */
class FakeLink(override val audio: LinkAudio = LinkAudio.Fed) : RealtimeLink {
    @Volatile override var phase: LinkPhase = LinkPhase.Closed
    override var lastOpen: OpenKind? = null
    var idle = true
    var pendingNotice = false
    var hearsUser = false
    var talking = false
    var openResult = true
    var reachable = true
    val opens = mutableListOf<OpenRequest>()
    var closes = 0
    val received = ArrayList<Short>()

    /** What the link would resume as on its next open. */
    var nextKind = OpenKind.Resumed

    override fun open(request: OpenRequest): Boolean {
        opens += request
        if (!openResult) {
            phase = LinkPhase.Failed
            return false
        }
        phase = LinkPhase.Opening
        return true
    }

    /** The server is ready (Grok: `session.update` sent). */
    fun ready() {
        lastOpen = if (opens.size == 1) OpenKind.Fresh else nextKind
        phase = LinkPhase.Ready
    }

    override fun close() {
        closes++
        phase = LinkPhase.Closed
    }

    override fun sendAudio(pcm: ShortArray, count: Int) {
        check(phase == LinkPhase.Ready) { "audio sent before the link was ready" }
        for (i in 0 until count) received += pcm[i]
    }

    override fun canOpen() = reachable
    override fun isIdle() = idle
    override fun hasPendingNotice() = pendingNotice
    override fun userSpeaking() = hearsUser
    override fun speaking() = talking
}

/** Speech while [speaking] is set (the test plays the Silero detector's part). */
class FakeVad : VoiceDetector {
    @Volatile var speaking = false
    var frames = 0
    var released = false
    override fun accept(frame: FloatArray): Boolean {
        frames++
        return speaking
    }

    override fun reset() = Unit
    override fun release() {
        released = true
    }
}

class RecordingSink : TelemetrySink {
    val events = mutableListOf<Triple<String, Map<String, Any?>, Boolean>>()
    override fun event(name: String, attrs: Map<String, Any?>, warn: Boolean) {
        events += Triple(name, attrs, warn)
    }

    fun names() = events.map { it.first }
}

/**
 * A call on a virtual clock: [feed] plays the mic in 20 ms chunks whose samples carry their own
 * index (so a gap or a reorder shows), and ticks the controller every 150 ms as the engine does.
 */
class Rig(
    settings: OnDemandSettings = OnDemandSettings(),
    val link: FakeLink = FakeLink(),
    val vad: FakeVad? = FakeVad(),
    val mic: MicHandoff = MicHandoff.None,
    detector: VoiceDetector? = vad,
) {
    var now = 0L
    var sampleIndex = 0
    private var nextTick = 0L
    val sink = RecordingSink()
    val controller: OnDemandController = OnDemandController(
        link = link,
        settings = settings,
        vadFactory = { detector },
        context = { "context" },
        clock = { now },
        wake = { tickNow() },
        mic = mic,
        telemetry = sink,
    )

    fun tickNow() = controller.tick()

    /** [ms] of mic audio, 20 ms at a time; [before] runs before each chunk. */
    fun feed(ms: Long, before: () -> Unit = {}) {
        val end = now + ms
        while (now < end) {
            before()
            val chunk = ShortArray(CHUNK) { ((sampleIndex + it) % MOD).toShort() }
            sampleIndex += CHUNK
            controller.onMic(chunk, CHUNK)
            now += CHUNK_MS
            if (now >= nextTick) {
                controller.tick()
                nextTick = now + TICK
            }
        }
    }

    /** Time passes with the mic muted or silent-free (no audio), ticking. */
    fun idle(ms: Long) {
        val end = now + ms
        while (now < end) {
            now += TICK
            controller.tick()
        }
    }

    /** Start the call and get the session ready. */
    fun connected(): Rig {
        controller.start()
        link.ready()
        controller.tick()
        return this
    }

    /** Run until the session closes (all idle), with the mic silent. */
    fun closeByIdle() {
        feed(9_000)
        check(link.closes >= 1) { "did not close" }
    }

    companion object {
        const val CHUNK = 320
        const val CHUNK_MS = 20L
        const val TICK = 150L
        const val MOD = 30_000
    }
}
