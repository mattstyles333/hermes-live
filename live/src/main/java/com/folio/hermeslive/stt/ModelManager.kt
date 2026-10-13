package com.folio.hermeslive.stt

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Where a model set is (what Settings shows, S-E). */
sealed interface ModelStatus {
    data object Missing : ModelStatus

    /** [done] of [total] bytes fetched (all archives of the set). */
    data class Downloading(val done: Long, val total: Long) : ModelStatus {
        val fraction: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
    }

    /** Checking the sha256 and unpacking. */
    data object Installing : ModelStatus

    data class Ready(val bytesOnDisk: Long) : ModelStatus

    data class Failed(val reason: Reason) : ModelStatus

    /** Codes only (safe for telemetry). */
    enum class Reason { NETWORK, HTTP, CHECKSUM, DISK, ARCHIVE, CANCELLED }
}

/**
 * The model store Settings calls (S-E): [status] per set (with progress), [download], [cancel],
 * [delete]. Nothing touches the network until [download] is called (R-Q-1: off by default).
 */
interface SttModelStore {
    val status: StateFlow<Map<ModelSet, ModelStatus>>

    /** Start (or resume) fetching [set] in the background. No-op while busy or ready. */
    fun download(set: ModelSet)

    /** Stop a running download; the partial file is kept so the next [download] resumes. */
    fun cancel(set: ModelSet)

    /** Remove [set]'s files and any partial download. */
    fun delete(set: ModelSet)

    fun isReady(set: ModelSet): Boolean
}

/**
 * Downloads the pinned model archives into app-private storage ([root], e.g.
 * `noBackupFilesDir/stt`), resumable (HTTP Range on a `.part` file), checked against the sha256
 * pinned in [SttCatalog], then unpacks only the files each model needs and writes `bpe.vocab`
 * for hotwords. The archive is deleted after install. These downloads are the only network
 * calls of the speech-to-text stack.
 */
