package com.folio.hermeslive.ui.cascade

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import com.folio.hermes.cascade.Event
import com.folio.hermes.cascade.ResumeChoice
import com.folio.hermes.cascade.TaskTap

/** A haptic the screen asks for; the app decides whether and how to vibrate. */
enum class HapticIntent {
    /** A light tick: a turn was committed. */
    TurnCommitted,

    /** A double tick: an agent finished. */
    TaskFinished,
}

/**
 * Everything the cascade call screen can ask for. The screen never acts itself: it calls these
 * and the engine (S-F) turns them into actor [Event]s ([EventIntents]) or app actions (mute,
 * end, speaker, haptics).
 */
@Stable
interface CascadeIntents {
    /** A task card action: Cancel (after the 3 s Undo), Retry, ReadAll, Play, Tell. */
    fun task(taskId: String, tap: TaskTap) {}

    /**
     * The user picked [choice] for word [index] of turn [turnId]. [index] is the position in
     * `TurnUi.words` (all words, not only the uncertain ones).
     */
    fun wordChosen(turnId: String, index: Int, choice: String) {}

    fun resume(choice: ResumeChoice) {}

    /** Hold-to-talk on the orb. Always followed by `false`, also on cancel and dispose. */
    fun holdToTalk(pressed: Boolean) {}

    fun mute() {}
    fun end() {}
    fun speaker() {}

    /** After the call ended: leave this screen for home (Close, Back, a tap on the summary, or its hold running out). */
    fun closeEnded() {}

    /** After the call ended: start a new call ("Talk again", or "Reconnect" after a drop). */
    fun callAgain() {}

    /** A past conversation was picked in the history list (no S0 event yet; see the S-D report). */
    fun openConversation(conversationId: String) {}

    fun haptic(intent: HapticIntent) {}
}

/** Does nothing (previews, renders). */
object NoCascadeIntents : CascadeIntents

/**
 * Maps the screen's intents onto the actor's [Event]s; app-level intents go to [app].
 * [post] is typically `actor::send`.
 */
@Immutable
class EventIntents(
    private val post: (Event) -> Unit,
    private val app: CascadeIntents = NoCascadeIntents,
) : CascadeIntents {
    override fun task(taskId: String, tap: TaskTap) = post(Event.TaskTapped(taskId, tap))
    override fun wordChosen(turnId: String, index: Int, choice: String) = post(Event.WordChosen(turnId, index, choice))
    override fun resume(choice: ResumeChoice) = post(Event.ResumeChosen(choice))
    override fun holdToTalk(pressed: Boolean) = post(Event.HoldToTalk(pressed))
    override fun mute() = app.mute()
    override fun end() = app.end()
    override fun speaker() = app.speaker()
    override fun closeEnded() = app.closeEnded()
    override fun callAgain() = app.callAgain()
    override fun openConversation(conversationId: String) = app.openConversation(conversationId)
    override fun haptic(intent: HapticIntent) = app.haptic(intent)
}
