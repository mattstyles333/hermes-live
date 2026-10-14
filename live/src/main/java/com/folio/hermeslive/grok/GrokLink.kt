package com.folio.hermeslive.grok

import com.folio.hermes.realtime.GrokRealtime
import com.folio.hermes.realtime.GrokSession
import com.folio.hermes.realtime.LinkState
import com.folio.hermeslive.call.ondemand.LinkAudio
import com.folio.hermeslive.call.ondemand.LinkPhase
import com.folio.hermeslive.call.ondemand.OpenKind
import com.folio.hermeslive.call.ondemand.OpenReason
import com.folio.hermeslive.call.ondemand.OpenRequest
import com.folio.hermeslive.call.ondemand.RealtimeLink

/**
 * Grok Voice as an on-demand [RealtimeLink] (ondemand slice): [close] parks the socket (the
 * conversation id and the queued notices stay), [open] resumes it with xAI's `conversation_id`
 * inside the 30-minute window, else fresh with the context note. The host feeds the mic
 * ([LinkAudio.Fed]); audio is resampled to 24 kHz and sent in 200 ms appends, so the held
 * audio after a reopen never goes as one huge frame.
 *
 * The call's first open is the session's own start ([GrokSession.start], done by the engine).
 */
class GrokLink(
    private val session: GrokSession,
    private val realtime: GrokRealtime,
    /** The phone is online: a waiting result reopens only then (no retry loop while offline). */
    private val online: () -> Boolean = { true },
) : RealtimeLink {
    override val audio: LinkAudio = LinkAudio.Fed

    /** We closed it (on purpose); cleared by [open]. */
    @Volatile private var parked = false
    private var everParked = false

    /** One per stream; replaced at each reopen (mic thread reads it under the controller's lock). */
    @Volatile private var resampler = Resampler16To24()

    override val phase: LinkPhase
        get() = if (parked) {
            LinkPhase.Closed
        } else {
            when (realtime.link.value) {
                is LinkState.Open -> if (realtime.acceptsAudio) LinkPhase.Ready else LinkPhase.Opening
                LinkState.Idle, LinkState.Connecting, is LinkState.Reconnecting -> LinkPhase.Opening
                LinkState.Offline, is LinkState.Failed -> LinkPhase.Failed
                LinkState.Closed, LinkState.Parked -> LinkPhase.Closed
            }
        }

    override val lastOpen: OpenKind?
        get() = (realtime.link.value as? LinkState.Open)?.let { if (it.resumed) OpenKind.Resumed else OpenKind.Fresh }

    override fun open(request: OpenRequest): Boolean {
        // The call's start: the engine already started the session (it connects by itself).
        if (request.reason == OpenReason.Start && !everParked) return true
        parked = false
        resampler = Resampler16To24()
        return session.resume(request.context)
    }

    override fun close() {
        parked = true
        everParked = true
        session.park()
    }

    override fun sendAudio(pcm: ShortArray, count: Int) {
        var from = 0
        while (from < count) {
            val n = minOf(CHUNK, count - from)
            val chunk = if (from == 0 && n == pcm.size) pcm else pcm.copyOfRange(from, from + n)
            session.audio(resampler.convert(chunk, n))
            from += n
        }
    }

    override fun canOpen(): Boolean = online()

    override fun isIdle(): Boolean = session.quiet()
    override fun hasPendingNotice(): Boolean = session.hasPendingNotice()
    override fun userSpeaking(): Boolean = session.userSpeaking()
    override fun speaking(): Boolean = session.speaking()

    companion object {
        /** 200 ms at 16 kHz. */
        const val CHUNK = 3_200
    }
}
