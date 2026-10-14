package com.folio.hermeslive.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * GPT-Live's own switches (gptlive-parallel slice), in their own preferences file so they never
 * touch [LiveSettings] / [SettingsStore]. Parallel agents: off by default; on, each GPT-Live
 * delegation runs as its own task on the shared task API.
 */
class GptLivePrefs(private val prefs: SharedPreferences) {
    private val _agents = MutableStateFlow(prefs.getBoolean(K_AGENTS, false))

    /** GPT-Live › Parallel agents. */
    val agents: StateFlow<Boolean> = _agents.asStateFlow()

    fun setAgents(on: Boolean) {
        prefs.edit().putBoolean(K_AGENTS, on).apply()
        _agents.value = on
    }

    companion object {
        const val PREFS = "hermes_live_gptlive"
        private const val K_AGENTS = "parallel_agents"

        @Volatile private var instance: GptLivePrefs? = null

        fun of(context: Context): GptLivePrefs = instance ?: synchronized(this) {
            instance ?: GptLivePrefs(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)).also { instance = it }
        }
    }
}
