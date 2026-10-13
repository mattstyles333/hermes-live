package com.folio.hermes.cascade.budget

import com.folio.hermes.cascade.CascadeSettings
import kotlinx.serialization.json.JsonObject

/**
 * Fits Jev's `state` into `CascadeSettings.jevStateCapChars` (R-CTX-1; v1 §9 priority order:
 * the current turn, uncertain spans, tasks, held text, recent, the conversation summary).
 * Compression applies to Jev only: Hermes always gets the full verbatim dictation (R-CTX-3).
 *
 * S0 ships [PassThrough] (today's behaviour: no cap). S-A owns this package and makes it real.
 */
fun interface JevStateBudget {
    fun fit(state: JsonObject, settings: CascadeSettings): JsonObject

    companion object {
        val PassThrough = JevStateBudget { state, _ -> state }
    }
}
