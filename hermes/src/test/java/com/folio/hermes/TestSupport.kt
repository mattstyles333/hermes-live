package com.folio.hermes

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest

internal fun json(code: Int, body: String, vararg headers: Pair<String, String>): MockResponse =
    MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").apply {
        headers.forEach { (k, v) -> addHeader(k, v) }
    }.body(body).build()

internal fun RecordedRequest.jsonBody(): JsonObject = HermesJson.parseToJsonElement(body!!.utf8()).jsonObject

internal fun MockWebServer.config(vararg overrides: (HermesConfig) -> HermesConfig): HermesConfig {
    // MockWebServer is plain http on 127.0.0.1; the endpoint check is bypassed here only.
    val endpoint = testEndpoint(url("/").toString())
    return overrides.fold(HermesConfig(endpoint)) { acc, f -> f(acc) }
}

/** Build an endpoint without the cleartext rule, for local MockWebServer tests. */
internal fun testEndpoint(raw: String): HermesEndpoint = HermesEndpoint.forTesting(raw)
