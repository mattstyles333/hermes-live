package com.folio.hermes

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android compiles regexes with ICU, not java.util.regex. A few JVM-only constructs compile
 * fine here and throw PatternSyntaxException on the phone (Hermes Live 1.0.0 crashed on every
 * call start with `(?U)\s+`). Unit tests can't see that, so refuse them in the source.
 */
class AndroidRegexTest {
    private val unsupported = listOf(
        "(?U)" to "UNICODE_CHARACTER_CLASS flag: ICU's \\s, \\w, \\b are already Unicode",
        "UNICODE_CHARACTER_CLASS" to "not supported by ICU",
        "\\\\p{Is" to "Java-only property syntax: use \\p{L}, \\p{P}, …",
        "\\\\p{java" to "Java-only property syntax",
        "CANON_EQ" to "not supported on Android",
    )

    @Test
    fun noJvmOnlyRegexInMainSources() {
        val roots = listOf(File("src/main"), File("../live/src/main")).filter { it.isDirectory }
        assertTrue("source roots not found from ${File(".").absolutePath}", roots.isNotEmpty())
        val problems = roots.flatMap { root ->
            root.walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
                file.readLines().mapIndexedNotNull { i, line ->
                    unsupported.firstOrNull { (needle, _) -> needle in line }
                        ?.let { (needle, why) -> "${file.path}:${i + 1} uses $needle ($why)" }
                }
            }.toList()
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