class ModelManager(
    private val root: File,
    private val baseUrl: String = SttModel.RELEASE_BASE,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** The models of each set (tests swap in small archives). */
    private val modelsOf: (ModelSet) -> List<SttModel> = { it.models },
) : SttModelStore {
    private val _status = MutableStateFlow(ModelSet.entries.associateWith { initial(it) })
    override val status: StateFlow<Map<ModelSet, ModelStatus>> = _status.asStateFlow()
    private val jobs = ConcurrentHashMap<ModelSet, Job>()
    private val cancels = ConcurrentHashMap<ModelSet, AtomicBoolean>()

    fun dir(model: SttModel) = File(root, "models/${model.id}")

    fun path(model: SttModel, relative: String): String = File(dir(model), relative).absolutePath

    fun isInstalled(model: SttModel): Boolean {
        val marker = File(dir(model), READY)
        return marker.isFile && marker.readText().trim() == model.sha256 && model.keep.all { File(dir(model), it).isFile }
    }

    fun hasVocab(model: SttModel): Boolean = model.vocabFile.isNotEmpty() && File(dir(model), model.vocabFile).isFile

    override fun isReady(set: ModelSet): Boolean = modelsOf(set).all(::isInstalled)

    fun bytesOnDisk(model: SttModel): Long = dir(model).walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    override fun download(set: ModelSet) {
        if (isReady(set)) {
            update(set, ready(set))
            return
        }
        val running = jobs[set]
        if (running?.isActive == true) return
        val cancel = AtomicBoolean(false)
        cancels[set] = cancel
        jobs[set] = scope.launch {
            update(set, ModelStatus.Downloading(0, modelsOf(set).sumOf { it.bytes }))
            val result = runCatching { fetchSet(set, cancel) }
            update(
                set,
                result.fold(
                    onSuccess = { ready(set) },
                    onFailure = { ModelStatus.Failed(if (cancel.get()) ModelStatus.Reason.CANCELLED else reasonOf(it)) },
                ),
            )
        }
    }

    override fun cancel(set: ModelSet) {
        cancels[set]?.set(true)
    }

    override fun delete(set: ModelSet) {
        cancel(set)
        for (model in modelsOf(set)) {
            // A model shared with another set stays while that set is installed.
            if (ModelSet.entries.any { it != set && model in modelsOf(it) && isReady(it) }) continue
            dir(model).deleteRecursively()
            partFile(model).delete()
        }
        update(set, initial(set))
    }

    /**
     * Installs [model] from an archive already on the device (tests and sideloading): the same
     * sha256 check and unpacking as a download.
     */
    fun installFrom(model: SttModel, archive: File) {
        val sha = FileInputStream(archive).use { Sha256.of(it) }
        if (!Sha256.matches(model.sha256, sha)) throw ChecksumException()
        install(model, archive, AtomicBoolean(false))
        ModelSet.entries.forEach { if (isReady(it)) update(it, ready(it)) }
    }

    private fun fetchSet(set: ModelSet, cancel: AtomicBoolean) {
        val total = modelsOf(set).sumOf { it.bytes }
        var before = 0L
        for (model in modelsOf(set)) {
            if (isInstalled(model)) {
                before += model.bytes
                continue
            }
            val part = partFile(model)
            part.parentFile?.mkdirs()
            val base = before
            fetch(model, part, cancel) { done -> update(set, ModelStatus.Downloading(base + done, total)) }
            update(set, ModelStatus.Installing)
            val sha = FileInputStream(part).use { Sha256.of(it) }
            if (!Sha256.matches(model.sha256, sha)) {
                part.delete()
                throw ChecksumException()
            }
            install(model, part, cancel)
            part.delete()
            before += model.bytes
        }
    }

    /** Fetches [model] into [part], resuming from its current length when the server allows it. */
    private fun fetch(model: SttModel, part: File, cancel: AtomicBoolean, progress: (Long) -> Unit) {
        var have = if (part.isFile) part.length() else 0L
        if (have >= model.bytes) {
            progress(have)
            return
        }
        val conn = URL(baseUrl + model.fileName).openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "hermes-live")
        if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
        try {
            val code = conn.responseCode
            val append = when {
                code == HttpURLConnection.HTTP_PARTIAL && have > 0 -> true
                code == HttpURLConnection.HTTP_OK -> false
                else -> throw HttpException(code)
            }
            if (!append) have = 0
            conn.inputStream.use { input ->
                FileOutputStream(part, append).use { out ->
                    val buffer = ByteArray(256 * 1024)
                    var done = have
                    var shown = 0L
                    while (true) {
                        if (cancel.get()) throw IOException("cancelled")
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        if (done - shown > model.bytes / 200) {
                            shown = done
                            progress(done)
                        }
                    }
                    progress(done)
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun install(model: SttModel, archive: File, cancel: AtomicBoolean) {
        val target = dir(model)
        val staging = File(root, "staging/${model.id}")
        staging.deleteRecursively()
        staging.mkdirs()
        if (!model.isArchive) {
            archive.copyTo(File(staging, model.fileName), overwrite = true)
        } else {
            TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(FileInputStream(archive), 1 shl 20))).use { tar ->
                while (true) {
                    val entry = tar.nextEntry ?: break
                    if (cancel.get()) throw IOException("cancelled")
                    if (!entry.isFile) continue
                    val rel = ArchivePaths.select(entry.name, model.keep) ?: continue
                    val out = File(staging, rel)
                    out.parentFile?.mkdirs()
                    out.outputStream().use { tar.copyTo(it, 256 * 1024) }
                }
            }
        }
        val missing = model.keep.filterNot { File(staging, it).isFile }
        if (missing.isNotEmpty()) throw ArchiveException()
        writeVocab(model, staging)
        File(staging, READY).writeText(model.sha256)
        target.deleteRecursively()
        target.parentFile?.mkdirs()
        if (!staging.renameTo(target)) throw IOException("could not install")
    }

    private fun writeVocab(model: SttModel, dir: File) {
        val pieces = when (model.vocabSource) {
            VocabSource.NONE -> return
            VocabSource.SENTENCEPIECE_MODEL -> BpeVocab.piecesFromModel(File(dir, model.bpeModel).readBytes())
            VocabSource.TOKENS_EQUAL_SCORES -> BpeVocab.piecesFromTokens(File(dir, model.tokens).readText())
        }
        File(dir, model.vocabFile).writeText(BpeVocab.format(pieces))
    }

    private fun initial(set: ModelSet): ModelStatus = if (isReady(set)) ready(set) else ModelStatus.Missing

    private fun ready(set: ModelSet) = ModelStatus.Ready(modelsOf(set).sumOf(::bytesOnDisk))

    private fun update(set: ModelSet, status: ModelStatus) = _status.update { it + (set to status) }

    private fun partFile(model: SttModel) = File(root, "download/${model.fileName}.part")

    private fun reasonOf(e: Throwable): ModelStatus.Reason = when (e) {
        is ChecksumException -> ModelStatus.Reason.CHECKSUM
        is HttpException -> ModelStatus.Reason.HTTP
        is ArchiveException -> ModelStatus.Reason.ARCHIVE
        is java.io.FileNotFoundException -> ModelStatus.Reason.HTTP
        is IOException -> if (e.message?.contains("ENOSPC") == true || e.message?.contains("No space") == true) ModelStatus.Reason.DISK else ModelStatus.Reason.NETWORK
        else -> ModelStatus.Reason.ARCHIVE
    }

    class ChecksumException : IOException("sha256 mismatch")

    class HttpException(val code: Int) : IOException("HTTP $code")

    class ArchiveException : IOException("archive lacks model files")

    private companion object {
        const val READY = ".ready"
    }
}

/** The pinned digest check (from the S-C0 lab). */
object Sha256 {
    private val HEX = Regex("^[0-9a-f]{64}$")

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun of(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(256 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
        return hex(digest.digest())
    }

    /** True only for a well-formed pin that equals [actual] (case-insensitive hex). */
    fun matches(pin: String, actual: String): Boolean {
        val p = pin.lowercase()
        val a = actual.lowercase()
        return HEX.matches(p) && HEX.matches(a) && MessageDigest.isEqual(p.toByteArray(), a.toByteArray())
    }
}
