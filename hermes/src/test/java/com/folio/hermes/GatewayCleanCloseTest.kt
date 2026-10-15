package com.folio.hermes

import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * [HermesGateway.disconnect] closes the socket cleanly (close 1000); it never cuts it. The gateway's
 * scope runs on one single-thread dispatcher and disconnect is called on it, as Folio's HermesAsk does.
 *
 * The flake this pins (Folio `HermesAskWireTest`, CI 2026-10-13): disconnect sent close(1000) and
 * cancelled the loop; the loop's cleanup then ran `ws.cancel()` on the dispatcher thread while
 * OkHttp's writer thread had not yet written the close frame. When the cancel won, the server saw a
 * dropped socket instead of the close.
 */
class GatewayCleanCloseTest {
    private val fake = FakeHermesServer()
    private val executor = Executors.newSingleThreadExecutor()
    private val main = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + main)
    private val gate = CloseGate()

    private fun gateway(config: HermesConfig = fake.config()) =
        HermesGateway(config, FakeTokens(), OkHttpClient.Builder().socketFactory(gate).build(), scope)

    @After fun tearDown() {
        gate.release()
        scope.cancel()
        fake.close()
        executor.shutdownNow()
    }

    private fun test(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    /** The server saw a clean close with [code]; otherwise the failure says how it saw the socket go. */
    private suspend fun FakeHermesServer.ServerSide.assertClosedWith(code: Int) {
        val how = withTimeout(5_000) {
            select<Any> {
                closing.onAwait { it }
                failed.onAwait { it }
            }
        }
        assertEquals("server saw: $how", code, how)
    }

    @Test fun disconnectOnTheDispatcherClosesWith1000() = test {
        val gateway = gateway()
        gateway.awaitReady()
        val socket = fake.nextSocket()
        withContext(main) { gateway.disconnect() }
        socket.assertClosedWith(1000)
        assertEquals(GatewayConnection.Idle, gateway.connection.value)
    }

    @Test fun cleanupNeverCutsTheSocketUnderTheCloseFrame() = test {
        gate.hold = true
        val gateway = gateway()
        gateway.awaitReady()
        val socket = fake.nextSocket()
        withContext(main) { gateway.disconnect() }
        gate.closeFrameWritten.await() // the close frame reached the socket and waits there
        // The server speaks while the close is held: the client has left, so the frame must not count.
        // Not checked: if the cleanup already cut the socket, the send fails and the close assert says so.
        socket.ws.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"message.start","session_id":"s1"}}""")
        // The loop's cleanup was queued on this one thread by disconnect, so it has run by now.
        withContext(main) { }
        gate.release()
        socket.assertClosedWith(1000)
        // The client socket closes only after the client read the server's close, which came after the event.
        gate.closeSocketClosed.await()
        assertFalse(gateway.isRunning("s1"))
        assertEquals(GatewayConnection.Idle, gateway.connection.value)
    }
}

/**
 * Client sockets that can hold the WebSocket close frame (a write whose first byte is 0x88: FIN +
 * close) until [release]. A test uses it to run the gateway's cleanup before that frame leaves, which
 * is the order the CI flake hit by chance.
 */
private class CloseGate : SocketFactory() {
    @Volatile var hold = false
    private val released = CountDownLatch(1)

    /** A close frame reached a client socket (and waits there while [hold] is set). */
    val closeFrameWritten = CompletableDeferred<Unit>()

    /** The client socket that carried the close frame was closed. */
    val closeSocketClosed = CompletableDeferred<Unit>()

    fun release() = released.countDown()

    override fun createSocket(): Socket = Gated()
    override fun createSocket(host: String, port: Int): Socket = Gated().apply { connect(InetSocketAddress(host, port)) }
    override fun createSocket(host: InetAddress, port: Int): Socket = Gated().apply { connect(InetSocketAddress(host, port)) }
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        throw UnsupportedOperationException()
    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        throw UnsupportedOperationException()

    private inner class Gated : Socket() {
        @Volatile private var sentClose = false

        override fun getOutputStream(): OutputStream {
            val raw = super.getOutputStream()
            return object : OutputStream() {
                override fun write(b: Int) = raw.write(b)

                override fun write(b: ByteArray, off: Int, len: Int) {
                    if (len > 0 && b[off] == CLOSE_FRAME) {
                        sentClose = true
                        closeFrameWritten.complete(Unit)
                        if (hold) released.await(10, TimeUnit.SECONDS)
                    }
                    raw.write(b, off, len)
                }

                override fun flush() = raw.flush()
                override fun close() = raw.close()
            }
        }

        override fun close() {
            super.close()
            if (sentClose) closeSocketClosed.complete(Unit)
        }
    }

    private companion object {
        const val CLOSE_FRAME = 0x88.toByte()
    }
}
