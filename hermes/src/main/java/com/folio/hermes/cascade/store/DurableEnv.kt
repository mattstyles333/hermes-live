package com.folio.hermes.cascade.store

import com.folio.hermes.cascade.CascadeEnv
import com.folio.hermes.cascade.PhraseBank
import com.folio.hermes.cascade.budget.JevStateBudget
import com.folio.hermes.cascade.conversation.DurableConversations
import com.folio.hermes.cascade.conversation.ReconnectRouter
import com.folio.hermes.cascade.registry.DurableTaskRegistry
import com.folio.hermes.cascade.routing.HandlerRegistry

/**
 * The S-B seams together: [DurablePersistence], [DurableTaskRegistry] and [DurableConversations]
 * (S-A's [reconnect] routing plugs into the last). S-F builds the actor with this env, a
 * file-backed `Store` (S-E) and, after `start()`, one
 * [com.folio.hermes.cascade.registry.TaskRecovery] run.
 */
fun durableEnv(
    bank: PhraseBank,
    handlers: HandlerRegistry = HandlerRegistry.default(),
    budget: JevStateBudget = JevStateBudget.PassThrough,
    reconnect: ReconnectRouter? = null,
): CascadeEnv = CascadeEnv(
    bank = bank,
    handlers = handlers,
    budget = budget,
    persistence = DurablePersistence,
    registry = DurableTaskRegistry,
    conversation = DurableConversations(reconnect),
)
