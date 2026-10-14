package com.folio.hermes.cascade

import com.folio.hermes.openrouter.SpeechFormat
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** A whole PCM clip ready for the player. */
class AudioClip(val bytes: ByteArray, val format: SpeechFormat.Pcm) {
    val durationMs: Long get() = format.durationMs(bytes.size.toLong())
}

/** One stored clip, for the LRU index. */
data class StoredClip(val key: String, val sizeBytes: Long, val lastUsedMs: Long)

/** Clip bytes by key. `:live` supplies [DirectoryClipStore] in app-private storage. */
interface ClipStore {
    fun read(key: String): ByteArray?
    fun write(key: String, bytes: ByteArray)
    fun delete(key: String)
    fun touch(key: String, atMs: Long)
    fun list(): List<StoredClip>
}

/** Keeps clips in memory (tests, or a session without storage). */
class MemoryClipStore : ClipStore {
    private val map = ConcurrentHashMap<String, Pair<ByteArray, Long>>()
    override fun read(key: String): ByteArray? = map[key]?.first
    override fun write(key: String, bytes: ByteArray) {
        map[key] = bytes to System.currentTimeMillis()
    }
    override fun delete(key: String) {
        map.remove(key)
    }
    override fun touch(key: String, atMs: Long) {
        map.computeIfPresent(key) { _, v -> v.first to atMs }
    }
    override fun list(): List<StoredClip> = map.map { (k, v) -> StoredClip(k, v.first.size.toLong(), v.second) }
}

/** One file per clip (`<key>.clip`) in [dir]; the file's modified time is its LRU time. */
class DirectoryClipStore(private val dir: File) : ClipStore {
    private fun file(key: String) = File(dir, "$key$EXT")
    override fun read(key: String): ByteArray? = file(key).takeIf { it.isFile }?.let { runCatching { it.readBytes() }.getOrNull() }
    override fun write(key: String, bytes: ByteArray) {
        dir.mkdirs()
        val tmp = File(dir, "$key.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file(key))) tmp.delete()
    }
    override fun delete(key: String) {
        file(key).delete()
    }
    override fun touch(key: String, atMs: Long) {
        file(key).setLastModified(atMs)
    }
    override fun list(): List<StoredClip> = dir.listFiles { f -> f.name.endsWith(EXT) }.orEmpty()
        .map { StoredClip(it.name.removeSuffix(EXT), it.length(), it.lastModified()) }

    private companion object {
        const val EXT = ".clip"
    }
}

/**
 * Every TTS clip, stock or written, under `sha256(model|voice|format|text)`; least recently used
 * clips go first once the total passes [maxBytes] (50 MB). A repeated line plays from storage with
 * no network call. Stored as a 12-byte header (rate, channels, bits) plus raw PCM.
 */
class TtsCache(
    private val store: ClipStore,
    private val maxBytes: Long = 50L * 1024 * 1024,
    private val now: () -> Long = System::currentTimeMillis,
) {
    // Access-ordered: the eldest entry is the least recently used.
    private val index: LinkedHashMap<String, Long> by lazy {
        LinkedHashMap<String, Long>(64, 0.75f, true).apply {
            store.list().sortedBy { it.lastUsedMs }.forEach { put(it.key, it.sizeBytes) }
        }
    }
    private var total: Long = -1

    /** Bytes stored. */
    val sizeBytes: Long @Synchronized get() = ensureTotal()

    @Synchronized fun contains(key: String): Boolean = index.containsKey(key)

    @Synchronized
    fun get(key: String): AudioClip? {
        if (!index.containsKey(key)) return null
        val raw = store.read(key)
        val clip = raw?.let(::decode)
        if (clip == null) {
            remove(key)
            return null
        }
        index[key] // mark used
        store.touch(key, now())
        return clip
    }

    @Synchronized
    fun put(key: String, clip: AudioClip) {
        val bytes = encode(clip)
        if (bytes.size > maxBytes) return
        ensureTotal()
        remove(key)
        store.write(key, bytes)
        store.touch(key, now())
        index[key] = bytes.size.toLong()
        total += bytes.size
        val it = index.entries.iterator()
        while (total > maxBytes && it.hasNext()) {
            val eldest = it.next()
            if (eldest.key == key) continue
            store.delete(eldest.key)
            total -= eldest.value
            it.remove()
        }
    }

    private fun remove(key: String) {
        ensureTotal()
        index.remove(key)?.let {
            store.delete(key)
            total -= it
        }
    }

    private fun ensureTotal(): Long {
        if (total < 0) total = index.values.sum()
        return total
    }

    companion object {
        private const val MAGIC = 0x484C4331 // "HLC1"
        private const val HEADER = 12

        /** `sha256(model|voice|format|text)` as hex. */
        fun key(model: String, voice: String, format: SpeechFormat, text: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest("$model|$voice|${formatTag(format)}|$text".toByteArray())
            val hex = "0123456789abcdef"
            return buildString(digest.size * 2) {
                digest.forEach { b ->
                    append(hex[(b.toInt() shr 4) and 0xF])
                    append(hex[b.toInt() and 0xF])
                }
            }
        }

        private fun formatTag(format: SpeechFormat): String = when (format) {
            is SpeechFormat.Pcm -> "pcm"
            SpeechFormat.Mp3 -> "mp3"
        }

        internal fun encode(clip: AudioClip): ByteArray = ByteBuffer.allocate(HEADER + clip.bytes.size)
            .putInt(MAGIC)
            .putInt(clip.format.sampleRateHz)
            .putShort(clip.format.channels.toShort())
            .putShort(clip.format.bitsPerSample.toShort())
            .put(clip.bytes)
            .array()

        internal fun decode(raw: ByteArray): AudioClip? {
            if (raw.size < HEADER) return null
            val buf = ByteBuffer.wrap(raw)
            if (buf.int != MAGIC) return null
            val format = SpeechFormat.Pcm(buf.int, buf.short.toInt(), buf.short.toInt())
            return AudioClip(raw.copyOfRange(HEADER, raw.size), format)
        }
    }
}
