package com.folio.hermes.cascade.conversation

import com.folio.hermes.cascade.AppLines
import com.folio.hermes.cascade.CascadeEvent
import com.folio.hermes.cascade.SendState
import com.folio.hermes.cascade.SpeechKind
import com.folio.hermes.cascade.State
import com.folio.hermes.cascade.TaskRecord
import com.folio.hermes.cascade.TaskState
import com.folio.hermes.cascade.Tx

/**
 * The opening briefing (R-CONV-4): what is waiting, at most `briefingItems` items, most urgent
 * first: tasks that need the user, unheard results, tasks not sent, held notes, then tasks still
 * working. A call opens with it only when something is waiting for the user (running tasks alone
 * are not a reason to brief).
 */
object Briefing {
    fun items(s: State): List<String> {
        val tasks = s.tasks.tasks
        val needs = tasks.filter { it.state == TaskState.Running && it.waiting }
        val results = tasks.filter { it.unread }
        val notSent = tasks.filter { it.send == SendState.NotSent && it.state != TaskState.Running }
        val held = s.outbox.size
        val running = tasks.filter { it.state == TaskState.Running && !it.waiting }
        if (needs.isEmpty() && results.isEmpty() && notSent.isEmpty() && held == 0) return emptyList()
        return listOfNotNull(
            needs.phrase({ "${it.label} needs you." }, { n, l -> "$n tasks need you: $l." }),
            results.phrase({ "${it.label} is done." }, { n, l -> "$n results are ready: $l." }),
            notSent.phrase({ "${it.label} was not sent." }, { n, l -> "$n tasks were not sent: $l." }),
            when (held) {
                0 -> null
                1 -> "One note is waiting to send."
                else -> "$held notes are waiting to send."
            },
            running.phrase({ "${it.label} is still working." }, { n, l -> "$n tasks are still working: $l." }),
        ).take(s.settings.briefingItems.coerceAtLeast(0))
    }

    /** Put [items] on screen and say them: a stock lead-in, then the items, as one notice. */
    fun speak(tx: Tx, items: List<String>) {
        tx.s = tx.s.copy(ceo = tx.s.ceo.copy(briefing = items))
        if (items.isEmpty()) return
        tx.emit(CascadeEvent.Briefing(items.size))
        val lead = if (tx.s.tasks.tasks.any { it.unread }) AppLines.WHILE_AWAY else AppLines.WELCOME_BACK
        val u = tx.open(SpeechKind.Notice)
        tx.nextLine(lead)?.let { tx.add(u, it, lead) }
        items.forEach { tx.add(u, it) }
        tx.close(u)
    }

    private fun List<TaskRecord>.phrase(one: (TaskRecord) -> String, many: (Int, String) -> String): String? = when (size) {
        0 -> null
        1 -> one(first())
        else -> many(size, labels(map { it.label }))
    }

    /** "a", "a and b", "a, b and c". */
    fun labels(labels: List<String>): String = when (labels.size) {
        0 -> ""
        1 -> labels[0]
        else -> labels.dropLast(1).joinToString(", ") + " and " + labels.last()
    }
}
