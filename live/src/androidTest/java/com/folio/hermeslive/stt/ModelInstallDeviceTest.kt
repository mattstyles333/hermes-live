package com.folio.hermeslive.stt

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Installs pushed model archives (the `.tar.bz2` files in `<external files>/stt-archives`) through
 * [ModelManager.installFrom]: the same sha256 check and unpacking as a download, timed on the
 * device. Archives are deleted after install. Runs only with `installModels=true`.
 */
@RunWith(AndroidJUnit4::class)
class ModelInstallDeviceTest {
    @Test fun installPushedArchives() {
        assumeTrue(Device.args.getString("installModels") == "true")
        val dir = File(Device.external(), "stt-archives")
        val archives = dir.listFiles { f -> f.name.endsWith(".tar.bz2") }.orEmpty()
        assumeTrue("no archives in $dir", archives.isNotEmpty())
        val rows = JSONArray()
        for (file in archives) {
            val model = SttCatalog.all.firstOrNull { it.fileName == file.name } ?: continue
            val t0 = System.nanoTime()
            Device.models.installFrom(model, file)
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertTrue(model.id, Device.models.isInstalled(model))
            rows.put(
                JSONObject()
                    .put("model", model.id)
                    .put("archive_bytes", model.bytes)
                    .put("installed_bytes", Device.models.bytesOnDisk(model))
                    .put("install_ms", ms)
                    .put("vocab", Device.models.hasVocab(model)),
            )
            file.delete()
        }
        Device.results("install.json").writeText(rows.toString(2))
    }
}
