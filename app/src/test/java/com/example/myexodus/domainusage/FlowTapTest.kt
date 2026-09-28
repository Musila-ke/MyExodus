package com.example.myexodus.domainusage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The taps the Engine hands the Baseline: [ActivityTap]/[UdpActivityTap] accounting (§4.7's
 * `< MIN_ACTIVITY_BYTES` leg of U-10 among them) and [GuardedTap]'s INV-6 behavior — taps are
 * called directly, not through `engine.hook`, so the guard is what makes them exception-safe.
 */
class FlowTapTest {

    private val counters = EngineCounters()
    private val mono = FakeMonotonicClock()
    private val wall = FakeWallClock()
    private val engine = DomainUsageEngine(EngineMode.ENFORCE, mono, counters)
    private val ledger = UsageLedger(mono, wall, counters, engine.registry, EngineMode.ENFORCE, java.time.ZoneId.of("UTC"), "2026-09-20")

    private class RecordingControl : FlowControl {
        var resets = 0
        override fun reset() {
            resets++
        }
    }

    private fun counter(id: String = "r1") = RuleCounter(id, 0L, dailyLimitMs = 3_600_000L, enforcedToday = true)

    private fun attach(c: RuleCounter) {
        engine.ledger = ledger
        engine.updateRules(listOf(DomainRule(c.ruleId, c.revision, "limited.example", c.dailyLimitMs, true)))
    }

    // --- ActivityTap ---

    @Test
    fun chunksUnder512BytesAreNotActivity() {
        attach(counter())
        val tap = ActivityTap(engine, ledger.counterFor("r1", 0)!!, RecordingControl())

        mono.now = 1_000
        assertEquals(ClientDataResult.PassThrough, tap.onClientData(ByteArray(511), 0, 511))
        mono.now = 2_000
        ledger.tick()

        assertEquals("a sub-threshold chunk never opens an activity streak", 0L, ledger.counterFor("r1", 0)!!.totalMs)
    }

    @Test
    fun aChunkAtTheThresholdCountsAndBothDirectionsAccrue() {
        attach(counter())
        val c = ledger.counterFor("r1", 0)!!
        val tap = ActivityTap(engine, c, RecordingControl())

        mono.now = 1_000
        assertEquals(ClientDataResult.PassThrough, tap.onClientData(ByteArray(512), 0, 512))
        mono.now = 2_000
        ledger.tick()
        assertEquals(1_000L, c.totalMs)

        tap.onServerData(1_024)
        mono.now = 3_000
        ledger.tick()
        assertEquals("the server direction keeps the streak alive across the gap", 2_000L, c.totalMs)
    }

    @Test
    fun theTapUnregistersItsFlowOnClose() {
        val c = counter()
        attach(c)
        val control = RecordingControl()
        engine.registry.register(c.key, control)

        ActivityTap(engine, c, control).onClosed()

        assertTrue(engine.registry.isEmpty(c.key))
    }

    // --- UdpActivityTap (§6.4) ---

    @Test
    fun udpDatagramsAreDroppedOnceTheRuleIsExhaustedInEnforce() {
        val c = counter()
        attach(c)
        val tap = UdpActivityTap(engine, c, RecordingControl())

        assertEquals(ClientDataResult.PassThrough, tap.onClientData(ByteArray(600), 0, 600))

        c.exhausted = true
        assertEquals(ClientDataResult.Reset, tap.onClientData(ByteArray(600), 0, 600))
        assertEquals(1L, counters.get(Counter.udp_dropped))
    }

    @Test
    fun udpDatagramsAreNeverDroppedInAccountMode() {
        attach(counter())
        val c = ledger.counterFor("r1", 0)!!
        c.exhausted = true
        val account = DomainUsageEngine(EngineMode.ACCOUNT, mono, counters)
        account.ledger = ledger

        assertEquals(ClientDataResult.PassThrough, UdpActivityTap(account, c, RecordingControl()).onClientData(ByteArray(600), 0, 600))
        assertEquals(0L, counters.get(Counter.udp_dropped))
    }

    // --- GuardedTap (INV-6 / D-35) ---

    private class ThrowingTap(private val error: Throwable) : FlowTap {
        var closed = 0
        override fun onClientData(data: ByteArray, off: Int, len: Int): ClientDataResult = throw error
        override fun onServerData(len: Int) = throw error
        override fun onClosed() {
            closed++
        }
    }

    @Test
    fun anOrdinaryExceptionPassesTheChunkThroughAndClosesTheInnerTap() {
        val inner = ThrowingTap(IllegalStateException("tap boom"))
        val tap = GuardedTap(engine, inner)

        assertEquals(ClientDataResult.PassThrough, tap.onClientData(ByteArray(16), 0, 16))
        assertEquals(1L, counters.get(Counter.hook_exceptions))
        assertEquals("a faulting tap must not strand a buffer or a registry entry", 1, inner.closed)
    }

    @Test
    fun aServerDataFaultIsCountedAndSwallowed() {
        val tap = GuardedTap(engine, ThrowingTap(IllegalStateException("server boom")))
        tap.onServerData(64)
        assertEquals(1L, counters.get(Counter.hook_exceptions))
    }

    @Test
    fun aVirtualMachineErrorIsCountedAndRethrown() {
        val tap = GuardedTap(engine, ThrowingTap(OutOfMemoryError("vm boom")))

        assertThrows(OutOfMemoryError::class.java) { tap.onClientData(ByteArray(16), 0, 16) }
        assertEquals(1L, counters.get(Counter.fatal_vm_error))
        assertEquals(0L, counters.get(Counter.hook_exceptions))
    }
}
