package com.folio.hermes.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StopPhrasesTest {
    private fun stop(text: String?, config: StopPhrases.Config = StopPhrases.Config.Default) =
        StopPhrases.isStopCommand(text, config)

    @Test fun bareStopPhrasesMatch() {
        listOf("stop", "Stop.", "STOP!", "stop listening", "please stop", "That's all.", "that’s all", "never mind",
            "Nevermind", "good bye", "Goodbye!", "bye", "cancel", "end the conversation", "  stop  stop ")
            .forEach { assertTrue(it, stop(it)) }
    }

    @Test fun addressedStopsMatch() {
        listOf("Hermes, stop.", "hey hermes stop", "Hey Hermes, stop listening", "ok stop", "Okay, bye", "hey, cancel")
            .forEach { assertTrue(it, stop(it)) }
    }

    @Test fun realRequestsAreNotSwallowed() {
        listOf("stop the docker container", "how do I stop a running process", "hermes", "hey hermes", "ok",
            "stop it now please", "", "   ", "...")
            .forEach { assertFalse(it, stop(it)) }
        assertFalse(stop(null))
    }

    @Test fun commaPrefixesNeverMatchAfterNormalisation() {
        // "hermes," and "hey hermes," are in the source list but normalisation turns commas into spaces,
        // so only the comma-free forms ever strip. Kept for an exact port.
        assertEquals("hermes stop", StopPhrases.normalize("hermes, stop"))
        assertTrue(StopPhrases.ADDRESS_PREFIXES.contains("hermes,"))
    }

    @Test fun normalisesAnyScript() {
        assertEquals("стоп", StopPhrases.normalize("«стоп»!"))
        assertEquals("停止", StopPhrases.normalize("停止。"))
        assertEquals("stop", StopPhrases.normalize("ｓｔｏｐ")) // NFKC folds full-width
        assertEquals("a b", StopPhrases.normalize("a  b"))
    }

    @Test fun customAndDisabledLists() {
        val custom = StopPhrases.Config.Custom(listOf("Отбой!", "停止", " "))
        assertTrue(stop("отбой", custom))
        assertTrue(stop("Hermes 停止。", custom))
        assertFalse(stop("stop", custom))
        assertFalse(stop("stop", StopPhrases.Config.Disabled))
        assertFalse(stop("stop", StopPhrases.Config.Custom(emptyList())))
    }

    @Test fun typedStopOnlyDuringACallWithoutAttachments() {
        assertTrue(StopPhrases.interceptsTypedStop(true, "stop"))
        assertFalse(StopPhrases.interceptsTypedStop(false, "stop"))
        assertFalse(StopPhrases.interceptsTypedStop(true, "stop", attachmentCount = 1))
    }
}
