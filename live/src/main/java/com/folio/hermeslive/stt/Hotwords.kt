package com.folio.hermeslive.stt

/**
 * Hotwords for `createStream(hotwords)` (R-STT-7): the user vocabulary, task labels and learned
 * corrections, per stream. In sherpa-onnx v1.13.8 a non-empty hotwords string
 * - exits the process (SHERPA_ONNX_EXIT) for a model without contextual biasing (Nemotron);
 * - dereferences a null bpe encoder unless the decoding method is modified_beam_search and a
 *   bpe.vocab is configured.
 * So [argument] returns "" unless the model takes them safely. Pure (from the S-C0 lab).
 */
object Hotwords {
    const val BEAM = "modified_beam_search"
    const val GREEDY = "greedy_search"

    /** Default boost for a phrase without its own ":score". */
    const val DEFAULT_SCORE = 2.0f

    /** At most this many phrases per stream (a long list slows beam search). */
    const val MAX_PHRASES = 200

    data class Phrase(val words: List<String>, val score: Float?)

    /**
     * One phrase per entry, optional trailing ":score" ("HERMES LIVE :2.5"). Punctuation other
     * than apostrophes is dropped (the tokenizers have none); blank or malformed entries are
     * skipped. Duplicates (ignoring case) keep the first.
     */
    fun parse(entries: List<String>, upperCase: Boolean): List<Phrase> {
        val out = LinkedHashMap<String, Phrase>()
        for (raw in entries) {
            val parts = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (parts.isEmpty()) continue
            val scored = parts.last().startsWith(":")
            val score = if (scored) parts.last().drop(1).toFloatOrNull()?.takeIf { it > 0f && it <= 20f } else null
            if (scored && score == null) continue
            val words = (if (scored) parts.dropLast(1) else parts)
                .map { w -> w.filter { it.isLetterOrDigit() || it == '\'' } }
                .filter { it.isNotEmpty() }
                .map { if (upperCase) it.uppercase() else it }
            if (words.isEmpty()) continue
            val key = words.joinToString(" ").lowercase()
            if (key !in out) out[key] = Phrase(words, score)
            if (out.size >= MAX_PHRASES) break
        }
        return out.values.toList()
    }

    /** The string sherpa-onnx expects: one phrase per line, score last as " :2.5". */
    fun toSherpa(phrases: List<Phrase>): String =
        phrases.joinToString("\n") { p -> p.words.joinToString(" ") + " :" + (p.score ?: DEFAULT_SCORE) }

    /** The decoding method a model is built with: Nemotron supports only greedy in v1.13.8. */
    fun decodingFor(kind: ModelKind, requested: String): String = when {
        kind == ModelKind.NEMOTRON_STREAMING -> GREEDY
        requested == BEAM -> BEAM
        else -> GREEDY
    }

    fun supported(model: SttModel, decodingMethod: String, vocabPresent: Boolean): Boolean =
        model.hotwords && decodingMethod == BEAM && vocabPresent

    /** The hotwords argument for a new stream of [model], or "" when it can't take them safely. */
    fun argument(entries: List<String>, model: SttModel, decodingMethod: String, vocabPresent: Boolean): String {
        if (entries.isEmpty() || !supported(model, decodingMethod, vocabPresent)) return ""
        return toSherpa(parse(entries, model.upperCase))
    }
}
