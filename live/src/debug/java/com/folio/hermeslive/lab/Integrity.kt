package com.folio.hermeslive.lab

import java.io.InputStream
import java.security.MessageDigest

/** sha256 pins for downloaded models. Pure: no Android, no network. */
object Sha256 {
    private val HEX = Regex("^[0-9a-f]{64}$")

    fun isPin(value: String): Boolean = HEX.matches(value)

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun of(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun of(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
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
        return isPin(p) && isPin(a) && MessageDigest.isEqual(p.toByteArray(), a.toByteArray())
    }
}

/** Which archive entries to extract, and where. Rejects anything that could escape the folder. */
object ArchivePaths {
    /**
     * Strips a leading "./" and the archive's top-level folder:
     * "./sherpa-onnx-x/test_wavs/0.wav" -> "test_wavs/0.wav". Null for the folder itself.
     */
    fun relative(entryName: String): String? {
        var name = entryName.replace('\\', '/')
        while (name.startsWith("./")) name = name.removePrefix("./")
        val slash = name.indexOf('/')
        if (slash < 0) return null
        val rest = name.substring(slash + 1).trimEnd('/')
        return rest.ifEmpty { null }
    }

    fun isSafe(relative: String): Boolean =
        relative.isNotEmpty() &&
            !relative.startsWith("/") &&
            relative.split('/').none { it.isEmpty() || it == "." || it == ".." }

    /** The relative path to extract for [entryName], or null to skip it. */
    fun select(entryName: String, keep: Collection<String>): String? {
        val rel = relative(entryName) ?: return null
        return rel.takeIf { it in keep && isSafe(it) }
    }
}
