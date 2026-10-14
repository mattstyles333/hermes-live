package com.folio.hermeslive.cascade

import com.folio.hermes.cascade.Effect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec v2 §14 items 12 and 13: Notify goes through notifyInfo, LearnWord to the vocabulary. */
class AppEffectsTest {
    @Test fun learnWordReachesTheVocabularyAndUnknownTasksNotifyNothing() {
        val learned = mutableListOf<Pair<String, String>>()
        val notified = mutableListOf<String>()
        val haptics = mutableListOf<String>()
        val effects = CascadeAppRuntime.appEffects(
            core = { null },
            notify = { id, _, _ -> notified += id },
            learn = { h, m -> learned += h to m },
            haptic = { haptics += it },
        )
        effects(Effect.LearnWord("Jeff", "Jev"))
        effects(Effect.Notify("task_done", "t1"))
        effects(Effect.Haptic("task_finished"))
        assertEquals(listOf("Jeff" to "Jev"), learned)
        assertTrue(notified.isEmpty())
        assertEquals(listOf("task_finished"), haptics)
    }
}
