package com.folio.hermes.telemetry

/**
 * Where library code reports events, so tests can see exactly what would be sent. The default
 * forwards to [Telemetry] (a no-op until the app installs it). Same rule as [Telemetry]: event
 * names and safe attributes only (codes, enum reasons, durations, counts, booleans), never content.
 */
fun interface TelemetrySink {
    /** [warn] marks something that went wrong but the caller carried on. */
    fun event(name: String, attrs: Map<String, Any?>, warn: Boolean)

    companion object {
        /** Forwards to [Telemetry.event] / [Telemetry.warn] (no throwable: messages may echo content). */
        val Default: TelemetrySink = TelemetrySink { name, attrs, warn ->
            if (warn) Telemetry.warn(name, null, attrs) else Telemetry.event(name, attrs)
        }

        /** Drops everything. */
        val None: TelemetrySink = TelemetrySink { _, _, _ -> }
    }
}
