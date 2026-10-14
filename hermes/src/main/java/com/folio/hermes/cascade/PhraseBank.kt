package com.folio.hermes.cascade

import com.folio.hermes.HermesJson
import kotlinx.serialization.Serializable

/**
 * One stock-line slot: Jev picks the slot by [use]; [LineRotator] picks which of [lines] plays.
 * No task names and no user content: anything specific is the writer's.
 */
@Serializable
data class PhraseSlot(val id: String, val group: String, val use: String, val lines: List<String>)

@Serializable
internal data class PhraseFile(
    val version: Int,
    val groups: Map<String, String> = emptyMap(),
    val slots: List<PhraseSlot> = emptyList(),
)

/**
 * Bill's stock lines (`hermes-cascade-phrases-2026-10-13.json`, version 3, shipped unchanged as a
 * resource): slots of 2–6 alternative lines. Every line is rendered once per voice and model
 * ([PhraseRenderer]) and played from the [TtsCache], so a stock line costs no TTS request.
 *
 * Jev picks a slot per turn from [jevSlots]; the app picks the event slots itself ([AppLines]).
 */
class PhraseBank(val version: Int, val groups: Map<String, String>, val slots: List<PhraseSlot>) {
    private val byId = slots.associateBy { it.id }

    operator fun get(id: String): PhraseSlot? = byId[id]

    /** Every distinct line in the bank (a line shared by two slots once). */
    val allLines: List<String> get() = slots.flatMap { it.lines }.distinct()

    /**
     * The slots Jev may choose from: the conversational groups, minus the slots the app picks
     * itself; `listen` slots only when [backchannel] is on (default off: it risks talking over the user).
     */
    fun jevSlots(backchannel: Boolean): List<PhraseSlot> = slots.filter { s ->
        s.id !in AppLines.APP_IDS && (s.group in CONVERSATIONAL || (backchannel && s.group == LISTEN))
    }

    companion object {
        const val RESOURCE = "/com/folio/hermes/cascade/hermes-cascade-phrases-2026-10-13.json"
        const val LISTEN = "listen"
        val CONVERSATIONAL = setOf("ack", "start", "update", "status", "result", "cancel", "clarify", "social")

        fun parse(json: String): PhraseBank {
            val file = HermesJson.decodeFromString(PhraseFile.serializer(), json)
            return PhraseBank(file.version, file.groups, file.slots.filter { it.lines.isNotEmpty() })
        }

        /** The bank shipped with the library. */
        fun load(): PhraseBank {
            val stream = PhraseBank::class.java.getResourceAsStream(RESOURCE) ?: error("phrase bank missing: $RESOURCE")
            return parse(stream.bufferedReader().use { it.readText() })
        }
    }
}

/** Slots the app plays on its own events (never offered to Jev), and fallbacks it uses itself. */
object AppLines {
    const val HI = "social.hi"
    const val WELCOME_BACK = "social.welcome_back"

    /** Results that arrived while the call was off or the line was down (bank v3). */
    const val WHILE_AWAY = "result.while_away"

    const val WAIT = "wait.brief"
    const val SIGNAL = "wait.signal"
    const val LIMIT_FULL = "limit.full"
    const val NEEDS_OK = "approval.needs_ok"
    const val QUESTION = "approval.question"
    const val HERMES_DOWN = "error.hermes"
    const val OFFLINE = "error.offline"
    const val ONLINE = "error.online"
    const val RETRY = "error.retry"
    const val GAVE_UP = "error.gave_up"
    const val BYE = "end.bye"
    const val BYE_TASKS = "end.bye_tasks"

    // Conversational slots the app also uses as fallbacks when Jev is not asked or not sure.
    const val SAY_AGAIN = "clarify.again"
    const val RESULT_BACK = "result.back"
    const val RESULT_FAILED = "result.failed"
    const val ON_SCREEN = "result.screen"
    const val WHICH_UPDATE = "update.which"
    const val WHICH_CANCEL = "cancel.which"
    const val WHICH_ONE = "clarify.which"

    /** The hold-silence rule's first prompt: held words that do not look like a request, then silence ("Go on."). */
    const val HOLD_GO_ON = "listen.patient"

    /** Its second, after more silence ("Just to check, go ahead?"). */
    const val HOLD_CONFIRM = "clarify.confirm"

    /** Groups only the app picks from. */
    val APP_GROUPS = setOf("wait", "limit", "approval", "error", "end")

    /** Conversational-group slots only the app picks (call start, catch-up). */
    val APP_IDS = setOf(HI, WELCOME_BACK, WHILE_AWAY)
}
