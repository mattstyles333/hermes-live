package com.folio.hermeslive.call

import com.folio.hermes.live.LiveTurn
import com.folio.hermes.live.LiveTranscriptFragment

/**
 * The call's captions: transcript deltas merged into speaker turns. Survives a reconnect, so the
 * new voice session is seeded with [history] and the screen keeps the whole call.
 */
class CaptionLog(private val maxTurns: Int = 400) {
    private val turns = ArrayList<Caption>()

    val captions: List<Caption> get() = turns.toList()

    fun append(fragment: LiveTranscriptFragment) {
        if (fragment.text.isEmpty()) return
        val last = turns.lastOrNull()
        if (last != null && last.speaker == fragment.speaker) {
            turns[turns.lastIndex] = last.copy(text = last.text + fragment.text)
        } else {
            turns += Caption(fragment.speaker, fragment.text.trimStart())
        }
        if (turns.size > maxTurns) turns.subList(0, turns.size - maxTurns).clear()
    }

    /** A typed request (share sheet, Folio's PROMPT) shown as the user's line. */
    fun appendUserText(text: String) {
        turns += Caption(com.folio.hermes.live.Speaker.User, text)
    }

    /** Turns for `VoiceLiveApi.history` when reconnecting. */
    fun history(): List<LiveTurn> = turns.map { LiveTurn(it.speaker, it.text) }

    fun clear() = turns.clear()
}
