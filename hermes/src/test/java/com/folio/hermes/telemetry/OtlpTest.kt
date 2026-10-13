package com.folio.hermes.telemetry

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class OtlpTest {
    @Test fun payloadMatchesTheOtlpJsonMapping() {
        val record = TelemetryRecord(
            timeMs = 1_700_000_000_123,
            severity = Severity.Error,
            body = "gateway.error",
            attributes = linkedMapOf(
                "event.name" to JsonPrimitive("gateway.error"),
                "code" to JsonPrimitive(503L),
                "retry" to JsonPrimitive(false),
                "ratio" to JsonPrimitive(0.25),
            ),
        )
        val json = Otlp.payload(linkedMapOf("service.name" to "hermes-live", "service.version" to "1.0.2"), listOf(record), 1_700_000_001_000).toString()
        val expected = """
            {"resourceLogs":[{"resource":{"attributes":[
            {"key":"service.name","value":{"stringValue":"hermes-live"}},
            {"key":"service.version","value":{"stringValue":"1.0.2"}}]},
            "scopeLogs":[{"scope":{"name":"com.folio.hermes.telemetry","version":"1"},"logRecords":[
            {"timeUnixNano":"1700000000123000000","observedTimeUnixNano":"1700000001000000000",
            "severityNumber":17,"severityText":"ERROR","body":{"stringValue":"gateway.error"},"attributes":[
            {"key":"event.name","value":{"stringValue":"gateway.error"}},
            {"key":"code","value":{"intValue":"503"}},
            {"key":"retry","value":{"boolValue":false}},
            {"key":"ratio","value":{"doubleValue":0.25}}]}]}]}]}
        """.trimIndent().replace("\n", "")
        assertEquals(expected, json)
    }
}
