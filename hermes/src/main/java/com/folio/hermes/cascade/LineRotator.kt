package com.folio.hermes.cascade

import com.folio.hermes.HermesJson
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/** Where one slot is in its loop: the shuffled [order] (line texts), the next index and the last line played. */
@Serializable
data class Rotation(val order: List<String>, val cursor: Int, val last: String? = null)

/** Keeps each slot's [Rotation] across restarts. `:live` supplies a file in app-private storage. */
interface RotationStore {
    fun load(slotId: String): Rotation?
    fun save(slotId: String, rotation: Rotation)
}

class MemoryRotationStore : RotationStore {
    private val map = ConcurrentHashMap<String, Rotation>()
    override fun load(slotId: String): Rotation? = map[slotId]
    override fun save(slotId: String, rotation: Rotation) {
        map[slotId] = rotation
    }
}

/** All rotations in one small JSON file, rewritten on every play (a few KB). */
class FileRotationStore(private val file: File) : RotationStore {
    private val serializer = MapSerializer(String.serializer(), Rotation.serializer())
    private val map: MutableMap<String, Rotation> by lazy {
        runCatching { HermesJson.decodeFromString(serializer, file.readText()) }.getOrNull()?.toMutableMap() ?: mutableMapOf()
    }

    @Synchronized override fun load(slotId: String): Rotation? = map[slotId]

    @Synchronized override fun save(slotId: String, rotation: Rotation) {
        map[slotId] = rotation
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(HermesJson.encodeToString(serializer, map))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }
}

/**
 * Picks which line of a slot plays: each slot loops over a shuffled order of its lines, one line
 * per play, and the cursor is saved in [store]. At the end of a pass the lines are reshuffled and
 * the first line of the new pass is never the line just played. If the bank's lines for a slot
 * changed, the slot starts a fresh pass. Variety costs no Jev or model call.
 */
class LineRotator(
    private val bank: PhraseBank,
    private val store: RotationStore,
    private val random: Random = Random.Default,
) {
    /** The next line of [slotId] (advancing it), or null for an unknown slot. */
    @Synchronized
    fun next(slotId: String): String? {
        val lines = bank[slotId]?.lines?.takeIf { it.isNotEmpty() } ?: return null
        var rotation = store.load(slotId)?.takeIf { it.order.sorted() == lines.sorted() }
            ?: Rotation(shuffled(lines, null), 0, null)
        if (rotation.cursor >= rotation.order.size) rotation = Rotation(shuffled(lines, rotation.last), 0, rotation.last)
        val line = rotation.order[rotation.cursor]
        store.save(slotId, Rotation(rotation.order, rotation.cursor + 1, line))
        return line
    }

    private fun shuffled(lines: List<String>, notFirst: String?): List<String> {
        val order = lines.shuffled(random).toMutableList()
        if (notFirst != null && order.size > 1 && order[0] == notFirst) {
            val swap = 1 + random.nextInt(order.size - 1)
            order[0] = order[swap].also { order[swap] = order[0] }
        }
        return order
    }
}
