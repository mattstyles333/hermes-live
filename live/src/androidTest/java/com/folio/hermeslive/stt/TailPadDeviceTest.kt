package com.folio.hermeslive.stt

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in experiment (`-e tailpad <dir>`): every WAV in `<dir>` (16 kHz mono, e.g. the smoke's
 * saved `request.wav` copies) through the real pipeline, at pace 0, with each tail padding in
 * `-e pads 0.3,0.66` (seconds). Logs the fast and accurate text per file and padding under
 * `TAILPAD` (the smoke's made-up request, not user speech). No key, no network.
 */
@RunWith(AndroidJUnit4::class)
class TailPadDeviceTest {
    @Test fun lastWordByTailPadding() {
        val dir = Device.args.getString("tailpad")
        assumeTrue("tailpad=<dir> runs the experiment", dir != null)
        Device.requireModels(SttCatalog.zipformer, SttCatalog.parakeet110m)
        val files = File(dir!!).listFiles { f -> f.name.endsWith(".wav") }.orEmpty().sortedBy { it.name }
        val pads = (Device.args.getString("pads") ?: "0.3,0.66").split(',').map { it.trim().toDouble() }
        for (pad in pads) {
            val runner = CorpusRunner(Device.engines(SttEngineConfig(tailPadS = pad)), emptyList(), pace = 0.0)
            try {
                for (f in files) {
                    val u = runner.run(Device.Item(f, "", "", "smoke"))
                    Log.i("TAILPAD", "pad=$pad file=${f.name} fast=[${u.fastText}] accurate=[${u.transcript?.text}]")
                }
            } finally {
                runner.close()
            }
        }
    }
}
