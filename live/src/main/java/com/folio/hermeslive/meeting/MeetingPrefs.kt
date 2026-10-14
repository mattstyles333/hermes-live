package com.folio.hermeslive.meeting

import android.content.SharedPreferences
import com.folio.hermes.telemetry.TelemetrySink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Settings › Meeting mode. Kept apart from `LiveSettings` (its own prefs file), so meeting mode
 * adds no field to the call settings.
 *
 * [enabled] is the one switch that ships dark: off, there is no Meeting button, no launcher
 * shortcut and nothing of meeting mode is built.
 */
data class MeetingSettings(
    /** Show the Meeting button and the "Take meeting notes" shortcut. Off by default (ships dark). */
    val enabled: Boolean = false,
    /** "Bill, note that …" / "Bill, start a task to …" during a meeting. */
    val wakeWord: Boolean = true,
    /** Keep transcripts on the phone this many days after the meeting, then delete them. */
    val retentionDays: Int = DEFAULT_RETENTION_DAYS,
) {
    companion object {
        const val DEFAULT_RETENTION_DAYS = 30
        val RETENTION_CHOICES = listOf(7, 14, 30, 90)
    }
}

class MeetingPrefs(private val prefs: SharedPreferences) {
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<MeetingSettings> = _settings.asStateFlow()

    fun update(transform: (MeetingSettings) -> MeetingSettings) {
        val next = transform(_settings.value).let { it.copy(retentionDays = it.retentionDays.coerceIn(1, 365)) }
        prefs.edit()
            .putBoolean(K_ENABLED, next.enabled)
            .putBoolean(K_WAKE, next.wakeWord)
            .putInt(K_RETENTION, next.retentionDays)
            .apply()
        _settings.value = next
    }

    private fun read() = MeetingSettings(
        enabled = prefs.getBoolean(K_ENABLED, false),
        wakeWord = prefs.getBoolean(K_WAKE, true),
        retentionDays = prefs.getInt(K_RETENTION, MeetingSettings.DEFAULT_RETENTION_DAYS),
    )

    companion object {
        const val PREFS = "hermes_live_meeting"
        private const val K_ENABLED = "meeting_enabled"
        private const val K_WAKE = "meeting_wake_word"
        private const val K_RETENTION = "meeting_retention_days"
    }
}

/**
 * Retention (Settings › Meeting mode › Keep transcripts): a meeting that ended more than N days
 * ago is deleted with its transcript, brief and tasks. Kept regardless: a live meeting, one that
 * has not ended, and one still **queued** for Hermes (it was asked to go and has not: deleting it
 * would lose it unsent). Returns how many were deleted.
 */
object MeetingRetention {
    const val DAY_MS = 86_400_000L

    fun prune(log: MeetingLog, nowWall: Long, days: Int, telemetry: TelemetrySink = TelemetrySink.None): Int {
        val cutoff = nowWall - days.coerceAtLeast(1) * DAY_MS
        val old = log.all().filter { m ->
            val ended = m.endedAt ?: return@filter false
            ended < cutoff && m.phase in setOf(MeetingPhase.Sent, MeetingPhase.Saved, MeetingPhase.Ended)
        }
        old.forEach { log.delete(it.id) }
        if (old.isNotEmpty()) runCatching { telemetry.event("meeting.deleted", mapOf("count" to old.size, "reason" to "retention"), false) }
        return old.size
    }
}
