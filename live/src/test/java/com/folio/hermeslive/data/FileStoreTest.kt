package com.folio.hermeslive.data

import com.folio.hermes.cascade.ports.MemoryStore
import com.folio.hermes.cascade.ports.Store
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plain JVM: the file store uses java.io only. */
class FileStoreTest {
    private fun tempDir(): File = Files.createTempDirectory("filestore").toFile().also { it.deleteOnExit() }

    /** The same behaviour as [MemoryStore] (the port's reference), run against both. */
    private fun contract(store: Store) {
        assertNull(store.get("a"))
        store.put("task/1/session", "s-1")
        store.put("task/2/session", "s-2")
        store.put("conv:current", "{\"id\":\"c1\"}")
        store.put("a", "one")
        store.put("a", "two")
        assertEquals("two", store.get("a"))
        assertEquals(listOf("task/1/session", "task/2/session"), store.keys("task/"))
        assertEquals(listOf("a", "conv:current", "task/1/session", "task/2/session"), store.keys())
        store.put("a", null)
        assertNull(store.get("a"))
        assertFalse("a" in store.keys())
        store.put("never-there", null)

        assertTrue(store.read("outbox").isEmpty())
        store.append("outbox", "first")
        store.append("outbox", "second line with \\ and\nnewline")
        store.append("outbox", "")
        store.append("outbox", "third")
        assertEquals(listOf("first", "second line with \\ and\nnewline", "", "third"), store.read("outbox"))
        store.trim("outbox", 2)
        assertEquals(listOf("", "third"), store.read("outbox"))
        store.trim("outbox", 10)
        assertEquals(listOf("", "third"), store.read("outbox"))
        store.trim("outbox", 0)
        assertTrue(store.read("outbox").isEmpty())
        store.trim("missing", 3)
        assertTrue(store.read("missing").isEmpty())
    }

    @Test fun memoryStoreMeetsTheContract() = contract(MemoryStore())

    @Test fun fileStoreMeetsTheContract() = contract(FileStore(tempDir()))

    @Test fun valuesAndLogsSurviveARestart() {
        val dir = tempDir()
        FileStore(dir).apply {
            put("conv/c1", "{\"recent\":[\"héllo ✓\"]}")
            append("decisions", "{\"turn\":1}")
        }
        val again = FileStore(dir)
        assertEquals("{\"recent\":[\"héllo ✓\"]}", again.get("conv/c1"))
        assertEquals(listOf("{\"turn\":1}"), again.read("decisions"))
    }

    @Test fun keysOfAnyShapeAndLengthWork() {
        val store = FileStore(tempDir())
        val long = "conversation/" + "x".repeat(400)
        val odd = listOf("../escape", ".hidden", "a b/c:d?e*f", "ünïcode/✓", long, "")
        odd.forEachIndexed { i, k -> store.put(k, "v$i") }
        odd.forEachIndexed { i, k -> assertEquals(k, "v$i", store.get(k)) }
        assertEquals(odd.sorted(), store.keys())
        assertTrue(FileStore.fileName(long).length < 100)
        assertFalse(FileStore.fileName("../escape").contains('/'))
    }

    @Test fun aWriteIsAllOrNothing() {
        val dir = tempDir()
        val store = FileStore(dir)
        store.put("k", "old")
        // A crash mid-write leaves only a temp file behind: the old value is intact and the temp file goes on open.
        val kv = File(dir, "kv")
        File(kv, "k.kv.tmp-123").writeText("half a wri")
        val again = FileStore(dir)
        assertEquals("old", again.get("k"))
        assertTrue(kv.listFiles()!!.none { it.name.contains(".tmp-") })
        assertEquals(listOf("k"), again.keys())
    }

    @Test fun aCorruptValueReadsAsAbsentAndIsQuarantined() {
        val dir = tempDir()
        val sink = RecordingSink()
        val store = FileStore(dir, telemetry = sink)
        store.put("good", "fine")
        store.put("bad", "will be damaged")
        val file = File(File(dir, "kv"), FileStore.fileName("bad") + ".kv")
        val bytes = file.readBytes()
        bytes[bytes.size - 2] = (bytes[bytes.size - 2] + 1).toByte()
        file.writeBytes(bytes)

        assertNull(store.get("bad"))
        assertFalse(file.exists())
        assertEquals(1, File(dir, "corrupt").listFiles()!!.size)
        assertEquals(listOf("good"), store.keys())
        assertEquals("fine", store.get("good"))
        val (name, attrs, warn) = sink.events.single()
        assertEquals("store.corrupt", name)
        assertEquals(mapOf("kind" to "kv", "count" to 1), attrs)
        assertTrue(warn)
        assertFalse("no key or value in telemetry", sink.text().contains("damaged") || sink.text().contains("bad"))
        // It can be written again.
        store.put("bad", "new")
        assertEquals("new", store.get("bad"))
    }

    @Test fun aTruncatedValueFileReadsAsAbsent() {
        val dir = tempDir()
        val store = FileStore(dir)
        store.put("k", "a value long enough to cut")
        val file = File(File(dir, "kv"), "k.kv")
        file.writeBytes(file.readBytes().copyOf(10))
        assertNull(store.get("k"))
        file.parentFile!!.resolve("k.kv").writeBytes(ByteArray(0))
        assertNull(store.get("k"))
    }

    @Test fun aTornLastLineIsSkippedAndTheNextAppendIsSafe() {
        val dir = tempDir()
        val sink = RecordingSink()
        val store = FileStore(dir, telemetry = sink)
        store.append("outbox", "turn-1")
        store.append("outbox", "turn-2")
        val log = File(File(dir, "logs"), "outbox.log")
        // Power cut in the middle of the third append.
        log.appendText(FileStore.encodeLine("turn-3").take(12))
        assertEquals(listOf("turn-1", "turn-2"), store.read("outbox"))
        store.append("outbox", "turn-4")
        assertEquals(listOf("turn-1", "turn-2", "turn-4"), FileStore(dir).read("outbox"))
        assertEquals("store.corrupt", sink.events.first().first)
        assertEquals("log", sink.events.first().second["kind"])
    }

    @Test fun aDamagedMiddleLineIsSkippedAndTrimRewritesClean() {
        val dir = tempDir()
        val store = FileStore(dir)
        listOf("a", "b", "c").forEach { store.append("log", it) }
        val log = File(File(dir, "logs"), "log.log")
        log.writeText(log.readText().replace(FileStore.encodeLine("b"), FileStore.encodeLine("b").dropLast(1) + "X"))
        assertEquals(listOf("a", "c"), store.read("log"))
        store.trim("log", 5)
        assertEquals(listOf("a", "c"), FileStore(dir).read("log"))
    }

    @Test fun notDurableKeysStillWriteAtomically() {
        val dir = tempDir()
        val store = FileStore(dir, durable = { !it.startsWith("ui/") })
        store.put("ui/scroll", "3")
        store.append("ui/log", "x")
        assertEquals("3", FileStore(dir).get("ui/scroll"))
        assertEquals(listOf("x"), FileStore(dir).read("ui/log"))
    }
}
