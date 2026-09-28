package com.example.myexodus.domainusage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U-12 (§12.1): 20 ordinary exceptions within `BREAKER_WINDOW_MS` ⇒ dormant +
 * `Degraded(INTERNAL_ERROR)`; a `VirtualMachineError` is rethrown, not counted toward the breaker.
 */
class DomainUsageEngineBreakerTest {

    private class FakeMonotonicClock(var now: Long = 0L) : MonotonicClock {
        override fun elapsedMs(): Long = now
    }

    private fun engine(clock: FakeMonotonicClock) =
        DomainUsageEngine(EngineMode.ENFORCE, clock)

    private fun DomainUsageEngine.fail() =
        hook("fallback") { throw IllegalStateException("hook boom") }

    @Test
    fun engineStartsDormant() {
        val engine = engine(FakeMonotonicClock())
        assertEquals(EngineStatus.Dormant, engine.status.value)
        assertFalse(engine.dormantUntilRestart)
    }

    @Test
    fun ordinaryException_returnsFallbackAndCounts() {
        val engine = engine(FakeMonotonicClock())
        var bodyRan = false

        val result = engine.hook("fallback") {
            bodyRan = true
            throw IllegalStateException("boom")
        }

        assertTrue(bodyRan)
        assertEquals("fallback", result)
        assertEquals(1L, engine.counters.get(Counter.hook_exceptions))
        assertFalse(engine.dormantUntilRestart)
        assertEquals(EngineStatus.Dormant, engine.status.value)
    }

    @Test
    fun twentyExceptionsWithinWindow_tripBreaker() {
        val clock = FakeMonotonicClock()
        val engine = engine(clock)

        repeat(MeteringConfig.BREAKER_MAX_EXCEPTIONS) {
            clock.now += 1_000L
            assertEquals("fallback", engine.fail())
        }

        assertTrue(engine.dormantUntilRestart)
        assertEquals(EngineStatus.Degraded(EngineStatus.Reason.INTERNAL_ERROR), engine.status.value)
        assertEquals(
            MeteringConfig.BREAKER_MAX_EXCEPTIONS.toLong(),
            engine.counters.get(Counter.hook_exceptions),
        )
        assertEquals(1L, engine.counters.get(Counter.breaker_tripped))
    }

    @Test
    fun exceptionsSpreadBeyondWindow_doNotTrip() {
        val clock = FakeMonotonicClock()
        val engine = engine(clock)

        // 19 exceptions, then a > window gap before the 20th: the oldest sample has fallen out
        // of the window, so the ring holds 20 entries but not 20 *within* the window.
        repeat(MeteringConfig.BREAKER_MAX_EXCEPTIONS - 1) { clock.now += 1_000L; engine.fail() }
        clock.now += MeteringConfig.BREAKER_WINDOW_MS + 1
        engine.fail()

        assertFalse(engine.dormantUntilRestart)
        assertEquals(0L, engine.counters.get(Counter.breaker_tripped))
    }

    @Test
    fun virtualMachineError_isCountedAndRethrown() {
        val engine = engine(FakeMonotonicClock())

        assertThrows(OutOfMemoryError::class.java) {
            engine.hook(Unit) { throw OutOfMemoryError("oom") }
        }

        assertEquals(1L, engine.counters.get(Counter.fatal_vm_error))
        assertEquals(0L, engine.counters.get(Counter.hook_exceptions))
        assertFalse(engine.dormantUntilRestart)
    }

    @Test
    fun dormantEngine_passesThroughWithoutRunningHooks() {
        val clock = FakeMonotonicClock()
        val engine = engine(clock)
        repeat(MeteringConfig.BREAKER_MAX_EXCEPTIONS) { engine.fail() }

        var bodyRan = false
        val result = engine.hook("fallback") {
            bodyRan = true
            throw IllegalStateException("boom")
        }

        assertFalse(bodyRan)
        assertEquals("fallback", result)
        assertEquals(
            MeteringConfig.BREAKER_MAX_EXCEPTIONS.toLong(),
            engine.counters.get(Counter.hook_exceptions),
        )
    }

    @Test
    fun successfulHook_returnsBodyValue() {
        val engine = engine(FakeMonotonicClock())
        assertEquals(42, engine.hook(-1) { 42 })
        assertEquals(0L, engine.counters.get(Counter.hook_exceptions))
    }
}
