package com.folio.hermeslive.stt

/** Which archive entries to extract, and where. Rejects anything that could escape the folder (from the S-C0 lab). */
object ArchivePaths {
    /**
     * Strips a leading "./" and the archive's top-level folder:
     * "./sherpa-onnx-x/tokens.txt" -> "tokens.txt". Null for the folder itself.
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
