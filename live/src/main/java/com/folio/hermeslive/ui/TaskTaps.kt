package com.folio.hermeslive.ui

import com.folio.hermes.cascade.CascadeTasks
import com.folio.hermes.cascade.TaskTap

/**
 * A task card's tap on a call that is not Bill's. Callers: [grokIntents] (Grok Voice) and
 * [gptLiveIntents] (GPT-Live with parallel agents). The actor is not in a Bill call there and drops
 * `Event.TaskTapped`, so every tap goes through the shared task API. Retry is the registry's own
 * retry, as on Bill's card: a task that never reached Hermes is sent again as itself, and a failed
 * one starts again as a new task with exactly its brief (`TaskRetryIntentsTest`).
 */
internal suspend fun CascadeTasks.apiTap(taskId: String, tap: TaskTap) {
    when (tap) {
        TaskTap.Cancel -> cancelTask(taskId)
        is TaskTap.Tell -> updateTask(taskId, tap.text, tap.mode)
        TaskTap.ReadAll, TaskTap.Play -> heard(taskId)
        TaskTap.Retry -> retry(taskId)
    }
}
