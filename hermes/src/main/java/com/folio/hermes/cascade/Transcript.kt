package com.folio.hermes.cascade

/*
 * Transcript types (R-STT, frozen per R-ARC-4). S-C fills them from the engine; S-A reads them
 * for Jev `clarity`, writer repair and the brief's transcript block.
 */

/** Which speech-to-text pass produced a transcript (R-STT-1/2). */
enum class TranscriptPass(val wire: String) {
    /** The streaming model (partials, pause ring, Jev's turn decision). */
    Fast("fast"),

    /** The offline accurate pass over the committed utterance. */
    Accurate("accurate"),
}

/**
 * One word. [confidence] comes from the engine's token probabilities when it has them (null
 * otherwise; R-STT-4). [alternatives] are the other readings kept for an uncertain word (from
 * the other pass, or the engine's n-best), e.g. `Sapphire` with `Safilo`.
 */
data class Word(
    val text: String,
    val confidence: Double? = null,
    val alternatives: List<String> = emptyList(),
    /** Set by the transcript merge: below `CascadeSettings.uncertainBelow`, or the two passes disagree. */
    val uncertain: Boolean = false,
)

/**
 * A transcript of one utterance or one committed turn. [offline] marks text held while offline
 * (memo mode, R-OFF); [pass] says which pass the [text] is from (`pass: fast` when the
 * accurate pass did not land within `accuratePassWaitMs`).
 */
data class Transcript(
    val text: String,
    val words: List<Word> = emptyList(),
    val pass: TranscriptPass = TranscriptPass.Fast,
    val offline: Boolean = false,
) {
    val uncertain: List<Word> get() = words.filter { it.uncertain }
}
