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
 *
 * The actor keeps rotations in its state and uses [Rotations.next] directly (pure, seeded); this
 * class is the same rule over a [RotationStore], for code outside a call.
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
        val (line, rotation) = Rotations.next(lines, store.load(slotId), random)
        store.save(slotId, rotation)
        return line
    }
}

/** The rotation rule, pure apart from [Random] (pass a seeded one for determinism). */
object Rotations {
    /** The line to play from [lines] and the slot's next rotation, given its saved [rotation]. */
    fun next(lines: List<String>, rotation: Rotation?, random: Random): Pair<String, Rotation> {
        var r = rotation?.takeIf { it.order.sorted() == lines.sorted() } ?: Rotation(shuffled(lines, null, random), 0, null)
        if (r.cursor >= r.order.size) r = Rotation(shuffled(lines, r.last, random), 0, r.last)
        val line = r.order[r.cursor]
        return line to Rotation(r.order, r.cursor + 1, line)
    }

    /**
     * [rotation] with a pass under way: unchanged if it has a line left for [lines], else the pass
     * [next] would shuffle (so `next` then plays its first line). The next line is then known
     * ahead, whatever other slots play first (`Event.PrimeLines`).
     */
    fun prime(lines: List<String>, rotation: Rotation?, random: Random): Rotation {
        val r = rotation?.takeIf { it.order.sorted() == lines.sorted() } ?: return Rotation(shuffled(lines, null, random), 0, null)
        return if (r.cursor >= r.order.size) Rotation(shuffled(lines, r.last, random), 0, r.last) else r
    }

    private fun shuffled(lines: List<String>, notFirst: String?, random: Random): List<String> {
        val order = lines.shuffled(random).toMutableList()
        if (notFirst != null && order.size > 1 && order[0] == notFirst) {
            val swap = 1 + random.nextInt(order.size - 1)
            order[0] = order[swap].also { order[swap] = order[0] }
        }
        return order
    }
}
