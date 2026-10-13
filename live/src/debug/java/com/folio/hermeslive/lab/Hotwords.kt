package com.folio.hermeslive.lab

/**
 * Hotwords for `createStream(hotwords)` (R-STT-7), and the guard that keeps sherpa-onnx from
 * killing the process. In v1.13.8 a non-empty hotwords string:
 * - exits the process (SHERPA_ONNX_EXIT) for any model without contextual biasing, which
 *   includes the Nemotron streaming transducer;
 * - dereferences a null bpe encoder when the decoding method is not modified_beam_search (the
 *   encoder is only built in that branch) or when no bpe.vocab is configured.
 * So hotwords are passed only when [support] says yes.
 */
object Hotwords {
    data class Phrase(val words: List<String>, val score: Float?)

    data class Parsed(val phrases: List<Phrase>, val errors: List<String>)

    sealed interface Support {
        data object Yes : Support

        data class No(val reason: String) : Support
    }

    const val BEAM = "modified_beam_search"
    const val GREEDY = "greedy_search"

    /** One phrase per line, optional trailing ":score" (e.g. "HERMES LIVE :2.5"). */
    fun parse(text: String, upperCase: Boolean): Parsed {
        val phrases = ArrayList<Phrase>()
        val errors = ArrayList<String>()
        text.lineSequence().forEachIndexed { index, raw ->
            val parts = raw.replace('/', ' ').trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (parts.isEmpty()) return@forEachIndexed
            val scored = parts.last().startsWith(":")
            val words = if (scored) parts.dropLast(1) else parts
            val score = if (scored) parts.last().drop(1).toFloatOrNull() else null
            when {
                words.isEmpty() -> errors += "line ${index + 1}: no words"
                words.any { it.startsWith(":") } -> errors += "line ${index + 1}: score must come last"
                scored && (score == null || score <= 0f || score > 20f) ->
                    errors += "line ${index + 1}: score must be a number in (0, 20]"
                else -> phrases += Phrase(words.map { if (upperCase) it.uppercase() else it }, score)
            }
        }
        return Parsed(phrases, errors)
    }

    /** The string sherpa-onnx expects: one phrase per line, score last as " :2.5". */
    fun toSherpa(phrases: List<Phrase>): String =
        phrases.joinToString("\n") { p ->
            p.words.joinToString(" ") + (p.score?.let { " :$it" } ?: "")
        }

    /**
     * The decoding method a model is actually built with. The NeMo streaming transducer
     * (Nemotron) supports only greedy_search in v1.13.8; anything else exits the process.
     */
    fun decodingFor(kind: ModelKind, requested: String): String = when {
        kind == ModelKind.NEMOTRON_STREAMING -> GREEDY
        requested == BEAM -> BEAM
        else -> GREEDY
    }

    /** Sherpa sets modelingUnit=bpe and the vocab only for beam search (see [Sherpa]). */
    fun vocabConfigured(decodingMethod: String, vocabFileExists: Boolean): Boolean =
        vocabFileExists && decodingMethod == BEAM

    fun support(kind: ModelKind, decodingMethod: String, vocabPresent: Boolean): Support = when {
        kind == ModelKind.SILERO_VAD -> Support.No("not a recogniser")
        kind == ModelKind.NEMOTRON_STREAMING ->
            Support.No("Nemotron has no contextual biasing in sherpa-onnx v1.13.8 (the process would exit)")
        decodingMethod != BEAM -> Support.No("needs $BEAM (greedy would crash)")
        !vocabPresent -> Support.No("no bpe.vocab yet")
        else -> Support.Yes
    }

    /** The hotwords string to pass, or "" when the model can't take them safely. */
    fun safeArgument(phrases: List<Phrase>, kind: ModelKind, decodingMethod: String, vocabPresent: Boolean): String =
        if (phrases.isNotEmpty() && support(kind, decodingMethod, vocabPresent) == Support.Yes) toSherpa(phrases) else ""

    /**
     * Rough pre-check of what sherpa's encoder will accept: each word, with the sentencepiece
     * word-start mark, must split into pieces of the vocabulary (longest match first). sherpa
     * itself only logs and skips a phrase it can't encode, so this is what the lab shows.
     */
    fun encodable(phrase: Phrase, vocab: Set<String>): Boolean =
        phrase.words.all { segment("▁" + it, vocab) }

    private fun segment(word: String, vocab: Set<String>): Boolean {
        // reachable[i]: word[0 until i] splits into vocabulary pieces.
        val reachable = BooleanArray(word.length + 1)
        reachable[0] = true
        for (i in 0 until word.length) {
            if (!reachable[i]) continue
            for (j in word.length downTo i + 1) {
                if (word.substring(i, j) in vocab) reachable[j] = true
            }
        }
        return reachable[word.length]
    }
}
