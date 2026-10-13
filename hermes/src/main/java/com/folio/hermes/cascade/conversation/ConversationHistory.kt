package com.folio.hermes.cascade.conversation

import com.folio.hermes.cascade.State
import com.folio.hermes.cascade.ports.Store
import com.folio.hermes.cascade.store.ConversationDto
import com.folio.hermes.cascade.store.CoreDto
import com.folio.hermes.cascade.store.Snapshots
import com.folio.hermes.cascade.store.StoreCodec
import com.folio.hermes.cascade.store.StoreKeys

/** One conversation in the history list (for the UI). [title] is the start of the first thing the user said. */
data class PastConversation(
    val id: String,
    val startedWallMs: Long,
    /** Null for the current conversation. */
    val endedWallMs: Long?,
    val title: String,
    val exchanges: Int,
    val summary: String,
    val taskIds: List<String>,
    val unheard: Int,
    val current: Boolean,
)

/** Past conversations, newest first, at most `conversationsKept` (the current one included; R-CONV-1). */
object ConversationHistory {
    const val TITLE_CHARS = 60

    /** [current] is the live state if a call actor is running; otherwise the stored one is used. */
    fun list(store: Store, current: State? = null, kept: Int = current?.settings?.conversationsKept ?: 10): List<PastConversation> {
        val live = current?.let { Snapshots.conversation(it) }
            ?: StoreCodec.decode(CoreDto.serializer(), store.get(StoreKeys.CORE))?.conversation?.takeIf { it.id.isNotEmpty() }
        val ended = store.keys(StoreKeys.CONVERSATION_PREFIX)
            .mapNotNull { StoreCodec.decode(ConversationDto.serializer(), store.get(it)) }
            .filter { it.id.isNotEmpty() && it.id != live?.id }
            .sortedByDescending { it.startedWallMs }
        return (listOfNotNull(live?.let { it.past(current = true) }) + ended.map { it.past(current = false) }).take(kept.coerceAtLeast(0))
    }

    private fun ConversationDto.past(current: Boolean) = PastConversation(
        id = id,
        startedWallMs = startedWallMs,
        endedWallMs = if (current) null else endedWallMs,
        title = recent.firstOrNull { it.user.isNotBlank() }?.user?.trim()?.let { if (it.length > TITLE_CHARS) it.take(TITLE_CHARS - 1) + "…" else it }.orEmpty(),
        exchanges = recent.size,
        summary = summary,
        taskIds = taskIds,
        unheard = unheard.size,
        current = current,
    )
}
