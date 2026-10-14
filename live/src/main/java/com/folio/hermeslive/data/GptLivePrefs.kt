package com.folio.hermeslive.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * GPT-Live's own switches (gptlive-parallel slice), in their own preferences file so they never
 * touch [LiveSettings] / [SettingsStore]. Parallel agents: on by default (hub decision, 1.3.0
 * integration); they run only while signed in ([CascadeGate.gptLiveAgentsActive]). On, each GPT-Live
 * delegation runs as its own task on the shared task API; off, the call is the single Hermes
 * conversation.
 */
class GptLivePrefs(private val prefs: SharedPreferences) {
    private val _agents = MutableStateFlow(prefs.getBoolean(K_AGENTS, AGENTS_DEFAULT))

    /** GPT-Live › Parallel agents. */
    val agents: StateFlow<Boolean> = _agents.asStateFlow()

    fun setAgents(on: Boolean) {
        prefs.edit().putBoolean(K_AGENTS, on).apply()
        _agents.value = on
    }

    companion object {
        const val PREFS = "hermes_live_gptlive"
        private const val K_AGENTS = "parallel_agents"

        /** GPT-Live › Parallel agents with nothing saved: on (they still need a sign-in). */
        const val AGENTS_DEFAULT = true

        @Volatile private var instance: Pair<Context, GptLivePrefs>? = null

        /** The process's prefs (rebuilt for another application object: Robolectric makes one per test). */
        fun of(context: Context): GptLivePrefs {
            val app = context.applicationContext
            instance?.takeIf { it.first === app }?.let { return it.second }
            return synchronized(this) {
                instance?.takeIf { it.first === app }?.second
                    ?: GptLivePrefs(app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)).also { instance = app to it }
            }
        }
    }
}
