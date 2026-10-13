package com.folio.hermeslive.stt

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The real download path: the pinned GitHub release URL (redirected to its CDN), cancelled at
 * about 30%, then resumed with a Range request. Uses a scratch root, so installed models are
 * untouched, and deletes it afterwards. Network: the model download only. Runs only with
 * `download=true`.
 */
@RunWith(AndroidJUnit4::class)
class ModelDownloadDeviceTest {
    @Test fun resumesARealDownloadAfterACancel() = runBlocking {
        assumeTrue(Device.args.getString("download") == "true")
        val root = File(Device.context.cacheDir, "dl-check").apply { deleteRecursively() }
        val m = ModelManager(root)
        val model = SttCatalog.parakeet110m
        val t0 = System.nanoTime()
        val first = launch(Dispatchers.Default) { m.fetch(model) }
        withTimeout(600_000) {
            m.models.first { (it[model.id] as? ModelStatus.Downloading)?.let { d -> d.done > model.bytes * 3 / 10 } == true }
        }
        first.cancelAndJoin()
        val part = File(root, "download/${model.fileName}.part")
        assertTrue("partial file kept", part.isFile)
        val cut = part.length()
        val seen = ArrayList<Long>()
        val watch = launch(Dispatchers.Default) {
            m.models.collect { s -> (s[model.id] as? ModelStatus.Downloading)?.let { synchronized(seen) { seen += it.done } } }
        }
        val ok = m.fetch(model)
        watch.cancelAndJoin()
        val ms = (System.nanoTime() - t0) / 1_000_000
        val lowest = synchronized(seen) { seen.minOrNull() } ?: -1
        Device.results("download.json").writeText(
            JSONObject()
                .put("model", model.id)
                .put("cancelled_at_bytes", cut)
                .put("resumed_lowest_progress", lowest)
                .put("ready", ok && m.isInstalled(model))
                .put("total_ms", ms)
                .toString(2),
        )
        root.deleteRecursively()
        assertTrue("ready after resume", ok)
        assertTrue("resumed from $lowest, cut at $cut (a restart would start near 0)", lowest >= cut)
    }
}
