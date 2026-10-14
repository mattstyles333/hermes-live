package com.folio.hermeslive.meeting

/**
 * The wake phrase in a meeting: an utterance that **starts** with "Bill" ("hey Bill", "OK Bill",
 * "okay Bill") is an instruction to Bill, not meeting speech. Speech-to-text gives "Bill note that
 * …" with no comma, so only the first words are looked at, and only these spellings: every
 * sound-alike added ("Phil", "bill's") would catch more of the room.
 *
 * - **A task** only with an explicit task verb right after the wake word: "start a task to …",
 *   "make/create/open/add a task …", "new task …", "ask Hermes to …". Someone talking to a person
 *   called Bill must never launch an agent.
 * - **A note** with a note verb right after the wake word ("note that as an action for Priya",
 *   "mark that", "flag …", "remember …", "action for …"): a highlighted marker in the transcript,
 *   nothing is sent.
 * - **Anything else** ("Bill thinks we should …") is ordinary meeting speech.
 * - **"Bill" alone** arms the next segment (said within [ARM_MS]): it is the instruction, a task
 *   with a task verb, else a note.
 *
 * The segment stays in the transcript either way (its marker points at it), so a false wake never
 * loses words.
 */
object WakePhrase {
    /** How long "Bill" alone waits for the instruction, ms. */
    const val ARM_MS = 8_000L

    sealed interface Parsed {
        /** Not addressed to Bill. */
        data object None : Parsed

        /** Just the wake word: the next segment is the instruction. */
        data object Armed : Parsed

        /** A note for the meeting notes; [text] is what followed the wake word, tidied. */
        data class Note(val text: String) : Parsed

        /** An agent task; [request] is what to do, as said. */
        data class Task(val request: String) : Parsed
    }

    private val WAKE = Regex("^(?:(?:hey|hi|ok|okay)[\\s,]+)?bill\\b[\\s,.:;!?-]*", RegexOption.IGNORE_CASE)

    private val TASK = Regex(
        "^(?:please[\\s,]+)?(?:(?:can|could|would) you[\\s,]+)?" +
            "(?:(?:start|make|create|open|add|set up|kick off|launch)\\s+(?:a\\s+|an\\s+|the\\s+)?(?:new\\s+)?(?:agent\\s+)?task" +
            "|new\\s+(?:agent\\s+)?task|ask hermes)\\b[\\s,:;-]*(?:(?:to|for|that|about)\\b\\s*)?",
        RegexOption.IGNORE_CASE,
    )

    private val NOTE = Regex(
        "^(?:please[\\s,]+)?(?:(?:can|could|would) you[\\s,]+)?(?:make a note|take a note|write (?:that |this |it )?down|jot|note|mark|flag|highlight|" +
            "remember|record|log|capture|star|bookmark|action|add|put|keep|save|remind)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** [text] of one segment; [armed]: the previous segment was "Bill" alone, within [ARM_MS]. */
    fun parse(text: String, armed: Boolean = false): Parsed {
        val t = text.trim()
        if (t.isEmpty()) return Parsed.None
        val m = WAKE.find(t)
        val rest = when {
            m != null -> t.substring(m.range.last + 1).trim()
            armed -> t
            else -> return Parsed.None
        }
        if (rest.isEmpty()) return if (m != null) Parsed.Armed else Parsed.None
        val task = TASK.find(rest)
        if (task != null) {
            val request = rest.substring(task.range.last + 1).trim().trimEnd('.', ' ')
            if (request.isNotEmpty()) return Parsed.Task(request.replaceFirstChar { it.uppercaseChar() })
            // "Bill, start a task …" and a pause: the next segment says what.
            return Parsed.Armed
        }
        if (m != null && !armed && NOTE.find(rest) == null) return Parsed.None
        return Parsed.Note(rest.trimEnd('.', ' ').replaceFirstChar { it.uppercaseChar() })
    }

    /** The task's label: "Meeting task · " and the request's first five words. */
    fun taskLabel(request: String): String {
        val words = request.split(Regex("\\s+")).filter { it.isNotEmpty() }.take(5).joinToString(" ").trimEnd(',', '.', ';', ':')
        return "Meeting task · $words"
    }
}
