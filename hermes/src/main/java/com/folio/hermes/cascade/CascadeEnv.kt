package com.folio.hermes.cascade

import com.folio.hermes.cascade.budget.JevStateBudget
import com.folio.hermes.cascade.conversation.ConversationPolicy
import com.folio.hermes.cascade.registry.TaskRegistry
import com.folio.hermes.cascade.routing.HandlerRegistry
import com.folio.hermes.cascade.store.StatePersistence

/**
 * What `reduce` is parameterised by: immutable configuration and the slice seams (spec v2 §12).
 * Every default is C1's behaviour. Each phase 1 slice plugs in here from its own package instead
 * of editing the reducer:
 * - [handlers] (`routing/`, S-A): one handler per route, turn hooks, result handling;
 * - [budget] (`budget/`, S-A): Jev's state cap;
 * - [persistence] (`store/`, S-B), [registry] (`registry/`, S-B), [conversation] (`conversation/`, S-B).
 */
class CascadeEnv(
    val bank: PhraseBank,
    val handlers: HandlerRegistry = HandlerRegistry.default(),
    val budget: JevStateBudget = JevStateBudget.PassThrough,
    val persistence: StatePersistence = StatePersistence.None,
    val registry: TaskRegistry = TaskRegistry.None,
    val conversation: ConversationPolicy = ConversationPolicy.None,
)
