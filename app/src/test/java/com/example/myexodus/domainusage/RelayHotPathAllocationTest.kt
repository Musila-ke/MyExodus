package com.example.myexodus.domainusage

import java.lang.management.ManagementFactory
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * §11 PF-6: the relay hot path allocates nothing once a flow has been decided — `touch`, the
 * post-decision `onClientData`, and `onServerData`. INV-12 is the invariant behind it: the packet
 * and relay threads must not hand work to the GC just because a page loaded.
 *
 * This is a regression guard, not a proof: it pins the behaviour of *this* JVM (HotSpot's JIT is
 * free to deopt, and ART's behaviour on-device is not observable from here). The measured thread
 * does nothing but call the target, so any growth is the target's — provided the harness itself
 * allocates nothing, which is why [minWindowGrowth] is `inline` (a `(Int) -> Unit` parameter would
 * box each argument and report the harness's own junk as the target's).
 *
 * Why the minimum over windows: `getCurrentThreadAllocatedBytes` also counts a TLAB refill, which
 * lands as one large jump in an otherwise flat series. A refill only happens when an allocation
 * is *attempted*, so the minimum window of a genuinely allocation-free loop is exactly zero —
 * taking the minimum removes refill noise without loosening the assertion to "not much per call".
 */
class RelayHotPathAllocationTest {

    private class Harness {
        val counters = EngineCounters()
        val mono = FakeMonotonicClock()
        val wall = FakeWallClock()
        val engine = DomainUsageEngine(EngineMode.ENFORCE, mono, counters)
        val ledger = UsageLedger(mono, wall, counters, engine.registry, EngineMode.ENFORCE, ZoneId.of("UTC"), "2026-09-20")
        val control = object : FlowControl {
            override fun reset() = Unit
        }

        init {
            engine.ledger = ledger
            engine.updateRules(listOf(DomainRule("r1", 0L, "example.com", 3_600_000L, true)))
        }

        /** The real call path the Baseline holds — `GuardedTap`, not the inner tap. */
        fun decidedTap(): FlowTap {
            val tap = engine.tcpFlowOpened(DnsFixtures.ip(93, 184, 216, 34), 443, control)
            val hello = javaClass.getResourceAsStream("/tls/fixture_a_example_com.bin")!!.use { it.readBytes() }
            val forwarded = tap.onClientData(hello, 0, hello.size) as ClientDataResult.Forward
            forwarded.data.release()
            return tap
        }
    }

    private val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    private fun allocated(): Long = bean.currentThreadAllocatedBytes

    /** Smallest per-window growth over [windows] windows of [perWindow] calls, after [warmup]. */
    private inline fun minWindowGrowth(warmup: Int, windows: Int, perWindow: Int, body: () -> Unit): Long {
        var i = 0
        while (i < warmup) {
            body()
            i++
        }
        var min = Long.MAX_VALUE
        var w = 0
        while (w < windows) {
            val before = allocated()
            i = 0
            while (i < perWindow) {
                body()
                i++
            }
            val growth = allocated() - before
            if (growth < min) min = growth
            w++
        }
        return min
    }

    @Test
    fun touchAllocatesNothing() {
        assumeTrue("JVM exposes per-thread allocation", bean.isThreadAllocatedMemorySupported)
        val counter = RuleCounter(
            "r1", 0L, pattern = "example.com", dailyLimitMs = 3_600_000L, enforcedToday = true,
        )
        var elapsed = 0L

        val growth = minWindowGrowth(WARMUP, WINDOWS, PER_WINDOW) {
            elapsed += 1_000L
            counter.touch(elapsed)
        }
        assertEquals("RuleCounter.touch must stay allocation-free (INV-12)", 0L, growth)
    }

    @Test
    fun postDecisionClientDataAllocatesNothing() {
        assumeTrue("JVM exposes per-thread allocation", bean.isThreadAllocatedMemorySupported)
        val tap = Harness().decidedTap()
        val chunk = ByteArray(MeteringConfig.MIN_ACTIVITY_BYTES)

        val growth = minWindowGrowth(WARMUP, WINDOWS, PER_WINDOW) {
            tap.onClientData(chunk, 0, chunk.size)
        }
        assertEquals("the post-decision client path must stay allocation-free (PF-6)", 0L, growth)
    }

    @Test
    fun serverDataAllocatesNothing() {
        assumeTrue("JVM exposes per-thread allocation", bean.isThreadAllocatedMemorySupported)
        val tap = Harness().decidedTap()

        val growth = minWindowGrowth(WARMUP, WINDOWS, PER_WINDOW) {
            tap.onServerData(MeteringConfig.MIN_ACTIVITY_BYTES)
        }
        assertEquals("server→client accounting must stay allocation-free (PF-6)", 0L, growth)
    }

    private companion object {
        const val WARMUP = 200_000
        const val WINDOWS = 20
        const val PER_WINDOW = 100_000
    }
}
