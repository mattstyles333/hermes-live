package com.folio.hermes.cascade.routing

import com.folio.hermes.HermesJson
import com.folio.hermes.cascade.PhraseBank
import com.folio.hermes.openrouter.JevAnswer
import com.folio.hermes.openrouter.OpenRouterConfig
import com.folio.hermes.openrouter.SystemOneClient
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.Serializable

/**
 * Recorded Jev answers for the routing evals (test-shape plan, "recorded-Jev fixtures"): the
 * live answers to every eval case, captured once with a key (`LiveJevEvalTest` in record mode),
 * replayed offline by [RecordedJevTest] through the real floors and rules.
 *
 * Each case carries the SHA-256 of the exact System One request body the app builds for it
 * (model, state after the R-CTX-1 budget, questions with their wording). When the wording, the
 * state shape or the model changes, the fingerprint no longer matches and the replay fails with
 * "re-record". Answers only: no key, no headers, no latency beyond a number.
 */
object JevRecording {
    const val DIR = "/com/folio/hermes/cascade/routing/"
    const val PREFIX = "jev-recorded-"

    /** Where record mode writes (the test's working directory is the module directory). */
    val sourceDir = File("src/test/resources$DIR")

    const val RERECORD = "re-record: RECORD_JEV=1 OPENROUTER_API_KEY=… ./gradlew :hermes:testDebugUnitTest --tests '*LiveJevEvalTest*'"

    @Serializable
    data class Recorded(
        val set: String,
        val id: String,
        val fingerprint: String,
        /** Jev's answers, or null if the call failed while recording (scored as friction `no_answer`). */
        val answers: Map<String, JevAnswer>? = null,
        val ms: Long = 0,
        /** What the app did with [answers] when recorded ([RoutingEvals.effective]): pinned by the replay. */
        val effective: Map<String, String> = emptyMap(),
    )

    @Serializable
    data class Recording(val recorded: String, val model: String, val cases: List<Recorded>)

    /** The SHA-256 of the request body the app sends for [case] (as `SystemOneClient.decide` would). */
    fun fingerprint(case: EvalCase, bank: PhraseBank, model: String = OpenRouterConfig().jevModel): String {
        val request = RoutingEvals.request(case, bank)
        val body = SystemOneClient.body(model, request.state, request.questions).toString()
        return MessageDigest.getInstance("SHA-256").digest(body.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /** The newest recording on the test classpath, with its file name; null if there is none. */
    fun latest(): Pair<String, Recording>? {
        // The folder that holds the eval sets (the compiled classes have a folder of the same name).
        val dir = JevRecording::class.java.getResource(DIR + RoutingEvals.TUNED)?.let { File(it.toURI()).parentFile } ?: return null
        val file = dir.listFiles { f -> f.name.startsWith(PREFIX) && f.name.endsWith(".json") }?.maxByOrNull { it.name } ?: return null
        return file.name to HermesJson.decodeFromString(Recording.serializer(), file.readText())
    }

    /** One case per line, so a re-recording diffs case by case. */
    private fun encode(r: Recording): String = buildString {
        append("{\"recorded\":").append(HermesJson.encodeToString(kotlinx.serialization.serializer<String>(), r.recorded))
        append(",\"model\":").append(HermesJson.encodeToString(kotlinx.serialization.serializer<String>(), r.model))
        append(",\"cases\":[\n")
        append(r.cases.joinToString(",\n") { HermesJson.encodeToString(Recorded.serializer(), it) })
        append("\n]}\n")
    }

    /** Write [recording] as `jev-recorded-<date>.json` into the source resources; older recordings are removed. */
    fun write(recording: Recording): File {
        sourceDir.mkdirs()
        sourceDir.listFiles { f -> f.name.startsWith(PREFIX) }?.forEach { it.delete() }
        val file = File(sourceDir, "$PREFIX${recording.recorded}.json")
        file.writeText(encode(recording))
        return file
    }

    /** Rewrite the newest source recording's `effective` field from the current rules (offline, no key). */
    fun updateEffective(bank: PhraseBank, cases: Map<Pair<String, String>, EvalCase>): File {
        val file = sourceDir.listFiles { f -> f.name.startsWith(PREFIX) && f.name.endsWith(".json") }?.maxByOrNull { it.name }
            ?: error("no recording in $sourceDir")
        val recording = HermesJson.decodeFromString(Recording.serializer(), file.readText())
        val updated = recording.copy(
            cases = recording.cases.map { r ->
                val case = cases[r.set to r.id] ?: return@map r
                r.copy(effective = r.answers?.let { RoutingEvals.effective(case, it, bank) }.orEmpty())
            },
        )
        file.writeText(encode(updated))
        return file
    }
}
