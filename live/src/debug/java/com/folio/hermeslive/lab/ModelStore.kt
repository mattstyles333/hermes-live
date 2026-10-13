package com.folio.hermeslive.lab

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Downloads models from the k2-fsa GitHub release into app-private storage
 * (noBackupFilesDir/stt-lab), checks each against its pinned sha256, extracts only the
 * files the lab needs and deletes the archive. These downloads are the lab's only network
 * calls. Blocking: call from a background thread.
 */
class ModelStore(private val root: File) {
    sealed interface Status {
        data object Missing : Status
        data object Queued : Status
        data class Downloading(val done: Long, val total: Long) : Status
        data object Verifying : Status
        data class Extracting(val fraction: Float) : Status
        data class Ready(val bytesOnDisk: Long) : Status
        data class Failed(val reason: String) : Status
    }

    data class Timing(val shaOk: Boolean?, val downloadS: Double?, val extractS: Double?, val vocabPieces: Int?)

    private val _status = MutableStateFlow(ModelCatalog.all.associate { it.id to initial(it) })
    val status: StateFlow<Map<String, Status>> = _status
    private val timings = java.util.concurrent.ConcurrentHashMap<String, Timing>()
    private val cancels = java.util.concurrent.ConcurrentHashMap<String, AtomicBoolean>()

    fun timing(model: LabModel): Timing? = timings[model.id]

    fun dir(model: LabModel) = File(root, "models/${model.id}")

    fun path(model: LabModel, relative: String): String = File(dir(model), relative).absolutePath

    fun isReady(model: LabModel): Boolean {
        val marker = File(dir(model), READY)
        return marker.isFile && marker.readText().trim() == model.sha256 &&
            model.keep.all { File(dir(model), it).isFile }
    }

    fun hasVocab(model: LabModel): Boolean = model.vocabFile.isNotEmpty() && File(dir(model), model.vocabFile).isFile

    fun vocab(model: LabModel): Set<String> =
        if (!hasVocab(model)) emptySet()
        else File(dir(model), model.vocabFile).readLines().map { it.substringBefore('\t') }.toSet()

    fun bytesOnDisk(model: LabModel): Long = dir(model).walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    fun cancel(model: LabModel) {
        cancels[model.id]?.set(true)
    }

    fun delete(model: LabModel) {
        dir(model).deleteRecursively()
        partFile(model).delete()
        set(model, Status.Missing)
    }

    /** Marks [model] as waiting for a download slot; false if it is already busy or ready. */
    fun queue(model: LabModel): Boolean {
        val now = _status.value[model.id]
        if (now !is Status.Missing && now !is Status.Failed) return false
        set(model, Status.Queued)
        return true
    }

    fun download(model: LabModel) {
        if (_status.value[model.id].let { it is Status.Downloading || it is Status.Verifying || it is Status.Extracting }) return
        val cancel = AtomicBoolean(false)
        cancels[model.id] = cancel
        val part = partFile(model)
        try {
            part.parentFile?.mkdirs()
            val started = System.nanoTime()
            val sha = fetch(model, part, cancel)
            val downloadS = seconds(started)
            set(model, Status.Verifying)
            val ok = Sha256.matches(model.sha256, sha)
            timings[model.id] = Timing(ok, downloadS, null, null)
            if (!ok) {
                part.delete()
                set(model, Status.Failed("sha256 mismatch: the file was not the pinned release asset"))
                return
            }
            val extractStarted = System.nanoTime()
            install(model, part, cancel)
            val pieces = writeVocab(model)
            File(dir(model), READY).writeText(model.sha256)
            timings[model.id] = Timing(true, downloadS, seconds(extractStarted), pieces)
            set(model, Status.Ready(bytesOnDisk(model)))
        } catch (e: Exception) {
            set(model, Status.Failed(if (cancel.get()) "cancelled" else e.javaClass.simpleName + ": " + (e.message ?: "")))
        } finally {
            part.delete()
            cancels.remove(model.id)
        }
    }

    /** Re-creates bpe.vocab if it is missing (models installed before the exporter changed). */
    fun ensureVocab(model: LabModel): Int? = if (isReady(model) && !hasVocab(model)) writeVocab(model) else null

    private fun fetch(model: LabModel, part: File, cancel: AtomicBoolean): String {
        val conn = URL(model.url).openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "hermes-live-stt-lab")
        try {
            check(conn.responseCode == 200) { "HTTP ${conn.responseCode}" }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: model.bytes
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var lastShown = 0L
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        if (cancel.get()) error("cancelled")
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        done += n
                        if (done - lastShown > total / 200 || done == total) {
                            lastShown = done
                            set(model, Status.Downloading(done, total))
                        }
                    }
                }
            }
            return Sha256.hex(digest.digest())
        } finally {
            conn.disconnect()
        }
    }

    private fun install(model: LabModel, part: File, cancel: AtomicBoolean) {
        val target = dir(model)
        val staging = File(root, "staging/${model.id}")
        staging.deleteRecursively()
        staging.mkdirs()
        if (!model.isArchive) {
            check(part.renameTo(File(staging, model.fileName))) { "could not move ${model.fileName}" }
        } else {
            val total = part.length().toFloat()
            val counting = CountingInput(FileInputStream(part))
            TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(counting, 1 shl 20))).use { tar ->
                var lastShown = 0L
                while (true) {
                    val entry = tar.nextEntry ?: break
                    if (cancel.get()) error("cancelled")
                    if (!entry.isFile) continue
                    val rel = ArchivePaths.select(entry.name, model.keep) ?: continue
                    val out = File(staging, rel)
                    out.parentFile?.mkdirs()
                    out.outputStream().use { tar.copyTo(it, 256 * 1024) }
                    if (counting.count - lastShown > total / 100) {
                        lastShown = counting.count
                        set(model, Status.Extracting(counting.count / total))
                    }
                }
            }
        }
        val missing = model.keep.filterNot { File(staging, it).isFile }
        check(missing.isEmpty()) { "archive lacks ${missing.joinToString()}" }
        target.deleteRecursively()
        target.parentFile?.mkdirs()
        check(staging.renameTo(target)) { "could not install ${model.id}" }
    }

    private fun writeVocab(model: LabModel): Int? {
        val pieces = when (model.vocabSource) {
            VocabSource.NONE -> return null
            VocabSource.SENTENCEPIECE_MODEL -> BpeVocab.piecesFromModel(File(dir(model), model.bpeModel).readBytes())
            VocabSource.TOKENS_EQUAL_SCORES -> BpeVocab.piecesFromTokens(File(dir(model), model.tokens).readText())
        }
        File(dir(model), model.vocabFile).writeText(BpeVocab.format(pieces))
        return pieces.size
    }

    private fun initial(model: LabModel): Status = if (isReady(model)) Status.Ready(bytesOnDisk(model)) else Status.Missing

    private fun set(model: LabModel, status: Status) = _status.update { it + (model.id to status) }

    private fun partFile(model: LabModel) = File(root, "download/${model.fileName}.part")

    private fun seconds(startNanos: Long) = (System.nanoTime() - startNanos) / 1e9

    private class CountingInput(input: InputStream) : FilterInputStream(input) {
        @Volatile var count = 0L

        override fun read(): Int = super.read().also { if (it >= 0) count++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
    }

    private companion object {
        const val READY = ".ready"
    }
}
