package com.example.myexodus.domainusage

import java.time.ZoneId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §6.3 / D-13 (U-9's flow-lifetime legs): the TCP/443 tap's hold → decide → forward path, its
 * buffer ownership (INV-13) and the FR-14 rule binding — a flow keeps the `(rule, revision)` it
 * opened against, even after an edit.
 */
class InspectingTcpTapTest {

    private class RecordingControl : FlowControl {
        var resets = 0
        override fun reset() {
            resets++
        }
    }

    private class Harness(val mode: EngineMode = EngineMode.ENFORCE) {
        val counters = EngineCounters()
        val mono = FakeMonotonicClock()
        val wall = FakeWallClock()
        val engine = DomainUsageEngine(mode, mono, counters)
        val ledger = UsageLedger(mono, wall, counters, engine.registry, mode, ZoneId.of("UTC"), "2026-09-20")
        val control = RecordingControl()
        val ip = DnsFixtures.ip(93, 184, 216, 34)

        init {
            engine.ledger = ledger
        }

        fun use(vararg patterns: String) {
            engine.updateRules(patterns.mapIndexed { i, p -> DomainRule("r${i + 1}", 0L, p, 3_600_000L, true) })
        }

        fun counter(id: String = "r1", revision: Long = 0L) = ledger.counterFor(id, revision)!!

        fun open(dstPort: Int = 443): FlowTap = engine.tcpFlowOpened(ip, dstPort, control)
    }

    private fun fixture(name: String): ByteArray =
        javaClass.getResourceAsStream("/tls/$name")!!.use { it.readBytes() }

    private val hello = fixture("fixture_a_example_com.bin")
    private val ech = fixture("fixture_c_chrome_ech.bin")

    /** A minimal, valid ClientHello for [sni] (the parser's own tests cover the wire format). */
    private fun clientHelloFor(sni: String): ByteArray {
        val name = sni.toByteArray(Charsets.US_ASCII)
        val entries = byteArrayOf(0x00) + u16(name.size) + name
        val sniBody = u16(entries.size) + entries
        val ext = u16(0x0000) + u16(sniBody.size) + sniBody
        val body = u16(0x0303) + ByteArray(32) + byteArrayOf(0) + u16(2) + byteArrayOf(0x13, 0x01) +
            byteArrayOf(1, 0) + u16(ext.size) + ext
        val handshake = byteArrayOf(0x01) +
            byteArrayOf((body.size shr 16).toByte(), (body.size shr 8).toByte(), body.size.toByte()) + body
        return byteArrayOf(0x16, 0x03, 0x01, (handshake.size shr 8).toByte(), handshake.size.toByte()) + handshake
    }

    private fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())

    // --- hold → decide → forward ---

    @Test
    fun aSplitClientHelloIsHeldUntilTheLastByteThenForwardedAsOnePiece() {
        val h = Harness()
        h.use("example.com")
        val tap = h.open()

        assertEquals(ClientDataResult.Hold, tap.onClientData(hello, 0, 100))
        val result = tap.onClientData(hello, 100, hello.size - 100)

        val forward = result as ClientDataResult.Forward
        assertEquals(hello.size, forward.data.length)
        assertArrayEquals(hello, forward.data.bytes.copyOf(hello.size))
        assertEquals(1L, h.counters.get(Counter.sni_ok))
        assertEquals(1L, h.counters.get(Counter.flows_attributed_total))
        assertEquals("the pooled slot stays with the Baseline until release()", 63, h.engine.pool.available())

        forward.data.release()
        assertEquals(64, h.engine.pool.available())
    }

    @Test
    fun theHeldChunkLandsInTheCountersOnceItIsRelayed() {
        val h = Harness()
        h.use("example.com")
        val tap = h.open()
        tap.onClientData(hello, 0, hello.size)

        // A full-size chunk after the decision is the flow's ordinary activity path.
        tap.onClientData(ByteArray(1_000), 0, 1_000)
        h.mono.now = 1_000
        h.ledger.tick()

        assertEquals(1_000L, h.counter().totalMs)
    }

    @Test
    fun aValidSniThatMatchesNoRuleIsUnattributedEvenWhenDnsWouldMatch() {
        val h = Harness()
        h.use("limited.example")
        h.engine.observations.record(h.ip, "limited.example", 300)

        val tap = h.open()
        val hello = clientHelloFor("unmatched.example")
        tap.onClientData(hello, 0, hello.size)

        assertEquals(1L, h.counters.get(Counter.flows_unattributed))
        assertEquals(0L, h.counters.get(Counter.flows_attributed_total))
        assertEquals(0L, h.counters.get(Counter.dns_fallback_attributed))
        assertTrue(h.engine.registry.isEmpty(h.counter().key))
    }

    @Test
    fun echFallsBackToDnsRatherThanBeingBlockedForBeingEch() {
        val h = Harness()
        h.use("limited.example")
        h.engine.observations.record(h.ip, "limited.example", 300)

        val tap = h.open()
        val result = tap.onClientData(ech, 0, ech.size)

        assertTrue(result is ClientDataResult.Forward)
        assertEquals(1L, h.counters.get(Counter.sni_ech))
        assertEquals(1L, h.counters.get(Counter.flows_attributed_total))
        assertEquals(1L, h.counters.get(Counter.dns_fallback_attributed))
    }

    @Test
    fun aStalledClientHelloTimesOutAndForwardsTheHeldBytes() {
        val h = Harness()
        h.use("example.com")
        val tap = h.open()

        val split = hello.size / 2
        assertEquals(ClientDataResult.Hold, tap.onClientData(hello, 0, split))

        // Still one byte short when the deadline passes: the chunk that drives the check decides
        // the flow, and its own bytes ride along unmetered (NFR-6).
        h.mono.now = MeteringConfig.MAX_INSPECTION_HOLD_MS
        val held = hello.size - 1
        val result = tap.onClientData(hello, split, held - split)

        val forward = result as ClientDataResult.Forward
        assertEquals(held, forward.data.length)
        assertArrayEquals(hello.copyOf(held), forward.data.bytes.copyOf(held))
        assertEquals(1L, h.counters.get(Counter.inspect_timeout))
        assertEquals(0L, h.counters.get(Counter.sni_ok))
    }

    @Test
    fun aFullBufferEndsTheHoldAsTooLarge() {
        val h = Harness()
        h.use("example.com")
        val tap = h.open()

        // Empty handshake records: always valid, never enough — the only way to reach the ceiling.
        val fill = ByteArray(MeteringConfig.INSPECT_MAX_BYTES)
        var i = 0
        while (i + 5 <= fill.size) {
            fill[i] = 0x16
            fill[i + 1] = 0x03
            fill[i + 2] = 0x01
            i += 5
        }
        val result = tap.onClientData(fill, 0, fill.size)

        assertEquals(MeteringConfig.INSPECT_MAX_BYTES, (result as ClientDataResult.Forward).data.length)
        assertEquals(1L, h.counters.get(Counter.sni_toolarge))
    }

    // --- enforcement ---

    @Test
    fun anExhaustedRuleResetsTheFlowAtAttributionAndReturnsItsBuffer() {
        val h = Harness()
        h.use("example.com")
        h.counter().exhausted = true

        val tap = h.open()
        assertEquals(ClientDataResult.Reset, tap.onClientData(hello, 0, hello.size))
        assertEquals(1, h.control.resets)
        assertEquals(1L, h.counters.get(Counter.flow_resets_new))

        tap.onClosed()
        assertEquals("no Forward was handed out, so close returns the slot", 64, h.engine.pool.available())
    }

    @Test
    fun accountModeComputesExhaustionWithoutResettingAnything() {
        val h = Harness(EngineMode.ACCOUNT)
        h.use("example.com")
        h.counter().exhausted = true

        val tap = h.open()
        assertTrue(tap.onClientData(hello, 0, hello.size) is ClientDataResult.Forward)
        assertEquals(0, h.control.resets)
        assertEquals(0L, h.counters.get(Counter.flow_resets_new))
        assertEquals(1L, h.counters.get(Counter.flows_attributed_total))
    }

    // --- buffer ownership (INV-13) ---

    @Test
    fun aCloseDuringTheHoldReleasesTheBufferAndLaterChunksPassThrough() {
        val h = Harness()
        h.use("example.com")
        val tap = h.open()
        assertEquals(ClientDataResult.Hold, tap.onClientData(hello, 0, 100))

        tap.onClosed()
        assertEquals(64, h.engine.pool.available())
        assertEquals(ClientDataResult.PassThrough, tap.onClientData(hello, 100, hello.size - 100))
        assertEquals(0L, h.counters.get(Counter.sni_ok))
    }

    @Test
    fun aCloseAfterForwardingDoesNotTakeTheSlotBackFromTheBaseline() {
        val h = Harness()
        h.use("example.com")
        val tap = h.open()
        val forward = tap.onClientData(hello, 0, hello.size) as ClientDataResult.Forward

        tap.onClosed()
        assertEquals(63, h.engine.pool.available())

        forward.data.release()
        assertEquals(64, h.engine.pool.available())
    }

    @Test
    fun dormancyForwardsWhateverWasAlreadyHeldAndStopsHolding() {
        val h = Harness()
        h.use("example.com")
        val tap = h.open()
        assertEquals(ClientDataResult.Hold, tap.onClientData(hello, 0, 100))

        repeat(MeteringConfig.BREAKER_MAX_EXCEPTIONS) { h.engine.onHookException() }
        assertTrue(h.engine.dormantUntilRestart)
        val result = tap.onClientData(hello, 100, hello.size - 100)

        val forward = result as ClientDataResult.Forward
        assertEquals("only the bytes held before dormancy", 100, forward.data.length)
        assertArrayEquals(hello.copyOf(100), forward.data.bytes.copyOf(100))
        assertEquals(0L, h.counters.get(Counter.sni_ok))
    }

    @Test
    fun thePoolCeilingFallsBackToDnsAttributionAtOpen() {
        val h = Harness()
        h.use("limited.example")
        h.engine.observations.record(h.ip, "limited.example", 300)
        repeat(MeteringConfig.INSPECT_POOL_SIZE) { h.engine.pool.acquire() }

        val tap = h.open()
        assertEquals(1L, h.counters.get(Counter.inspect_pool_exhausted))

        // Non-inspecting tap: bytes flow straight through, and the flow is still metered.
        assertEquals(ClientDataResult.PassThrough, tap.onClientData(ByteArray(1_000), 0, 1_000))
        h.mono.now = 1_000
        h.ledger.tick()
        assertEquals(1_000L, h.counter().totalMs)
    }

    // --- revision binding (D-32 / FR-14) ---

    @Test
    fun aFlowKeepsCountingTheRevisionItOpenedAgainstAfterAnEdit() {
        val h = Harness()
        h.use("example.com")
        val tap = h.open()
        tap.onClientData(hello, 0, hello.size)

        h.engine.updateRules(listOf(DomainRule("r1", 1L, "example.com", 3_600_000L, true)))
        tap.onClientData(ByteArray(1_000), 0, 1_000)
        h.mono.now = 1_000
        h.ledger.tick()

        assertEquals("the flow stays on revision 0", 1_000L, h.counter(revision = 0L).totalMs)
        assertEquals("the new revision starts clean", 0L, h.counter(revision = 1L).totalMs)
    }

    @Test
    fun aFlowOpenedAfterAnEditBindsTheNewRevision() {
        val h = Harness()
        h.use("example.com")
        h.engine.updateRules(listOf(DomainRule("r1", 1L, "example.com", 3_600_000L, true)))

        val tap = h.open()
        tap.onClientData(hello, 0, hello.size)

        assertEquals(1L, h.counters.get(Counter.flows_attributed_total))
        assertTrue("the old revision's counter is not the one bound", h.ledger.counterFor("r1", 0L) == null || h.ledger.counterFor("r1", 0L)!!.totalMs == 0L)
    }
}
