package com.folio.hermeslive.lab

import com.k2fsa.sherpa.onnx.WaveReader
import java.io.File

/**
 * Load times and real-time factors on the LibriSpeech test WAVs that ship inside each model
 * archive (no personal audio), at 1, 2 and 4 threads. Run with captions stopped.
 */
class Bench(private val store: ModelStore, private val decoding: String) {
    private data class Wav(val samples: FloatArray, val rate: Int, val reference: String?) {
        val seconds: Double get() = samples.size.toDouble() / rate
    }

    fun run(models: List<LabModel>, progress: (String) -> Unit, cancelled: () -> Boolean): List<BenchRow> {
        val rows = ArrayList<BenchRow>()
        for (model in models) {
            if (!store.isReady(model)) continue
            if (model.role == ModelRole.VAD) {
                rows += vad(model)
                continue
            }
            val wavs = wavs(model)
            for (threads in LabSettings.THREADS) {
                if (cancelled()) return rows
                progress("${model.title}: $threads thread(s)")
                rows += if (model.role == ModelRole.STREAMING) streaming(model, threads, wavs) else offline(model, threads, wavs)
            }
        }
        return rows
    }

    private fun wavs(model: LabModel): List<Wav> {
        val refs = HashMap<String, String>()
        if (model.transcripts.isNotEmpty()) {
            File(store.path(model, model.transcripts)).readLines().forEach { line ->
                val name = line.substringBefore(' ').trim()
                if (name.isNotEmpty()) refs[name] = line.substringAfter(' ').trim()
            }
        }
        return model.testWavs.map { rel ->
            val w = WaveReader.readWave(store.path(model, rel))
            Wav(w.samples, w.sampleRate, refs[File(rel).name])
        }
    }

    private fun streaming(model: LabModel, threads: Int, wavs: List<Wav>): BenchRow {
        val t0 = System.nanoTime()
        val recognizer = Sherpa.online(store, model, threads, decoding)
        val loadMs = (System.nanoTime() - t0) / 1e6
        var decodeNanos = 0L
        var audio = 0.0
        val wers = ArrayList<Double>()
        try {
            for (wav in wavs) {
                val stream = recognizer.createStream()
                val step = wav.rate / 10
                val start = System.nanoTime()
                var at = 0
                while (at < wav.samples.size) {
                    val end = minOf(at + step, wav.samples.size)
                    stream.acceptWaveform(wav.samples.copyOfRange(at, end), wav.rate)
                    while (recognizer.isReady(stream)) recognizer.decode(stream)
                    at = end
                }
                // Tail padding flushes the last chunk, as in sherpa-onnx's examples.
                stream.acceptWaveform(FloatArray(wav.rate * 3 / 10), wav.rate)
                stream.inputFinished()
                while (recognizer.isReady(stream)) recognizer.decode(stream)
                val text = recognizer.getResult(stream).text
                decodeNanos += System.nanoTime() - start
                audio += wav.seconds
                wav.reference?.let { wers += WordDiff.wer(it, text) }
                stream.release()
            }
        } finally {
            recognizer.release()
        }
        return BenchRow(model.id, "streaming", threads, Hotwords.decodingFor(model.kind, decoding), loadMs, audio, decodeNanos / 1e9 / audio, wers.takeIf { it.isNotEmpty() }?.average())
    }

    private fun offline(model: LabModel, threads: Int, wavs: List<Wav>): BenchRow {
        val t0 = System.nanoTime()
        val recognizer = Sherpa.offline(store, model, threads, decoding)
        val loadMs = (System.nanoTime() - t0) / 1e6
        var decodeNanos = 0L
        var audio = 0.0
        try {
            for (wav in wavs) {
                val start = System.nanoTime()
                val stream = recognizer.createStream()
                stream.acceptWaveform(wav.samples, wav.rate)
                recognizer.decode(stream)
                recognizer.getResult(stream)
                decodeNanos += System.nanoTime() - start
                audio += wav.seconds
                stream.release()
            }
        } finally {
            recognizer.release()
        }
        return BenchRow(model.id, "offline", threads, Hotwords.decodingFor(model.kind, decoding), loadMs, audio, decodeNanos / 1e9 / audio, null)
    }

    private fun vad(model: LabModel): BenchRow {
        val t0 = System.nanoTime()
        val vad = Sherpa.vad(store)
        val loadMs = (System.nanoTime() - t0) / 1e6
        // The VAD archive has no WAV: use the Zipformer's 1.wav when present.
        val source = ModelCatalog.zipformer
        if (!store.isReady(source)) {
            vad.release()
            return BenchRow(model.id, "vad", 1, "-", loadMs, 0.0, 0.0, null)
        }
        val wav = WaveReader.readWave(store.path(source, "test_wavs/1.wav"))
        val start = System.nanoTime()
        var at = 0
        while (at + Sherpa.VAD_WINDOW <= wav.samples.size) {
            vad.acceptWaveform(wav.samples.copyOfRange(at, at + Sherpa.VAD_WINDOW))
            while (!vad.empty()) vad.pop()
            at += Sherpa.VAD_WINDOW
        }
        val seconds = wav.samples.size.toDouble() / wav.sampleRate
        val rtf = (System.nanoTime() - start) / 1e9 / seconds
        vad.release()
        return BenchRow(model.id, "vad", 1, "-", loadMs, seconds, rtf, null)
    }
}
