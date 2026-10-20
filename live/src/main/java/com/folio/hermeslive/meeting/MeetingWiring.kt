package com.folio.hermeslive.meeting

import com.folio.hermes.cascade.Transcript
import com.folio.hermes.cascade.cleanup.CleanupCheck
import com.folio.hermes.openrouter.CleanupInput
import com.folio.hermes.openrouter.OpenRouter
import com.folio.hermes.openrouter.OpenRouterResult

/**
 * The cleanup for meeting speech: the call's cleanup model and prompt, text only, and
 * [CleanupCheck]'s refusal of implausible swaps. Used only when Settings › Voice › Clean up
 * transcripts says so and a key is saved (the host decides per stretch).
 */
class OpenRouterSegmentCleaner(
    private val openRouter: OpenRouter,
    private val vocabulary: () -> List<String>,
    private val timeoutMs: Long = TIMEOUT_MS,
) : SegmentCleaner {
    override suspend fun clean(heard: Transcript, other: String): String? {
        if (heard.words.isEmpty() || heard.words.size > CleanupCheck.MAX_WORDS) return null
        val vocab = vocabulary()
        val input = CleanupInput(heard.text, other, heard.words.filter { it.uncertain }.map { it.text }, vocab)
        val cleaned = (openRouter.cleanup.clean(input, timeoutMs) as? OpenRouterResult.Ok)?.value ?: return null
        return (CleanupCheck.check(heard.words, cleaned, other, vocab) as? CleanupCheck.Verdict.Accepted)?.text
    }

    companion object {
        /** Off the turn's critical path (nobody waits on a meeting segment), so longer than a call's 1.5 s. */
        const val TIMEOUT_MS = 8_000L
    }
}

/** The writer for chunk summaries: DeepSeek Flash on the user's OpenRouter key. */
fun OpenRouter.meetingWriter(): MeetingWriter = MeetingWriter { system, prompt, maxTokens -> writer.write(system, prompt, maxTokens) }
