package com.folio.hermeslive.meeting

import com.folio.hermes.cascade.CascadeTasks
import com.folio.hermes.cascade.TaskReply
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

/**
 * [MeetingTasks] on the shared task API (`CascadeCore.tasks`, grok-voice slice `11cc01c`):
 * `startTask(brief, label, key)`. `Started` means the task is durably in Bill's task table: the
 * cascade sends it (now, or when the network is back) and resends it after a restart, and its
 * result arrives like any task (the card, the result notification, Hear it). The same key returns
 * the same task, so a retry never double-sends.
 *
 * [tasks] is null when Bill's runtime can't be built: not chosen in Settings › Voice, no usable
 * OpenRouter key, or signed out. [afterStart] keeps the gateway open while the task runs (the
 * off-call service, started while meeting mode is still in the foreground).
 */
class CascadeMeetingTasks(
    private val tasks: suspend () -> CascadeTasks?,
    private val signedIn: () -> Boolean,
    private val afterStart: () -> Unit = {},
    /** The held reason when the runtime can't be built (meetings: `bill_off`; offline memos: `no_runtime`). */
    private val noRuntime: String = "bill_off",
) : MeetingTasks {
    /** Build Bill's runtime now (its account check may wipe the store) before the outbox reads a meeting. */
    override suspend fun prepare() {
        if (signedIn()) tasks()
    }

    override suspend fun submit(brief: String, label: String, key: String): MeetingSubmit {
        if (!signedIn()) return MeetingSubmit.NotAvailable("signed_out")
        val api = tasks() ?: return MeetingSubmit.NotAvailable(noRuntime)
        return when (val r = api.startTask(brief, label, summary = null, key = key)) {
            is TaskReply.Started -> MeetingSubmit.Sent(r.taskId, r.queued).also { runCatching(afterStart) }
            TaskReply.NoReply, TaskReply.NotReady -> MeetingSubmit.NotAvailable("no_reply")
            else -> MeetingSubmit.NotAvailable("refused")
        }
    }
}
