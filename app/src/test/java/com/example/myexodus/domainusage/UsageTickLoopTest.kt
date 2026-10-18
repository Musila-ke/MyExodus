package com.example.myexodus.domainusage

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** §8 S5.4: the loop's cadence, its persist hook, the rollover hand-off, and fault tolerance. */
class UsageTickLoopTest {

    private val zone = ZoneId.of("UTC")

    @Test
    fun theLoopBooksAccrualOnItsCadenceAndPostsAPersistEveryTenTicks() = runTest {
        val counters = EngineCounters()
        val mono = object : MonotonicClock {
            override fun elapsedMs(): Long = testScheduler.currentTime
        }
        val wall = object : WallClock {
            private val instant = Instant.parse("2026-09-20T12:00:00Z")
            override fun now(zone: ZoneId): LocalDateTime = LocalDateTime.ofInstant(instant, zone)
            override fun nowMillis(): Long = instant.toEpochMilli()
        }
        val ledger = UsageLedger(mono, wall, counters, FlowRegistry(counters), EngineMode.ENFORCE, zone)
        ledger.applyRules(listOf(DomainRule("r1", 0L, "example.com", 3_600_000L, true)))
        ledger.counterFor("r1", 0L)!!.touch(0L)

        var persists = 0
        val job = UsageTickLoop(ledger, counters, onPersistDue = { persists++ }).launch(this)

        testScheduler.advanceTimeBy(9_000)
        testScheduler.runCurrent()
        assertEquals(0, persists)

        testScheduler.advanceTimeBy(27_000)
        testScheduler.runCurrent()
        assertEquals(3, persists)

        val total = ledger.counterFor("r1", 0L)!!.totalMs
        assertEquals("one chunk at t=0 books its 15 s tail, by whichever ticks ran", 15_000L, total)
        job.cancel()
    }

    @Test
    fun aDayChangeIsHandedToTheRolloverCallbackExactlyOnce() = runTest {
        val counters = EngineCounters()
        val wallStart = Instant.parse("2026-09-20T23:59:55Z")
        val mono = object : MonotonicClock {
            override fun elapsedMs(): Long = testScheduler.currentTime
        }
        val wall = object : WallClock {
            override fun now(zone: ZoneId): LocalDateTime =
                LocalDateTime.ofInstant(wallStart.plusMillis(testScheduler.currentTime), zone)
            override fun nowMillis(): Long = wallStart.toEpochMilli() + testScheduler.currentTime
        }
        val ledger = UsageLedger(mono, wall, counters, FlowRegistry(counters), EngineMode.ENFORCE, zone)
        val rollovers = mutableListOf<DayRollover>()
        val job = UsageTickLoop(ledger, counters, onRollover = { rollovers += it }).launch(this)

        testScheduler.advanceTimeBy(10_000)
        testScheduler.runCurrent()

        assertEquals(1, rollovers.size)
        assertEquals("2026-09-20", rollovers.single().oldDayKey)
        assertEquals("2026-09-21", rollovers.single().newDayKey)
        assertEquals("2026-09-21", ledger.dayKey)
        job.cancel()
    }

    @Test
    fun anOrdinaryFaultIsCountedAndTheLoopKeepsTicking() = runTest {
        val counters = EngineCounters()
        val mono = object : MonotonicClock {
            override fun elapsedMs(): Long = testScheduler.currentTime
        }
        val wall = object : WallClock {
            private val instant = Instant.parse("2026-09-20T12:00:00Z")
            override fun now(zone: ZoneId): LocalDateTime = LocalDateTime.ofInstant(instant, zone)
            override fun nowMillis(): Long = instant.toEpochMilli()
        }
        val ledger = UsageLedger(mono, wall, counters, FlowRegistry(counters), EngineMode.ENFORCE, zone)

        var attempts = 0
        val job = UsageTickLoop(
            ledger, counters,
            onPersistDue = {
                attempts++
                throw IllegalStateException("db down")
            },
        ).launch(this)

        testScheduler.advanceTimeBy(30_000)
        testScheduler.runCurrent()

        assertEquals("the loop survived each fault and kept its cadence", 3, attempts)
        assertEquals(3L, counters.get(Counter.hook_exceptions))
        assertTrue(job.isActive)
        job.cancel()
    }
}
