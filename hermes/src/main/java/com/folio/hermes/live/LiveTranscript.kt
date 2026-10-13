package com.folio.hermes.live

/** Who spoke a transcript fragment. */
enum class Speaker { User, Assistant }

/** One transcript delta from the live model; times are milliseconds into the session. */
data class LiveTranscriptFragment(val speaker: Speaker, val text: String, val startMs: Long, val endMs: Long)

/** The Hermes turn built from a delegation: [prompt] is what the user last said, [context] the recent exchange. */
data class DelegationPrompt(val prompt: String, val context: String)

/**
 * The call's transcript, bounded like the desktop client: at most 2000 fragments (trimmed to the
 * newest 1500), and a delegation sees the last 5 minutes, at most 80 fragments.
 */
class LiveTranscript(
    private val windowMs: Long = CONTEXT_WINDOW_MS,
    private val maxFragments: Int = CONTEXT_MAX_FRAGMENTS,
) {
    private val fragments = ArrayList<LiveTranscriptFragment>()

    /** Number of fragments held. */
    val size: Int get() = synchronized(fragments) { fragments.size }

    fun append(fragment: LiveTranscriptFragment) = synchronized(fragments) {
        fragments.add(fragment)
        if (fragments.size > 2_000) fragments.subList(0, fragments.size - 1_500).clear()
    }

    /** Recent conversation, oldest first, bounded by time (from the newest fragment's end) and count. */
    fun contextWindow(): List<LiveTranscriptFragment> = synchronized(fragments) {
        val last = fragments.lastOrNull() ?: return emptyList()
        val floor = last.endMs - windowMs
        fragments.filter { it.endMs >= floor }.takeLast(maxFragments)
    }

    fun clear() = synchronized(fragments) { fragments.clear() }

    companion object {
        const val CONTEXT_WINDOW_MS = 5 * 60_000L
        const val CONTEXT_MAX_FRAGMENTS = 80
        private val WHITESPACE = Regex("\\s+")

        /**
         * Port of the desktop `delegationPrompt`: merge consecutive fragments per speaker; the prompt
         * is the last user turn (or the transcript's last 400 chars when the user said nothing), the
         * context is `User: …` / `Voice assistant: …` lines.
         */
        fun delegationPrompt(context: List<LiveTranscriptFragment>): DelegationPrompt {
            val turns = ArrayList<Pair<Speaker, StringBuilder>>()
            for (fragment in context) {
                val last = turns.lastOrNull()
                if (last != null && last.first == fragment.speaker) last.second.append(fragment.text)
                else turns.add(fragment.speaker to StringBuilder(fragment.text))
            }
            val lastUser = turns.lastOrNull { it.first == Speaker.User }
            val prompt = (lastUser?.second?.toString() ?: "").replace(WHITESPACE, " ").trim()
            val transcript = turns
                .map { (speaker, text) ->
                    val who = if (speaker == Speaker.User) "User" else "Voice assistant"
                    "$who: ${text.toString().replace(WHITESPACE, " ").trim()}"
                }
                .filterNot { it.endsWith(": ") }
                .joinToString("\n")
            return DelegationPrompt(prompt = prompt.ifEmpty { transcript.takeLast(400) }, context = transcript)
        }
    }
}
