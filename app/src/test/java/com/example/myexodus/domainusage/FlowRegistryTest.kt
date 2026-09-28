package com.example.myexodus.domainusage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** §8 S5.3: per-rule tracking, the cap, and the exhaustion sweep. */
class FlowRegistryTest {

    private val counters = EngineCounters()
    private val registry = FlowRegistry(counters)

    private class Control : FlowControl {
        var resets = 0
        override fun reset() {
            resets++
        }
    }

    @Test
    fun resetAllResetsEveryTrackedFlowAndCountsTheSweep() {
        val first = Control()
        val second = Control()
        val otherRule = Control()
        registry.register("r1:0", first)
        registry.register("r1:0", second)
        registry.register("r2:0", otherRule)

        registry.resetAll("r1:0")

        assertEquals(1, first.resets)
        assertEquals(1, second.resets)
        assertEquals("another rule's flows are untouched", 0, otherRule.resets)
        assertEquals(2L, counters.get(Counter.flow_resets_sweep))
    }

    @Test
    fun unregisteringTheLastFlowEmptiesTheEntryAndForgetsTheKey() {
        val control = Control()
        registry.register("r1:0", control)
        assertFalse(registry.isEmpty("r1:0"))

        registry.unregister("r1:0", control)
        assertTrue(registry.isEmpty("r1:0"))
        assertTrue("an unregistered key is empty", registry.isEmpty("never-seen:0"))
    }

    @Test
    fun aFlowBeyondTheCapIsCountedButNotTracked() {
        val tracked = (0 until MeteringConfig.MAX_TRACKED_FLOWS_PER_RULE).map { Control() }
        tracked.forEach { registry.register("r1:0", it) }

        val overflow = Control()
        registry.register("r1:0", overflow)
        assertEquals(1L, counters.get(Counter.flow_registry_capped))

        registry.resetAll("r1:0")
        assertEquals("the tracked flows are still swept", 1, tracked.last().resets)
        assertEquals("the overflow flow is simply not tracked", 0, overflow.resets)
    }

    @Test
    fun clearDropsEveryEntry() {
        registry.register("r1:0", Control())
        registry.clear()
        assertTrue(registry.isEmpty("r1:0"))
    }
}
