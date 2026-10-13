package com.folio.hermeslive.call

import kotlin.math.sqrt

/** One stats object from `PeerConnection.getStats`, reduced to what the level needs. */
data class StatsEntry(val type: String, val members: Map<String, Any?>)

/**
 * Hermes's audio level from WebRTC stats, polled about every 150 ms.
 *
 * Reads the inbound audio RTP stream's `audioLevel` (0..1). Builds that do not report it get the
 * level from the change in `totalAudioEnergy` over `totalSamplesDuration` since the last poll
 * (`sqrt(dE / dT)`, the same scale). Tolerant of missing or oddly typed members.
 */
class RemoteLevelReader {
    private var lastEnergy: Double? = null
    private var lastDuration: Double? = null

    /** The level now, or null when the stats hold no inbound audio stream. */
    fun read(entries: Collection<StatsEntry>): Double? {
        val inbound = entries.firstOrNull { it.type == "inbound-rtp" && it.isAudio() } ?: return null
        val energy = inbound.members["totalAudioEnergy"].asDouble()
        val duration = inbound.members["totalSamplesDuration"].asDouble()
        val fromEnergy = if (energy != null && duration != null) {
            val pe = lastEnergy
            val pd = lastDuration
            lastEnergy = energy
            lastDuration = duration
            if (pe != null && pd != null && duration > pd && energy >= pe) sqrt((energy - pe) / (duration - pd)) else null
        } else {
            null
        }
        val direct = inbound.members["audioLevel"].asDouble()
        return (direct ?: fromEnergy)?.coerceIn(0.0, 1.0)
    }

    fun reset() {
        lastEnergy = null
        lastDuration = null
    }

    private fun StatsEntry.isAudio(): Boolean {
        val kind = members["kind"] ?: members["mediaType"]
        return kind?.toString() == "audio"
    }

    private fun Any?.asDouble(): Double? = when (this) {
        is Number -> toDouble()
        is String -> toDoubleOrNull()
        else -> null
    }
}
