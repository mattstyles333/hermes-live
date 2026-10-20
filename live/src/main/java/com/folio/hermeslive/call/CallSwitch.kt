package com.folio.hermeslive.call

import com.folio.hermes.ApprovalChoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which engine runs a call. */
enum class Engine { GptLive, Cascade, Grok, Memo }

/** Can a call start now, and on which engine. */
sealed interface CallPlan {
    data class Ready(val engine: Engine) : CallPlan

    /** Bill is chosen but his speech models are not on the phone yet ("Preparing Bill…"). */
    data object PreparingBill : CallPlan

    /**
     * Custom is chosen but has no voice at all (Hermes's not reachable and no OpenRouter key): the
     * "preparing" screen says so instead of starting a silent call. Today's gate already needs an
     * OpenRouter key for Custom, so this cannot happen yet (Hermes voice slice).
     */
    data object NoVoice : CallPlan
}

/**
 * The engine switch (spec v2 §12 phase 2): one [VoiceCall] for the app that hands each call to
 * GPT-Live ([CallEngine], today's code path, untouched) or to Bill (the cascade), chosen when the
 * call starts and kept until it ends.
 *
 * R-Q-1: Bill is chosen only while [cascadeActive] (engine `cascade` **and** a usable key). With
 * GPT-Live nothing cascade-related is built: [cascade] is never called (`EngineSwitchTest`).
 * Grok (grok-voice slice) only while [grokActive] (engine Grok **and** a usable xAI key); otherwise
 * [grok] is never called (`GrokOffStateTest`).
 *
 * Offline memos (offline-memo slice; Settings › Voice › Offline memos, off by default) are the
 * first branch while [memoActive]: a memo asked for (the Memo shortcut, the tile offline, "Record a
 * memo"), whatever the engine, or a GPT-Live call with the phone offline. Custom and Grok keep
 * their own offline paths. Off, [memo] is never called and [online] never asked.
 *
 * [state] is the active engine's own `StateFlow`, read when asked: the call service reads it right
 * after [start] and so watches the engine that took the call (a mirrored flow could hand it the
 * other engine's idle state first, and the service would stop the microphone).
 */
class CallSwitch(
    private val gptLive: () -> VoiceCall,
    private val cascade: () -> VoiceCall,
    private val cascadeActive: () -> Boolean,
    /** True when Bill's speech models are ready for a call. Asked only when Bill is chosen. */
    private val modelsReady: () -> Boolean,
    /** Grok Voice's engine (built on first use, only while [grokActive]). */
    private val grok: () -> VoiceCall = { error("no Grok engine") },
    /** Engine Grok **and** a usable xAI key (grok-voice slice; off by default). */
    private val grokActive: () -> Boolean = { false },
    /** Bill could take a call now even with another engine chosen (a usable OpenRouter key): Grok's offline fallback. */
    private val billAvailable: () -> Boolean = { false },
    /** Custom has a voice now (Hermes's or OpenRouter's). Asked only when Bill would take the call. */
    private val voiceReady: () -> Boolean = { true },
    /** A new offline memo recorder (one per memo, only while [memoActive]). */
    private val memo: () -> VoiceCall = { error("no memo recorder") },
    /** Settings › Voice › Offline memos is on. */
    private val memoActive: () -> Boolean = { false },
    /** The phone has a validated network now. Asked only while [memoActive]. */
    private val online: () -> Boolean = { true },
) : VoiceCall {
    private val _active = MutableStateFlow<VoiceCall?>(null)
    private var builtCascade: VoiceCall? = null
    private var builtGrok: VoiceCall? = null

    /** The engine of the current (or last) call, null before the first. */
    val active: StateFlow<VoiceCall?> = _active.asStateFlow()

    /** The engine that would run [request] now. */
    fun plan(request: CallRequest = CallRequest()): CallPlan {
        val base = basePlan(request)
        if (request.forceBill || !memoActive()) return base
        return if (request.memo || (base == CallPlan.Ready(Engine.GptLive) && !request.forceGptLive && !online())) CallPlan.Ready(Engine.Memo) else base
    }

    private fun basePlan(request: CallRequest): CallPlan = when {
        request.forceGptLive -> CallPlan.Ready(Engine.GptLive)
        request.forceBill && (cascadeActive() || billAvailable()) && modelsReady() -> CallPlan.Ready(Engine.Cascade)
        grokActive() -> CallPlan.Ready(Engine.Grok)
        !cascadeActive() -> CallPlan.Ready(Engine.GptLive)
        !modelsReady() -> CallPlan.PreparingBill
        !voiceReady() -> CallPlan.NoVoice
        else -> CallPlan.Ready(Engine.Cascade)
    }

    /** The Grok engine if a Grok call ever ran in this process (tests). */
    val grokEngine: VoiceCall? get() = builtGrok

    /** The cascade engine if a cascade call ever ran in this process (tests, the e2e hooks). */
    val cascadeEngine: VoiceCall? get() = builtCascade

    /** Bill's engine while Bill is chosen with a usable key (built on first use), else null. */
    fun bill(): VoiceCall? = if (cascadeActive()) builtCascade ?: cascade().also { builtCascade = it } else null

    private fun current(): VoiceCall = _active.value ?: gptLive().also { _active.value = it }

    override val state: StateFlow<CallUiState> get() = current().state

    /**
     * Start a call on the engine [plan] picks; while one is running, [request] goes to it. A plan
     * of [CallPlan.PreparingBill] starts nothing (the activity shows "Preparing Bill…" first).
     */
    override fun start(request: CallRequest) {
        _active.value?.takeIf { it.state.value.inCall }?.let {
            it.start(request)
            return
        }
        val engine = when (val p = plan(request)) {
            is CallPlan.Ready -> p.engine
            CallPlan.PreparingBill, CallPlan.NoVoice -> return
        }
        val call = when (engine) {
            Engine.Cascade -> builtCascade ?: cascade().also { builtCascade = it }
            Engine.Grok -> builtGrok ?: grok().also { builtGrok = it }
            Engine.GptLive -> gptLive()
            Engine.Memo -> memo()
        }
        _active.value?.takeIf { it !== call }?.dismissSummary()
        _active.value = call
        call.start(if (engine == Engine.Memo && !request.memo) request.copy(memo = true, memoCause = MemoCause.Offline) else request)
    }

    override fun end() = current().end()
    override fun toggleMute() = current().toggleMute()
    override fun selectRoute(option: RouteOption) = current().selectRoute(option)
    override fun submitText(text: String) = current().submitText(text)
    override fun answerApproval(id: String, choice: ApprovalChoice) = current().answerApproval(id, choice)
    override fun answerClarify(id: String, answers: Map<String, String?>) = current().answerClarify(id, answers)
    override fun dismissSummary() = current().dismissSummary()
}
