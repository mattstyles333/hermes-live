package com.folio.hermes.cascade.routing

import com.folio.hermes.openrouter.JevQuestion

/**
 * The Jev question wording, copied verbatim from the hub's measured probe
 * (`~/reports/assets/hermes-cascade-jev-probe.py`, `probe5`, held-out tested; R-RT-4). Change
 * wording only with a new probe run: `JevQuestionsTest` pins every string, so an edit shows up
 * in review. S-EV and S-A tune here; nothing else in the app holds question text.
 */
object JevQuestions {
    val TURN = JevQuestion(
        "The user is dictating to Bill, a voice assistant, and has paused for `silence_ms`. `utterance` is everything they have said " +
            "for this request so far; `latest` is the part since their previous pause. Judge how `utterance` ENDS. " +
            "Has the user finished, so Bill should act now?",
        linkedMapOf(
            CascadeBrain.DONE to "`utterance` forms a complete request, question or remark; the last sentence is finished",
            CascadeBrain.KEEP_LISTENING to "The utterance is unfinished: it trails off mid-sentence, ends on 'and'/'so'/'also', " +
                "announces a list that hasn't come yet, or they are thinking aloud",
            CascadeBrain.IGNORE to "Not meant for Bill: noise, filler, a cough, Bill's own words echoed back; the user speaking to " +
                "another person (addresses someone else by name, answers a phone call, chats to someone in the room: " +
                "'stick it on the side', 'ring you back'); or radio/TV/background speech",
        ),
    )

    val ACTION = JevQuestion(
        "If the turn is finished, what should Bill do with `utterance`, given `tasks` and `recent`?",
        linkedMapOf(
            CascadeBrain.REPLY to "Just talk: chat, a quick answer from general knowledge, questions about the conversation itself " +
                "(what did I just ask?), or anything about approvals (those are tapped on screen); no agent needed",
            CascadeBrain.NEW_TASK to "Start a new agent task: real work, lookups, anything needing tools, files, the web or the user's systems",
            CascadeBrain.UPDATE_TASK to "Pass new information, a correction or a change of plan to one of the existing tasks, or retry a failed one",
            CascadeBrain.STATUS to "Say how a task, or all tasks, are getting on",
            CascadeBrain.CANCEL_TASK to "Stop or drop a task: 'stop', 'kill', 'cancel', 'scrap that', 'never mind', 'forget it'",
            CascadeBrain.READ_RESULT to "Read out a finished task's result",
            CascadeBrain.END_CALL to "End the call: goodbye, that's all, hang up",
        ),
    )

    const val TASK = "Which task do they mean, if the action is about a task?"
    const val TASK_NONE = "No particular task, or all of them"

    val SPLIT = JevQuestion(
        "If this starts new work, is it one job (possibly with sequential steps) or several independent jobs?",
        linkedMapOf(
            CascadeBrain.ONE to "One job, even if it has steps that follow each other",
            CascadeBrain.SEVERAL to "Two or more separate, independent jobs",
        ),
    )

    /** Note: the probe's wording names `heard`, which the R-RT-4 state no longer has (it has `utterance`). Kept verbatim as measured. */
    val CLARITY = JevQuestion(
        "How clear is the transcript `heard` (speech-to-text can mishear)?",
        linkedMapOf(
            CascadeBrain.CLEAR to "Clear and sensible",
            CascadeBrain.MINOR_DOUBT to "Sensible overall, but a word looks like a speech-to-text slip: an odd or split word " +
                "('Bright Well' for a brand), a homophone, a missing small word",
            CascadeBrain.KEY_DOUBT to "A name, quantity, price or date the action depends on looks misheard, garbled or oddly phrased " +
                "(e.g. 'hundred' with no 'a', 'for' vs 'four', an unknown supplier-like name)",
            CascadeBrain.GARBLED to "Too garbled to act on",
        ),
    )

    const val LINE = "Which stock line should Bill say first? Pick the one whose use fits best, or none."
    const val LINE_NONE = "No stock line fits"

    val ENOUGH = JevQuestion(
        "Is the stock line enough on its own, or does Bill need to say more after it?",
        linkedMapOf(
            CascadeBrain.LINE_ONLY to "The stock line fully answers; nothing specific to add",
            CascadeBrain.LINE_THEN_WRITER to "Bill must add something specific after the stock line",
        ),
    )

    val UPDATE_MODE = JevQuestion(
        "If they are updating a running task: should it hear this after its current step, or change what it is doing now?",
        linkedMapOf(
            CascadeBrain.QUEUE to "Extra detail or a follow-up it can pick up after its current step (most updates)",
            CascadeBrain.REDIRECT to "A correction that makes its current work wrong: 'no', 'stop, instead…', 'I said X not Y'",
        ),
    )
}
