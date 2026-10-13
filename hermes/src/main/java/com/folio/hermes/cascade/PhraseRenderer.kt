package com.folio.hermes.cascade

import com.folio.hermes.openrouter.OpenRouterResult
import com.folio.hermes.telemetry.TelemetrySink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** "Preparing Bill's voice": [done] of [total] lines are ready ([cached] were already there), [failed] were skipped. */
data class RenderProgress(val total: Int = 0, val done: Int = 0, val cached: Int = 0, val failed: Int = 0, val finished: Boolean = false)

/**
 * Renders every line of every slot into the [TtsCache] through [voice], at most [parallel]
 * requests at a time. A line already cached (same text, voice, model and format) is not fetched,
 * so a new bank version, voice or model re-renders only the lines whose key changed. A line
 * shared by two slots is fetched once. A failure has already retried once (the speech client's
 * retry) and is skipped; the writer covers a missing stock line.
 */
class PhraseRenderer(
    private val voice: BillVoice,
    private val parallel: Int = 4,
    private val telemetry: TelemetrySink = TelemetrySink.Default,
) {
    private val _progress = MutableStateFlow(RenderProgress())
    val progress: StateFlow<RenderProgress> = _progress.asStateFlow()

    suspend fun render(bank: PhraseBank): RenderProgress = coroutineScope {
        val lines = bank.allLines
        val missing = lines.filterNot { voice.isCached(it) }
        _progress.value = RenderProgress(total = lines.size, done = lines.size - missing.size, cached = lines.size - missing.size)
        val gate = Semaphore(parallel.coerceIn(1, 8))
        val started = System.nanoTime()
        missing.map { line ->
            async {
                val ok = gate.withPermit { voice.clip(line) is OpenRouterResult.Ok }
                synchronized(_progress) {
                    val p = _progress.value
                    _progress.value = if (ok) p.copy(done = p.done + 1) else p.copy(failed = p.failed + 1)
                }
            }
        }.awaitAll()
        val final = _progress.value.copy(finished = true)
        _progress.value = final
        runCatching {
            telemetry.event(
                "cascade.render",
                mapOf(
                    "version" to bank.version, "total" to final.total, "cached" to final.cached, "fetched" to missing.size - final.failed,
                    "failed" to final.failed, "ms" to (System.nanoTime() - started) / 1_000_000,
                ),
                warn = final.failed > 0,
            )
        }
        final
    }
}
