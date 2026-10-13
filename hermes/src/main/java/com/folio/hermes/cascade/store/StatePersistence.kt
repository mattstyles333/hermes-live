package com.folio.hermes.cascade.store

import com.folio.hermes.cascade.Effect
import com.folio.hermes.cascade.State
import com.folio.hermes.cascade.ports.Store

/**
 * The durability seam (R-DUR-1..5; S-B owns this package).
 *
 * - [save] runs at the end of every `reduce` step, pure: it compares the state before and after
 *   and returns the `Effect.Persist` / `Effect.Append` effects that make the [Store] match
 *   (only what changed). The actor runs those before any other effect of the step, so a turn is
 *   persisted before any network effect (R-DUR-1).
 * - [restore] runs when the actor starts (not in `reduce`): it reads the [Store] and returns the
 *   new call's starting state, built on [fresh].
 *
 * S0 ships [None] (nothing persisted, as C1). The kill-and-restart test harness
 * (`CascadeKillRestartTest`) drives any implementation; S-B adds the real one and its assertions.
 */
interface StatePersistence {
    fun save(before: State, after: State): List<Effect>

    fun restore(store: Store, fresh: State): State

    companion object {
        val None: StatePersistence = object : StatePersistence {
            override fun save(before: State, after: State): List<Effect> = emptyList()
            override fun restore(store: Store, fresh: State): State = fresh
        }
    }
}
