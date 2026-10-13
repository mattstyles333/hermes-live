package com.folio.hermeslive.data

import com.folio.hermes.cascade.ports.Store
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A correction the call learned: the speech model heard [heard], you meant [meant]. */
@Serializable
data class Correction(val heard: String, val meant: String, val count: Int = 1, val lastAtMs: Long = 0)

/**
 * Learned corrections (R-STT-7), kept in the cascade [Store] under [KEY] on this phone only.
 * The call side (S-F) records one with [learn] when the user corrects a word ("I said Jev, not
 * Jeff"); Settings › Voice › Vocabulary shows them and can [forget] one. [hotwords] is what the
 * speech models and the writer get: your vocabulary first, then what was learned.
 */
class VocabularyStore(private val store: () -> Store, private val now: () -> Long = System::currentTimeMillis) {
    private val serializer = ListSerializer(Correction.serializer())
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun corrections(): List<Correction> = store().get(KEY)
        ?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }
        .orEmpty()
        .sortedByDescending { it.lastAtMs }

    @Synchronized
    fun learn(heard: String, meant: String) {
        val h = heard.trim().take(LiveSettings.MAX_PHRASE_CHARS)
        val m = meant.trim().take(LiveSettings.MAX_PHRASE_CHARS)
        if (h.isEmpty() || m.isEmpty() || h.equals(m, ignoreCase = true)) return
        val all = corrections()
        val old = all.firstOrNull { it.heard.equals(h, ignoreCase = true) }
        val next = Correction(h, m, if (old?.meant.equals(m, ignoreCase = true)) old!!.count + 1 else 1, now())
        save(listOf(next) + all.filterNot { it.heard.equals(h, ignoreCase = true) })
    }

    @Synchronized
    fun forget(heard: String) = save(corrections().filterNot { it.heard.equals(heard, ignoreCase = true) })

    @Synchronized
    fun clear() = store().put(KEY, null)

    /** Your phrases, then the learned targets, without repeats. */
    fun hotwords(vocabulary: List<String>): List<String> =
        (vocabulary + corrections().map { it.meant }).distinctBy { it.lowercase() }

    private fun save(list: List<Correction>) {
        val kept = list.take(MAX_CORRECTIONS)
        store().put(KEY, if (kept.isEmpty()) null else json.encodeToString(serializer, kept))
    }

    companion object {
        const val KEY = "shell/vocabulary/corrections"
        const val MAX_CORRECTIONS = 100
    }
}
