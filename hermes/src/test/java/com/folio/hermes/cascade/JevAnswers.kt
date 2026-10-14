package com.folio.hermes.cascade

import com.folio.hermes.openrouter.JevAnswer

/** A Jev answer picking [choice] with [confidence]. */
internal fun answer(choice: String, confidence: Double = 0.9) = JevAnswer("choice", choice, mapOf(choice to confidence), confidence)
