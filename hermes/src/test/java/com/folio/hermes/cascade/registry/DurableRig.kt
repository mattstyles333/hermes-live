package com.folio.hermes.cascade.registry

import com.folio.hermes.cascade.CascadeActor
import com.folio.hermes.cascade.CascadeEvent
import com.folio.hermes.cascade.CascadeSettings
import com.folio.hermes.cascade.Effect
import com.folio.hermes.cascade.FakeAudioOut
import com.folio.hermes.cascade.FakeOpenRouter
import com.folio.hermes.cascade.PhraseBank
import com.folio.hermes.cascade.State
import com.folio.hermes.cascade.TestClock
import com.folio.hermes.cascade.answer
import com.folio.hermes.cascade.conversation.ReconnectRouter
import com.folio.hermes.cascade.ports.MemoryStore
import com.folio.hermes.cascade.ports.Store
import com.folio.hermes.cascade.store.durableEnv
import com.folio.hermes.openrouter.JevAnswer
import com.folio.hermes.openrouter.OpenRouterResult
import com.folio.hermes.telemetry.TelemetrySink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent

/**
 * One app process on the durable env: an actor over [store] talking to [server] (both outlive
 * the rig, so a second rig is a restart). Virtual clock, fake ports, no network, no keys.
 */
class DurableRig(
    val scope: TestScope,
    val store: Store = MemoryStore(),
    val server: FakeServer = FakeServer(),
    settings: CascadeSettings = CascadeSettings(),
    reconnect: ReconnectRouter? = null,
    recover: Boolean = true,
) : AutoCloseable {
    val job = SupervisorJob()
    val runScope = CoroutineScope(scope.coroutineContext + job)
    val clock = TestClock(scope)
    val openRouter = FakeOpenRouter(clock).apply {
        jevDefault = FakeOpenRouter.JevScript(10, NEW_TASK)
        plan = { prompt -> OpenRouterResult.Ok("LABEL: ${label(prompt)}\nPROMPT: ${request(prompt)}\nSAY: On it.") }
    }
    val hermes = ClientHermes(server)
    val events = mutableListOf<CascadeEvent>()
    val appEffects = mutableListOf<Effect>()
    var settings: CascadeSettings = settings
    val actor = CascadeActor(
        scope = runScope, env = durableEnv(BANK, reconnect = reconnect), settings = { this.settings },
        openRouter = openRouter, hermes = hermes, audio = FakeAudioOut(clock), clock = clock, store = store,
        telemetry = TelemetrySink.None, onAppEffect = { appEffects += it }, seed = 5,
    )

    init {
        runScope.launch { actor.events.collect { events += it } }
        scope.runCurrent()
        actor.start()
        scope.runCurrent()
        if (recover) TaskRecovery(hermes, hermes).launch(runScope, actor)
        scope.runCurrent()
    }

    val state: State get() = actor.state.value

    fun say(text: String, jev: OpenRouterResult<Map<String, JevAnswer>>? = null) {
        jev?.let { openRouter.jevScripts += FakeOpenRouter.JevScript(10, it) }
        actor.speechStarted()
        actor.final(text)
        actor.speechEnded()
        scope.advanceTimeBy(2_000)
        scope.runCurrent()
    }

    override fun close() = job.cancel()

    companion object {
        val BANK: PhraseBank = PhraseBank.load()
        val NEW_TASK = OpenRouterResult.Ok(
            mapOf("turn" to answer("done"), "action" to answer("new_task"), "line" to answer("start.lookup"), "enough" to answer("line_then_writer")),
        )

        fun jev(vararg pairs: Pair<String, String>) = OpenRouterResult.Ok(mapOf("turn" to answer("done")) + pairs.associate { (k, v) -> k to answer(v) })

        /** The request text from the writer's plan prompt (the brief is the request itself, so submits are recognisable). */
        fun request(prompt: String) = prompt.substringAfter("The request: \"").trimEnd('"')

        /** First two words of the request, as the label. */
        fun label(prompt: String) = request(prompt).split(' ').take(2).joinToString(" ")
    }
}
