package com.folio.hermeslive.call

import com.folio.hermes.ApprovalChoice
import com.folio.hermes.ClarifyQuestion
import com.folio.hermes.live.StopPhrases

/** Matching what the user said against an open approval or clarify question. */
object SpokenReplies {
    private val APPROVE = setOf(
        "approve", "approve it", "approve once", "approved", "yes approve", "yes approve it",
        "allow", "allow it", "allow once", "yes allow it", "go ahead and approve",
    )
    private val DENY = setOf("deny", "deny it", "denied", "no deny", "reject", "reject it", "do not allow", "don t allow", "dont allow", "no don t", "cancel it")

    /**
     * Spoken approvals (a setting, off by default): only a whole-utterance "approve"/"deny"
     * phrase counts, never a sentence that merely contains one. Null when it is neither.
     * Never returns a choice the server did not offer.
     */
    fun approval(utterance: String, offered: List<ApprovalChoice>): ApprovalChoice? {
        val text = StopPhrases.normalize(utterance).removePrefix("hermes ").trim()
        val choice = when (text) {
            in APPROVE -> ApprovalChoice.Once
            in DENY -> ApprovalChoice.Deny
            else -> return null
        }
        return choice.takeIf { it in offered }
    }

    /**
     * An answer to a single clarify question from what the user said: the choice it names (exact,
     * then contained either way), or the words themselves for a free-text question. Null when the
     * question has choices and none matches.
     */
    fun clarify(utterance: String, question: ClarifyQuestion): String? {
        val said = StopPhrases.normalize(utterance)
        if (said.isEmpty()) return null
        if (question.choices.isEmpty()) return utterance.trim()
        val normalised = question.choices.map { it to StopPhrases.normalize(it) }
        normalised.firstOrNull { it.second == said }?.let { return it.first }
        normalised.firstOrNull { it.second.isNotEmpty() && said.contains(it.second) }?.let { return it.first }
        normalised.firstOrNull { it.second.contains(said) && said.length >= 3 }?.let { return it.first }
        return null
    }
}
