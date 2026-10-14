package com.folio.hermeslive.call.ondemand

/*
 * On-demand voice sessions (ondemand slice, 2026-10-14): a realtime engine (Grok Voice, GPT-Live)
 * keeps the call open but drops its paid session while the agents work, and resumes it when the
 * user speaks or a task notice arrives. [OnDemandController] owns the rules; each engine supplies
 * one [RealtimeLink].
 *
 * The contract every link keeps:
 * - [RealtimeLink.open], [RealtimeLink.close], [RealtimeLink.isIdle], [RealtimeLink.hasPendingNotice],
 *   [RealtimeLink.userSpeaking] and [RealtimeLink.speaking] are called on the call's thread (Main).
 * - [RealtimeLink.phase] is read from the call's thread and the mic thread: keep it `@Volatile`.
 * - [RealtimeLink.close] moves [RealtimeLink.phase] away from [LinkPhase.Ready] before it returns
 *   (synchronously), so no audio is flushed into a closing session.
 * - [RealtimeLink.sendAudio] is called from the mic thread or the call's thread, always under the
 *   controller's lock, so calls never overlap and arrive in order: the held audio (pre-roll plus
 *   what the mic heard while opening) first, as one call, then live audio ([LinkAudio.Fed] only).
 *   The held audio can be several seconds: a link chunks it as its wire needs.
 */

/** Why the session opens. [wire] goes in telemetry. */
enum class OpenReason(val wire: String) {
    /** The call started. */
    Start("start"),

    /** The phone's voice detector heard the user start speaking. */
    Speech("speech"),

    /** A task result or a needs-you notice is waiting to be spoken. */
    Result("result"),
}

/** Why the session closes. [wire] goes in telemetry. */
enum class CloseReason(val wire: String) {
    /** Everything was idle for `idleCloseMs`. */
    Idle("idle"),

    /** The call ended. */
    HangUp("hang_up"),
}

/** Where the paid session is, as the link sees it. */
enum class LinkPhase {
    /** No session (never opened, or closed by [RealtimeLink.close]). */
    Closed,

    /** Opening or resuming; audio is not accepted yet. */
    Opening,

    /** Open and configured: audio sent now is heard (for Grok: after `session.update`, not at the socket's open). */
    Ready,

    /** The open failed (offline, refused key, unreachable). */
    Failed,
}

/** Who captures the microphone while the session is open. */
enum class LinkAudio {
    /**
     * The host captures the mic all the time and feeds the link while it is open
     * ([RealtimeLink.sendAudio], 16 kHz mono PCM). The pre-roll is flushed into the session
     * before live audio, so the user's first words are never lost. Grok works this way.
     */
    Fed,

    /**
     * The link captures the mic itself while open (WebRTC). The host keeps capturing while the
     * session opens (local listening and the held audio continue); once [LinkPhase.Ready], the
     * link gets the held audio in one [RealtimeLink.sendAudio] call (it plays it in, transcribes it
     * or drops it: its choice), then the controller asks the host to release its capture
     * ([MicHandoff.release]) on the call's thread. After [RealtimeLink.close] the host takes it
     * back ([MicHandoff.reclaim]). Live audio is never sent to an [OwnsMic] link.
     */
    OwnsMic,
}

/** How the last open went. */
enum class OpenKind {
    /** The server kept the conversation (Grok: `conversation_id` within its 30-minute window). */
    Resumed,

    /** A new session: the link injected [OpenRequest.context] (a short note) first. */
    Fresh,
}

/**
 * One open. [context] builds a short context note (the conversation so far and the tasks' status;
 * no audio) for a link that cannot resume; it is called at most once, on the call's thread.
 */
class OpenRequest(val reason: OpenReason, val context: () -> String?)

/** One realtime engine's paid session, as [OnDemandController] drives it. See the file header for threads. */
interface RealtimeLink {
    val audio: LinkAudio

    /** `@Volatile`: read from both threads. */
    val phase: LinkPhase

    /** Open, or resume. False (and nothing sent) when it cannot start (no key). Returns at once; [phase] follows. */
    fun open(request: OpenRequest): Boolean

    /** Drop the paid session. The conversation (for resumption) and queued task notices stay. Tasks keep running. */
    fun close()

    /**
     * 16 kHz mono PCM; [count] samples of [pcm]. Only while [phase] is [LinkPhase.Ready]; see the
     * file header for threads and order. The mic reuses its buffer: copy what you keep past the call.
     */
    fun sendAudio(pcm: ShortArray, count: Int)

    /** Nothing in flight: no response, no tool round, no response we owe, no notice being injected or spoken. */
    fun isIdle(): Boolean

    /** A task notice (result, needs-you) waits to be spoken: the controller reopens for it. */
    fun hasPendingNotice(): Boolean

    /** The server hears the user now (while open). */
    fun userSpeaking(): Boolean

    /** The model's audio is queued or playing. */
    fun speaking(): Boolean

    /** How the last successful open went; null before the first. */
    val lastOpen: OpenKind?

    /** Worth trying to open now (e.g. the phone is online). False: a waiting result does not reopen yet; speech still tries. */
    fun canOpen(): Boolean = true
}

/** [LinkAudio.OwnsMic]: the host's mic capture steps aside while the link has the mic. */
interface MicHandoff {
    /** Stop the host's capture now (the link opens next). */
    fun release()

    /** Start the host's capture again (the link closed). */
    fun reclaim()

    companion object {
        val None = object : MicHandoff {
            override fun release() = Unit
            override fun reclaim() = Unit
        }
    }
}
