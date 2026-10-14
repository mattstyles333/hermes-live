package com.folio.hermes.cascade

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stock lines are spoken in whatever voice the user picks (ondemand slice: "Bill" is only the
 * default ElevenLabs voice now), so no spoken line names Bill or any other voice or engine.
 */
class PhraseNamesTest {
    @Test fun noSpokenLineNamesAVoiceOrEngine() {
        val names = Regex("\\b(bill|grok|gpt-live|custom engine|eve|ara|rex)\\b", RegexOption.IGNORE_CASE)
        val bad = PhraseBank.load().slots.flatMap { slot -> slot.lines.filter { names.containsMatchIn(it) }.map { "${slot.id}: $it" } }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }
}
