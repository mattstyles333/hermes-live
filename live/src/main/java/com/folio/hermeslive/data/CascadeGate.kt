package com.folio.hermeslive.data

import com.folio.hermes.cascade.CascadeSettings
import com.folio.hermes.telemetry.TelemetrySink

/**
 * R-Q-1: the cascade runs only when the user picked it **and** a usable OpenRouter key is saved.
 * Asking never decrypts the key and never builds anything.
 */
class CascadeGate(
    private val settings: () -> LiveSettings,
    private val key: () -> KeyStatus,
    /** The xAI key (grok-voice slice); none by default. */
    private val xaiKey: () -> KeyStatus = { KeyStatus() },
    /** GPT-Live › Parallel agents is switched on ([GptLivePrefs]; off by default). */
    private val gptLiveAgents: () -> Boolean = { false },
) {
    /** Bill: engine `cascade` and a usable OpenRouter key. */
    val active: Boolean get() = settings().voiceEngine == VoiceEngine.Cascade && key().usable

    /** Grok Voice: engine Grok and a usable xAI key. */
    val grokActive: Boolean get() = settings().voiceEngine == VoiceEngine.Grok && xaiKey().usable

    /** GPT-Live with parallel agents (Settings, off by default): its delegations run as tasks on the shared task API. */
    val gptLiveAgentsActive: Boolean get() = settings().voiceEngine == VoiceEngine.GptLive && gptLiveAgents()

    /**
     * The Hermes task machinery (the runtime: actor, store, gateway, off-call service) is wanted:
     * Bill, Grok or GPT-Live with agents (which drive the same tasks through the shared task API).
     */
    val tasksActive: Boolean get() = active || grokActive || gptLiveAgentsActive

    /** Bill could run a call (a usable OpenRouter key), whichever engine is chosen: Grok's offline fallback. */
    val billAvailable: Boolean get() = key().usable
}

/** What the cascade needs from the app shell when it is built. Everything is lazy or a provider. */
class CascadeEnvironment(
    /** Read on every use, so a Settings change applies at once. */
    val settings: () -> CascadeSettings,
    /** The OpenRouter key (decrypts; call only when a request is about to go out). */
    val key: () -> String?,
    val storage: CascadeStorage,
    val speechModels: SpeechModels,
    val telemetry: TelemetrySink,
)

/** The running cascade (S-F: actor, STT, OpenRouter, Hermes task sessions). Closed when the gate shuts. */
interface CascadeRuntime : AutoCloseable {
    companion object {
        val Empty: CascadeRuntime = object : CascadeRuntime {
            override fun close() = Unit
        }
    }
}

/** Builds the cascade. S-F implements it; until then [None] builds nothing that does anything. */
fun interface CascadeFactory {
    fun create(env: CascadeEnvironment): CascadeRuntime

    companion object {
        val None: CascadeFactory = CascadeFactory { CascadeRuntime.Empty }
    }
}

/**
 * The one way to get a cascade: [runtime] builds it through [factory] only while [gate] is open
 * (Bill, or Grok for its tasks: [CascadeGate.tasksActive]), and closes it as soon as the gate shuts (engine switched back, key removed or refused). With
 * the engine on `gpt-live`, or no key, nothing is built: no speech-to-text, no OpenRouter, no
 * Hermes task sessions, no off-call service (R-Q-1; `CascadeOffStateTest`).
 */
class CascadeShell(
    val gate: CascadeGate,
    private val factory: CascadeFactory,
    private val environment: () -> CascadeEnvironment,
) {
    private var current: CascadeRuntime? = null

    @Synchronized
    fun runtime(): CascadeRuntime? {
        if (!gate.tasksActive) {
            release()
            return null
        }
        return current ?: factory.create(environment()).also { current = it }
    }

    /** The runtime if one is built, without building it. */
    @Synchronized
    fun existing(): CascadeRuntime? = current

    @Synchronized
    fun release() {
        current?.let { runCatching { it.close() } }
        current = null
    }
}
