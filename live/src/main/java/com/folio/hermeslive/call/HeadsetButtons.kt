package com.folio.hermeslive.call

import android.view.KeyEvent

/** What a headset button press does during a call. */
enum class HeadsetCommand { ToggleMute, EndCall }

/**
 * Headset button rules: a single press of the hook / play-pause button toggles mute; a long press
 * (held about a second, delivered as a repeat or FLAG_LONG_PRESS) ends the call. Starting a call
 * from the headset is the system's long press → `VOICE_COMMAND` intent, which opens Hermes Live
 * when it is the chosen voice app.
 */
object HeadsetButtons {
    private val KEYS = setOf(
        KeyEvent.KEYCODE_HEADSETHOOK,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE,
    )

    /**
     * Map one key event. The long press fires on the first repeat (ACTION_DOWN); the short press on
     * ACTION_UP when no long press fired. [longPressHandled] is the caller's memory for this press.
     */
    fun map(keyCode: Int, action: Int, repeatCount: Int, longPress: Boolean, longPressHandled: Boolean): HeadsetCommand? {
        if (keyCode !in KEYS) return null
        return when (action) {
            KeyEvent.ACTION_DOWN -> if ((repeatCount > 0 || longPress) && !longPressHandled) HeadsetCommand.EndCall else null
            KeyEvent.ACTION_UP -> if (!longPressHandled) HeadsetCommand.ToggleMute else null
            else -> null
        }
    }
}
