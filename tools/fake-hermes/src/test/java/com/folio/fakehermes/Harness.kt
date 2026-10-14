package com.folio.fakehermes

import com.folio.hermes.GatewayConnection
import com.folio.hermes.HermesAuth
import com.folio.hermes.HermesConversation
import com.folio.hermes.HermesGateway
import com.folio.hermes.InMemoryTokenStore
import com.folio.hermes.TurnUpdate
import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** A signed-in client (the real :hermes classes) against one fake Hermes process. */
class Harness(
    val fake: FakeHermesProcess = FakeHermesProcess(),
) : Closeable {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val config = fake.config()
    val store = InMemoryTokenStore()
    val auth = HermesAuth(config, store, fake.client)
    val gateway = HermesGateway(config, auth, fake.client, scope)

    fun signIn() = runBlocking { auth.signIn(FakeHermesProcess.USER, FakeHermesProcess.PASSWORD) }

    fun conversation(stored: String? = null) = HermesConversation(gateway, config, storedSessionId = stored)

    override fun close() {
        gateway.disconnect()
        scope.cancel()
        fake.close()
    }

    // ── admin shortcuts ────────────────────────────────────────────────────────────────────

    /** Wait (15 s hang guard) for a connection newer than [generation]: the sync point after a drop. */
    suspend fun reconnectedAfter(generation: Long) {
        withTimeout(15_000) { gateway.connection.first { it is GatewayConnection.Ready && it.generation > generation } }
    }

    fun faults(json: String) = fake.post("/__fake/faults", json)
    fun prompts(): JsonObject = fake.get("/__fake/assert/prompts")
    fun prompts(stored: String): List<JsonObject> =
        (prompts()["sessions"]!!.jsonObject[stored] as? JsonArray).orEmpty().map { it.jsonObject }

    fun doubles(): JsonObject = fake.get("/__fake/assert/double-submits")
    fun sessions(): List<JsonObject> = fake.get("/__fake/sessions")["sessions"]!!.jsonArray.map { it.jsonObject }
    fun session(stored: String): JsonObject = sessions().first { it.str("stored_session_id") == stored }
}

fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.content?.toIntOrNull()
fun JsonObject.arr(key: String): List<JsonObject> = (this[key] as? JsonArray).orEmpty().map { it.jsonObject }

/** Collect a turn up to and including its terminal update. */
suspend fun Flow<TurnUpdate>.untilDone(timeoutMs: Long = 15_000): List<TurnUpdate> = withTimeout(timeoutMs) {
    transformWhile {
        emit(it)
        it !is TurnUpdate.Completed && it !is TurnUpdate.Failed
    }.toList()
}

fun List<TurnUpdate>.completed(): TurnUpdate.Completed =
    lastOrNull() as? TurnUpdate.Completed ?: error("turn did not complete: $this")
