package com.folio.hermes.live

import java.text.Normalizer

/**
 * Spoken stop-word detection, ported from the Hermes desktop client's `stopword.ts`.
 *
 * Deliberately conservative: only fires when the WHOLE utterance is a stop phrase (optionally
 * addressed to Hermes), so "stop the docker container" is never swallowed.
 */
object StopPhrases {
    /** Which phrase list to use (mirrors the server's `voice.stop_phrases`). */
    sealed interface Config {
        /** The built-in English list. */
        data object Default : Config

        /** A custom list (any language) replacing the built-in one. */
        data class Custom(val phrases: List<String>) : Config

        /** Spoken stop is off. */
        data object Disabled : Config
    }

    /** Canonical English stop commands. */
    val DEFAULT_PHRASES: List<String> = listOf(
        "stop",
        "stop listening",
        "stop it",
        "stop please",
        "please stop",
        "stop stop",
        "that is all",
        "that's all",
        "never mind",
        "nevermind",
        "end conversation",
        "end the conversation",
        "goodbye",
        "good bye",
        "bye",
        "cancel",
    )

    /**
     * Address prefixes stripped before matching, in order. The comma forms can never match after
     * [normalize] (commas become spaces); kept to match the source exactly.
     */
    val ADDRESS_PREFIXES: List<String> = listOf("hey hermes", "hey hermes,", "hermes", "hermes,", "ok", "okay", "hey")

    private val PUNCTUATION = Regex("\\p{P}+")
    private val WHITESPACE = Regex("\\s+")
    private val NORMALISED_DEFAULTS = DEFAULT_PHRASES.map(::normalize)

    /**
     * NFKC, lowercase, punctuation from any script to spaces, whitespace collapsed, trimmed.
     * Transcripts and phrases both go through this.
     */
    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC)
            .lowercase()
            .replace(PUNCTUATION, " ")
            .replace(WHITESPACE, " ")
            .trim()

    private fun stripAddress(text: String): String {
        for (prefix in ADDRESS_PREFIXES) {
            if (text == prefix) continue // a bare "hermes" is not a stop command
            if (text.startsWith("$prefix ")) return text.substring(prefix.length + 1).trim()
        }
        return text
    }

    private fun phrasesFor(config: Config): List<String>? = when (config) {
        Config.Disabled -> null
        is Config.Custom -> config.phrases.map(::normalize).filter { it.isNotEmpty() }
        Config.Default -> NORMALISED_DEFAULTS
    }

    /** True when the entire utterance is a stop command (optionally addressed to Hermes). */
    fun isStopCommand(transcript: String?, config: Config = Config.Default): Boolean {
        if (transcript.isNullOrEmpty()) return false
        val phrases = phrasesFor(config)
        if (phrases.isNullOrEmpty()) return false
        val normalized = normalize(transcript)
        if (normalized.isEmpty()) return false
        return setOf(normalized, stripAddress(normalized)).any { it in phrases }
    }

    /** A bare stop command typed while a call is live ends it; attachments never count. */
    fun interceptsTypedStop(conversationActive: Boolean, text: String, attachmentCount: Int = 0, config: Config = Config.Default): Boolean =
        conversationActive && attachmentCount == 0 && isStopCommand(text, config)
}
