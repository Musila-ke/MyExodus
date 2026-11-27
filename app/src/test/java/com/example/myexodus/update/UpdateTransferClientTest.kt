package com.example.myexodus.update

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** ADR #321 transfer core: resume, restart, redirects, size cap, protocol gate. */
class UpdateTransferClientTest {

    private val payload = ByteArray(100_000) { (it * 31 % 251).toByte() }
    private val servers = mutableListOf<HttpServer>()

    private val client = UpdateTransferClient(
        connectTimeoutMs = 2_000,
        readTimeoutMs = 2_000,
        allowedProtocols = setOf("http"),
    )

    private fun serve(handler: (HttpExchange) -> Unit): String {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> handler(exchange).also { exchange.close() } }
        server.start()
        servers += server
        return "http://127.0.0.1:${server.address.port}"
    }

    private fun respond(exchange: HttpExchange, code: Int, body: ByteArray) {
        exchange.sendResponseHeaders(code, body.size.toLong())
        // A capped client may hang up mid-body; that is the case under test, not a failure.
        runCatching { exchange.responseBody.use { it.write(body) } }
    }

    private fun tempFile(initial: ByteArray? = null): File =
        File.createTempFile("update-transfer", ".apk").apply {
            deleteOnExit()
            if (initial != null) writeBytes(initial)
        }

    @After
    fun stopServers() {
        servers.forEach { it.stop(0) }
    }

    @Test
    fun freshDownloadWritesExactlyTheExpectedBytes() {
        val base = serve { respond(it, 200, payload) }
        val file = tempFile()

        assertEquals(TransferOutcome.Complete, client.transfer("$base/apk", file, payload.size.toLong()))
        assertArrayEquals(payload, file.readBytes())
    }

    @Test
    fun resumeSendsRangeAndAppendsTheTail() {
        val rangeSeen = AtomicReference<String?>(null)
        val base = serve { exchange ->
            rangeSeen.set(exchange.requestHeaders.getFirst("Range"))
            respond(exchange, 206, payload.copyOfRange(40_000, payload.size))
        }
        val file = tempFile(payload.copyOfRange(0, 40_000))

        assertEquals(TransferOutcome.Complete, client.transfer("$base/apk", file, payload.size.toLong()))
        assertEquals("bytes=40000-", rangeSeen.get())
        assertArrayEquals(payload, file.readBytes())
    }

    @Test
    fun ignoredRangeRestartsTheFile() {
        val base = serve { respond(it, 200, payload) }
        val file = tempFile(ByteArray(777) { 7 })

        assertEquals(TransferOutcome.Complete, client.transfer("$base/apk", file, payload.size.toLong()))
        assertArrayEquals(payload, file.readBytes())
    }

    @Test
    fun oversizedPartialFileIsDiscardedAndRefetchedWhole() {
        val rangeSeen = AtomicReference<String?>(null)
        val base = serve { exchange ->
            rangeSeen.set(exchange.requestHeaders.getFirst("Range"))
            respond(exchange, 200, payload)
        }
        val file = tempFile(ByteArray(payload.size + 10) { 1 })

        assertEquals(TransferOutcome.Complete, client.transfer("$base/apk", file, payload.size.toLong()))
        assertEquals(null, rangeSeen.get())
        assertArrayEquals(payload, file.readBytes())
    }

    @Test
    fun redirectsAreFollowedIncludingRelativeLocations() {
        val base = serve { exchange ->
            when (exchange.requestURI.path) {
                "/start" -> {
                    exchange.responseHeaders.add("Location", "/end")
                    respond(exchange, 302, ByteArray(0))
                }
                else -> respond(exchange, 200, payload)
            }
        }
        val file = tempFile()

        assertEquals(
            TransferOutcome.Complete,
            client.transfer("$base/start", file, payload.size.toLong()),
        )
        assertArrayEquals(payload, file.readBytes())
    }

    @Test
    fun defaultClientRefusesPlainHttp() {
        val file = tempFile()
        assertEquals(
            TransferOutcome.Terminal(AttemptError.DOWNLOAD_FAILED),
            UpdateTransferClient().transfer("http://127.0.0.1:1/apk", file, 10),
        )
    }

    @Test
    fun serverErrorsRetry() {
        val base = serve { respond(it, 500, ByteArray(0)) }
        assertEquals(TransferOutcome.Retry, client.transfer("$base/apk", tempFile(), 10))
    }

    @Test
    fun extraBytesBeyondTheSignedSizeAreDropped() {
        val extended = payload + ByteArray(1_000) { 9 }
        val base = serve { respond(it, 200, extended) }
        val file = tempFile()

        assertEquals(TransferOutcome.Complete, client.transfer("$base/apk", file, payload.size.toLong()))
        assertEquals(payload.size.toLong(), file.length())
        assertArrayEquals(payload, file.readBytes())
    }
}
