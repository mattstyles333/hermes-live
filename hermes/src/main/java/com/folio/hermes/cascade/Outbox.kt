package com.folio.hermes.cascade

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * User turns waiting on the network, by turn id, sent again on reconnect.
 *
 * - [hold] keeps a turn once: the same id again, or an id already sent, is refused.
 * - [flush] sends held turns in order, one flush at a time; a turn that sends is removed and
 *   remembered, so a second flush (or a second reconnect) never sends it twice. The first failure
 *   stops the flush and keeps that turn and the ones after it.
 * - [markStage] / [stageDone] record which steps of a turn already happened (a stock line
 *   played, a task started), so a resend never repeats one: Bill does not say the same thing twice.
 */
class Outbox<T> {
    data class Entry<T>(val turnId: String, val payload: T)

    private val lock = Any()
    private val held = LinkedHashMap<String, T>()
    private val sent = LinkedHashSet<String>()
    private val stages = HashMap<String, MutableSet<String>>()
    private val flushing = Mutex()

    val size: Int get() = synchronized(lock) { held.size }

    fun ids(): List<String> = synchronized(lock) { held.keys.toList() }

    /** Hold [turnId]; false if it is already held or was already sent. */
    fun hold(turnId: String, payload: T): Boolean = synchronized(lock) {
        if (turnId in held || turnId in sent) return false
        held[turnId] = payload
        true
    }

    fun markStage(turnId: String, stage: String): Unit = synchronized(lock) {
        stages.getOrPut(turnId) { mutableSetOf() } += stage
        trim()
    }

    fun stageDone(turnId: String, stage: String): Boolean = synchronized(lock) { stages[turnId]?.contains(stage) == true }

    /** Send held turns in order with [send] (true = sent). Returns how many were sent. */
    suspend fun flush(send: suspend (Entry<T>) -> Boolean): Int = flushing.withLock {
        var count = 0
        while (true) {
            val entry = synchronized(lock) { held.entries.firstOrNull()?.let { Entry(it.key, it.value) } } ?: break
            if (!send(entry)) break
            synchronized(lock) {
                held.remove(entry.turnId)
                sent += entry.turnId
                trim()
            }
            count++
        }
        count
    }

    private fun trim() {
        while (sent.size > KEEP) sent.remove(sent.first())
        if (stages.size > KEEP) stages.keys.filter { it !in held }.take(stages.size - KEEP).forEach { stages.remove(it) }
    }

    private companion object {
        const val KEEP = 200
    }
}
