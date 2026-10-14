package com.folio.hermeslive.hooks

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.Response

/**
 * Debug and staging only (`src/hooks`): where each HTTP call's time goes, for the emulator smoke's
 * latency breakdown. Host and path only (never a query, header or body), and only while a test has
 * set `BuildHooks.netEvents`.
 */
class NetTimings : EventListener.Factory {
    /** One finished call: phases in ms (null when the phase did not happen, e.g. a pooled connection). */
    data class Row(
        val host: String,
        val path: String,
        val startMs: Long,
        val dnsMs: Long?,
        val connectMs: Long?,
        val tlsMs: Long?,
        val ttfbMs: Long?,
        val totalMs: Long,
        val protocol: String?,
        val reused: Boolean,
    )

    val rows = CopyOnWriteArrayList<Row>()

    override fun create(call: Call): EventListener = object : EventListener() {
        private fun now() = System.nanoTime() / 1_000_000
        private var start = 0L
        private var dnsStart: Long? = null
        private var dns: Long? = null
        private var connectStart: Long? = null
        private var connect: Long? = null
        private var tlsStart: Long? = null
        private var tls: Long? = null
        private var ttfb: Long? = null
        private var protocol: Protocol? = null
        private var newConnection = false

        override fun callStart(call: Call) { start = now() }
        override fun dnsStart(call: Call, domainName: String) { dnsStart = now() }
        override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) { dns = dnsStart?.let { now() - it } }
        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            newConnection = true
            connectStart = now()
        }
        override fun secureConnectStart(call: Call) { tlsStart = now() }
        override fun secureConnectEnd(call: Call, handshake: Handshake?) { tls = tlsStart?.let { now() - it } }
        override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
            connect = connectStart?.let { now() - it }
            this.protocol = protocol
        }
        override fun connectionAcquired(call: Call, connection: Connection) { protocol = connection.protocol() }
        override fun responseHeadersEnd(call: Call, response: Response) { if (ttfb == null) ttfb = now() - start }
        override fun callEnd(call: Call) = record()
        override fun callFailed(call: Call, ioe: java.io.IOException) = record()

        private fun record() {
            val url = call.request().url
            rows += Row(url.host, url.encodedPath, start, dns, connect, tls, ttfb, now() - start, protocol?.toString(), !newConnection)
        }
    }

    /** The calls to [host] whose path ends with [suffix], in order. */
    fun calls(host: String, suffix: String): List<Row> = rows.filter { it.host == host && it.path.endsWith(suffix) }.sortedBy { it.startMs }
}
