package com.folio.hermeslive.data

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.folio.hermes.cascade.AudioClip
import com.folio.hermes.cascade.BillVoice
import com.folio.hermes.cascade.DecisionLog
import com.folio.hermes.cascade.DecisionRecord
import com.folio.hermes.cascade.MemoryClipStore
import com.folio.hermes.cascade.MemoryDecisionStore
import com.folio.hermes.cascade.PhraseBank
import com.folio.hermes.cascade.PhraseRenderer
import com.folio.hermes.cascade.TtsCache
import com.folio.hermes.openrouter.OpenRouter
import com.folio.hermes.openrouter.OpenRouterConfig
import com.folio.hermes.openrouter.SpeechFormat
import com.folio.hermeslive.service.Briefing
import com.folio.hermeslive.service.BriefingPlayer
import com.folio.hermeslive.service.BriefingService
import com.folio.hermeslive.service.OffCallService
import com.folio.hermeslive.service.ResultNotifications
import com.folio.hermeslive.service.TaskResultNotice
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * R-Q-2: telemetry carries event names, codes and timings only. Every shell flow is driven with
 * sentinel content (a key, a transcript, a headline, a vocabulary word, a stock line) and a raw
 * recording sink, with no [SafeTelemetry] in front, so the components themselves are proven not
 * to pass text. Then [SafeTelemetry] is checked as the second line.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CascadeTelemetryPrivacyTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val sink = RecordingSink()

    private val transcript = "please email Sentinel Dorothy about invoice 4471"
    private val headline = "Sentinel headline: the refund for Dorothy went through"
    private val stockLine = "Sentinel stock line on it"
    private val word = "Sentinelvocab"
    private val sentinels = listOf(FAKE_KEY, FAKE_KEY.takeLast(20), "Sentinel", "Dorothy", "4471", word.lowercase())

    @After fun tearDown() = scope.cancel()

    private fun assertClean() {
        val text = sink.text()
        assertTrue("the flows reported something", sink.events.isNotEmpty())
        sentinels.forEach { s -> assertFalse("telemetry carried \"$s\": $text", text.contains(s, ignoreCase = true)) }
    }

    @Test fun keyFlowsReportNoKey() = runTest {
        val server = FakeOpenRouterServer()
        val config = OpenRouterConfig(baseUrl = "https://openrouter.example/api/v1", retryBackoffMs = 1)
        val checker = KeyChecker.from { key -> OpenRouter(config, key = key, client = server.client(), telemetry = sink) }
        listOf(200, 401, 503).forEach { server.keyStatus = it; checker.check(FAKE_KEY) }
        val box = SoftwareSecretBox()
        val keys = OpenRouterKeyStore(app.getSharedPreferences("privacy-key", Context.MODE_PRIVATE), box, sink)
        keys.save(FAKE_KEY, KeyCheck.Valid)
        box.key = javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        keys.key() // fails to open: reported by type only
        assertTrue(sink.events.any { it.first == "openrouter.call" })
        assertTrue(sink.events.any { it.first == "keystore.open_failed" })
        assertClean()
    }

    @Test fun storeCorruptionReportsNoKeyOrValue() {
        val dir = Files.createTempDirectory("privacy-store").toFile()
        val store = FileStore(dir, telemetry = sink)
        store.put("conv/$word", transcript)
        File(File(dir, "kv"), FileStore.fileName("conv/$word") + ".kv").appendText("x")
        store.get("conv/$word")
        store.append("outbox/$word", transcript)
        File(File(dir, "logs"), FileStore.fileName("outbox/$word") + ".log").appendText("garbage $transcript")
        store.read("outbox/$word")
        assertEquals(2, sink.events.count { it.first == "store.corrupt" })
        assertClean()
    }

    @Test fun phraseRenderAndDecisionLogReportNoText() = runTest {
        val server = FakeOpenRouterServer()
        val config = OpenRouterConfig(baseUrl = "https://openrouter.example/api/v1", retryBackoffMs = 1)
        val openRouter = OpenRouter(config, key = { FAKE_KEY }, client = server.client(), telemetry = sink)
        val voice = BillVoice(openRouter.speech, TtsCache(MemoryClipStore()), { "bill" }, { "elevenlabs/eleven-v4-turbo" })
        val bank = PhraseBank(9, emptyMap(), listOf(com.folio.hermes.cascade.PhraseSlot("ack.ok", "ack", "use", listOf(stockLine, "$stockLine two"))))
        PhraseRenderer(voice, 2, sink).render(bank)
        assertTrue(sink.events.any { it.first == "cascade.render" })
        // The decision log keeps the transcript on the phone and reports nothing at all.
        val log = DecisionLog(MemoryDecisionStore())
        log.record(DecisionRecord("t1", System.currentTimeMillis(), "done", line = transcript))
        assertTrue(log.exportJson().contains("Dorothy"))
        assertClean()
    }

    @Test fun vocabularyAndPrepReportNoWords() {
        val t = TestShell(app, "privacy-prep", scope, sink)
        t.settings.update { it.copy(voiceEngine = VoiceEngine.Cascade, vocabulary = listOf(word)) }
        t.keys.save(FAKE_KEY, KeyCheck.Valid)
        t.shell.vocabulary.learn("dorthy", "Dorothy $word")
        assertTrue(t.shell.prep.start())
        val deadline = System.currentTimeMillis() + 10_000
        while (t.shell.prep.state.value.phase == PrepPhase.Running && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(PrepPhase.Done, t.shell.prep.state.value.phase)
        assertTrue(sink.events.any { it.first == "cascade.prepare" })
        assertTrue(sink.events.any { it.first == "cascade.render" })
        assertTrue(t.server.requests.any { it.url.encodedPath.endsWith("/audio/speech") })
        assertClean()
    }

    @Test fun offCallAndHearItReportNoHeadline() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val hooks = FakeOffCallHooks(initialRunning = 2)
        val off = Robolectric.buildService(OffCallService::class.java, Intent(app, OffCallService::class.java).setAction(OffCallService.ACTION_START).putExtra(OffCallService.EXTRA_TASKS, 2))
        off.get().apply {
            hooksProvider = { hooks }
            activeProvider = { true }
            telemetry = sink
        }
        off.create().startCommand(0, 1)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(ResultNotifications.post(app, TaskResultNotice("task-$word", "Refund $word", headline)))
        hooks.running.value = 0
        shadowOf(Looper.getMainLooper()).idle()

        hooks.briefing = Briefing(listOf(AudioClip(ByteArray(480), SpeechFormat.Pcm())))
        val brief = Robolectric.buildService(BriefingService::class.java, BriefingService.playIntent(app, "task-$word"))
        brief.get().apply {
            hooksProvider = { hooks }
            playerProvider = { SilentPlayer() }
            telemetry = sink
        }
        brief.create().startCommand(0, 1)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(sink.events.any { it.first == "offcall.stop" })
        assertTrue(sink.events.any { it.first == "briefing.play" })
        assertClean()
    }

    @Test fun safeTelemetryLetsCodesThroughAndRedactsText() {
        val safe = SafeTelemetry(sink)
        safe.event(
            "cascade.turn",
            mapOf(
                "outcome" to "keep_listening", "ms" to 312L, "ok" to true, "ratio" to 0.5, "kind" to com.folio.hermes.openrouter.FailureKind.NoKey,
                "text" to transcript, "key" to FAKE_KEY, "Bad Key" to "x", "path" to "/data/user/0/x y",
            ),
            false,
        )
        safe.event("Free text $transcript", emptyMap(), false)
        val (name, attrs) = sink.events.first()
        assertEquals("cascade.turn", name)
        assertEquals("keep_listening", attrs["outcome"])
        assertEquals(312L, attrs["ms"])
        assertEquals(true, attrs["ok"])
        assertEquals("nokey", attrs["kind"])
        assertEquals(SafeTelemetry.REDACTED, attrs["text"])
        assertEquals(SafeTelemetry.REDACTED, attrs["key"])
        assertEquals(SafeTelemetry.REDACTED, attrs["path"])
        assertFalse("attribute names must be codes too", attrs.containsKey("Bad Key"))
        assertEquals(SafeTelemetry.REDACTED, sink.events[1].first)
        assertClean()
    }
}

class SilentPlayer : BriefingPlayer {
    val played = mutableListOf<AudioClip>()
    var onPlay: () -> Unit = {}
    override suspend fun play(clip: AudioClip) {
        onPlay()
        played += clip
    }
    override fun stop() = Unit
}
