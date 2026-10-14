package com.folio.hermeslive.service

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.folio.hermes.cascade.AudioClip
import com.folio.hermes.cascade.CascadeSettings
import com.folio.hermes.telemetry.TelemetrySink
import com.folio.hermeslive.data.CascadeGate
import com.folio.hermeslive.data.SafeTelemetry
import com.folio.hermeslive.data.VoiceShell
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What "Hear it" plays: [clips] in order. [title] is generic (it shows on the lock screen). */
class Briefing(val clips: List<AudioClip>, val title: String = "Bill · result")

/**
 * What the off-call service and "Hear it" need from the cascade (R-CONV-3). S-F implements it
 * (through `CascadeHost.offCallHooks`); [None] keeps nothing open and plays nothing.
 */
interface OffCallHooks {
    /** Voice tasks still running. The off-call service stays while this is above zero. */
    val tasksRunning: StateFlow<Int>

    /** Keep the Hermes gateway connected (no mic) until the returned handle is closed. */
    fun holdGateway(): AutoCloseable

    /** The clips for "Hear it" on [taskId], or the opening briefing when null (R-CONV-4). Null: nothing to play. */
    suspend fun briefing(taskId: String?): Briefing?

    companion object {
        val None: OffCallHooks = object : OffCallHooks {
            override val tasksRunning: StateFlow<Int> = MutableStateFlow(0).asStateFlow()
            override fun holdGateway(): AutoCloseable = AutoCloseable { }
            override suspend fun briefing(taskId: String?): Briefing? = null
        }
    }
}

/** Why the off-call service stopped. */
enum class OffCallStop(val wire: String) {
    TasksDone("tasks_done"),
    Timeout("timeout"),
    CascadeOff("cascade_off"),
    Stopped("stopped"),
    Refused("refused"),
}

/** The off-call rules, pure (JVM-tested). */
object OffCallPolicy {
    /** Keep going after hang-up only when the cascade is on and something is still running. */
    fun shouldStart(cascadeActive: Boolean, tasksRunning: Int): Boolean = cascadeActive && tasksRunning > 0

    /** Why to stop now, or null to carry on. */
    fun stopReason(cascadeActive: Boolean, tasksRunning: Int, elapsedMs: Long, keepAliveMs: Long): OffCallStop? = when {
        !cascadeActive -> OffCallStop.CascadeOff
        tasksRunning <= 0 -> OffCallStop.TasksDone
        elapsedMs >= keepAliveMs -> OffCallStop.Timeout
        else -> null
    }

    fun runningText(n: Int): String = if (n == 1) "1 task running" else "$n tasks running"
}

/**
 * The hooks S-F calls (R-CONV-3, R-CONV-4):
 *
 * - [afterHangUp] when a cascade call ends: starts [OffCallService] only if the cascade is on and
 *   tasks are running. Call it while the call is still in the foreground (before `CallService`
 *   stops): Android 12+ refuses a foreground-service start from the background, and then this
 *   returns false and only the result notifications remain.
 * - [stop] ends it early (a new call started, sign-out).
 * - [postResult] posts a finished task's notification ("Hear it", "Open").
 * - [playBriefing] plays a briefing or a result with the mic off ([BriefingService]).
 */
object OffCall {
    fun afterHangUp(
        context: Context,
        tasksRunning: Int,
        gate: CascadeGate = VoiceShell.of(context).gate,
        keepAliveMs: Long = CascadeSettings().offCallKeepAliveMs,
        telemetry: TelemetrySink = SafeTelemetry(),
    ): Boolean {
        if (!OffCallPolicy.shouldStart(gate.active, tasksRunning)) return false
        val intent = Intent(context, OffCallService::class.java).setAction(OffCallService.ACTION_START)
            .putExtra(OffCallService.EXTRA_KEEP_ALIVE_MS, keepAliveMs)
            .putExtra(OffCallService.EXTRA_TASKS, tasksRunning)
        return try {
            ContextCompat.startForegroundService(context, intent)
            true
        } catch (e: ForegroundServiceStartNotAllowedException) {
            runCatching { telemetry.event("offcall.stop", mapOf("reason" to OffCallStop.Refused.wire, "tasks" to tasksRunning), true) }
            false
        } catch (e: IllegalStateException) {
            runCatching { telemetry.event("offcall.stop", mapOf("reason" to OffCallStop.Refused.wire, "tasks" to tasksRunning), true) }
            false
        }
    }

    fun stop(context: Context) {
        // Not running and the app in the background: Android refuses the start, and there is nothing to stop.
        runCatching { context.startService(Intent(context, OffCallService::class.java).setAction(OffCallService.ACTION_STOP)) }
    }

    /** Post [notice]; false when notifications are off for the app. */
    fun postResult(context: Context, notice: TaskResultNotice): Boolean = ResultNotifications.post(context, notice)

    /** Play [taskId]'s result, or the opening briefing when null. Call from the foreground or a notification action. */
    fun playBriefing(context: Context, taskId: String?) {
        ContextCompat.startForegroundService(context, BriefingService.playIntent(context, taskId))
    }
}
