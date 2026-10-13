package com.folio.hermes.live

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveTranscriptTest {
    private fun u(text: String, end: Long) = LiveTranscriptFragment(Speaker.User, text, end - 100, end)
    private fun a(text: String, end: Long) = LiveTranscriptFragment(Speaker.Assistant, text, end - 100, end)

    @Test fun promptIsTheLastUserTurnAndContextHasLines() {
        val result = LiveTranscript.delegationPrompt(
            listOf(u("What's the ", 100), u("weather?", 200), a("Let me  check.", 300), u(" Actually,", 400), u(" check   my calendar ", 500)),
        )
        assertEquals("Actually, check my calendar", result.prompt)
        assertEquals("User: What's the weather?\nVoice assistant: Let me check.\nUser: Actually, check my calendar", result.context)
    }

    @Test fun emptyTurnsAreDroppedAndPromptFallsBackToTranscriptTail() {
        val result = LiveTranscript.delegationPrompt(listOf(u("  ", 100), a("I can help with that.", 200)))
        assertEquals("Voice assistant: I can help with that.", result.context)
        assertEquals("Voice assistant: I can help with that.", result.prompt)
        val long = LiveTranscript.delegationPrompt(listOf(a("x".repeat(1000), 100)))
        assertEquals(400, long.prompt.length)
    }

    @Test fun contextWindowIsFiveMinutesAndEightyFragments() {
        val t = LiveTranscript()
        t.append(u("old", 1_000))
        t.append(u("edge", 1_000 + LiveTranscript.CONTEXT_WINDOW_MS))
        assertEquals(listOf("old", "edge"), t.contextWindow().map { it.text }) // the floor is inclusive
        t.append(u("recent", 1_001 + LiveTranscript.CONTEXT_WINDOW_MS))
        assertEquals(listOf("edge", "recent"), t.contextWindow().map { it.text })
        val many = LiveTranscript()
        repeat(100) { many.append(u("f$it", 10_000L + it)) }
        val window = many.contextWindow()
        assertEquals(80, window.size)
        assertEquals("f20", window.first().text)
        assertEquals("f99", window.last().text)
    }

    @Test fun bufferIsTrimmedPast2000() {
        val t = LiveTranscript()
        repeat(2_001) { t.append(u("f$it", it.toLong())) }
        assertEquals(1_500, t.size)
    }
}
