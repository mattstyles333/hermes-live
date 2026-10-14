package com.folio.hermeslive.grok

import com.folio.hermes.cascade.CascadeUiState
import com.folio.hermes.cascade.JevPick
import com.folio.hermes.cascade.JevUi
import com.folio.hermes.cascade.TaskUi
import com.folio.hermes.cascade.TaskUiStatus
import com.folio.hermes.cascade.TurnPhase
import com.folio.hermes.realtime.GrokModels
import com.folio.hermes.realtime.GrokUi
import com.folio.hermes.realtime.GrokVoices
import com.folio.hermes.realtime.LinkState
import com.folio.hermes.realtime.ToolChip
import com.folio.hermeslive.data.EngineState
import com.folio.hermeslive.data.EngineStatus
import com.folio.hermeslive.data.KeyCheck
import com.folio.hermeslive.data.KeyStatus
import com.folio.hermeslive.data.LiveSettings
import com.folio.hermeslive.data.PrepState
import com.folio.hermeslive.data.VoiceEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure parts of the Grok engine: the 16 → 24 kHz resampler, the screen mapping, the settings. */
class GrokPartsTest {
    private fun samples(bytes: ByteArray): List<Int> = (bytes.indices step 2).map { (bytes[it].toInt() and 0xff) or (bytes[it + 1].toInt() shl 8) }

    @Test fun theResamplerMakesThreeSamplesOfTwoAcrossChunks() {
        val r = Resampler16To24()
        // A ramp 0, 30, 60, … in 20 ms chunks: the output is the same ramp at 20 per sample, seams included.
        var next = 0
        val out = mutableListOf<Int>()
        repeat(3) {
            val chunk = ShortArray(320) { (next + it) .let { i -> (i * 30).toShort() } }
            next += 320
            out += samples(r.convert(chunk))
        }
        assertEquals("1.5× the input, less the last sample's tail", 960 * 3 / 2 - 1, out.size)
        out.forEachIndexed { i, v -> assertEquals("sample $i", i * 20, v) }
    }

    @Test fun theResamplerKeepsSilenceSilentAndClampsNothing() {
        val r = Resampler16To24()
        assertTrue(samples(r.convert(ShortArray(320))).all { it == 0 })
        val loud = samples(r.convert(ShortArray(320) { Short.MAX_VALUE }))
        assertTrue(loud.drop(1).all { it.toShort() == Short.MAX_VALUE })
    }

    @Test fun billsScreenShowsGrokTurnsAndNoJevAnswers() {
        val tasks = CascadeUiState(jev = JevUi(inFlight = true, answers = mapOf("action" to JevPick("new_task", 0.9, emptyList()))), tasks = listOf(TaskUi("t1", "Stock check", TaskUiStatus.Running, 0L, null, 0, null, emptyList(), 0L, "", null, 0)))
        val speaking = GrokScreen.state(tasks, GrokUi(link = LinkState.Open(false), speaking = true, reply = "On it. I'll check the stock."), 0.7f)
        assertEquals(TurnPhase.BillSpeaking, speaking.turn.phase)
        assertEquals(0.7f, speaking.bill.level)
        assertEquals("I'll check the stock.", speaking.bill.text)
        assertTrue(speaking.jev.answers.isEmpty())
        assertFalse(speaking.jev.inFlight)
        assertEquals("the tray is the actor's", tasks.tasks, speaking.tasks)
        assertNull("Bill's Resume card is not Grok's", speaking.resume)
        val listening = GrokScreen.state(tasks, GrokUi(link = LinkState.Open(false), userSpeaking = true, caption = "check the"), 0.7f)
        assertEquals(TurnPhase.UserSpeaking, listening.turn.phase)
        assertEquals("check the", listening.turn.partial)
        assertEquals(0f, listening.bill.level)
        assertFalse("offline", GrokScreen.state(tasks, GrokUi(link = LinkState.Offline), 0f).connection.online)
    }

    @Test fun theStripShowsToolCallsWithTheirTasks() {
        val tasks = listOf(TaskUi("t1", "Stock check", TaskUiStatus.Running, 0L, null, 0, null, emptyList(), 0L, "", null, 0))
        val chips = GrokScreen.chips(listOf(ToolChip("c1", "start_task", "t1", ok = true), ToolChip("c2", "task_status"), ToolChip("c3", "cancel_task", ok = false)), tasks)
        assertEquals(listOf("start", "status", "cancel"), chips.map { it.question })
        assertEquals("…", chips[1].choice)
        assertEquals("failed", chips[2].choice)
        assertTrue(chips[0].choice.isNotBlank() && chips[0].choice != "ok")
    }

    @Test fun grokSettingsDefaultOffAndNormalise() {
        val d = LiveSettings()
        assertEquals("GPT-Live stays the default", VoiceEngine.GptLive, d.voiceEngine)
        assertEquals(GrokVoices.DEFAULT, d.grokVoiceId())
        assertEquals(GrokModels.DEFAULT, d.grokModel)
        val custom = LiveSettings(grokVoice = "ARA", grokCustomVoice = " ab12cd34 ", grokModel = "grok-voice-3.0").normalized()
        assertEquals("ara", custom.grokVoice)
        assertEquals("ab12cd34", custom.grokVoiceId())
        assertEquals("grok-voice-3.0", custom.grokModel)
        val bad = LiveSettings(grokVoice = "nobody", grokCustomVoice = "not a voice", grokModel = "gpt-4o").normalized()
        assertEquals(GrokVoices.DEFAULT, bad.grokVoiceId())
        assertEquals(GrokModels.DEFAULT, bad.grokModel)
        assertEquals(VoiceEngine.Grok, VoiceEngine.parse("grok"))
    }

    @Test fun theEngineLineFollowsTheXaiKey() {
        val grok = LiveSettings(voiceEngine = VoiceEngine.Grok)
        assertEquals(EngineState.GrokNeedsKey, EngineStatus.of(grok, KeyStatus(true, KeyCheck.Valid), PrepState()).state)
        assertEquals(EngineState.GrokKeyRefused, EngineStatus.of(grok, KeyStatus(), PrepState(), KeyStatus(true, KeyCheck.Refused)).state)
        assertEquals(EngineState.GrokReady, EngineStatus.of(grok, KeyStatus(), PrepState(), KeyStatus(true, KeyCheck.Valid)).state)
    }
}
