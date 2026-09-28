package com.example.myexodus.domainusage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** S4.4 / INV-13: bounded, lock-free, and a slot comes back only through `OwnedBytes.release`. */
class InspectionBufferPoolTest {

    private val counters = EngineCounters()
    private val pool = InspectionBufferPool(size = 2, bufferBytes = 32, counters = counters)

    @Test
    fun acquireHandsOutDistinctBuffersAndThenReportsExhaustion() {
        val first = pool.acquire()
        val second = pool.acquire()
        assertNotNull(first)
        assertNotNull(second)
        assertTrue(first !== second)

        assertNull("pool of 2 has no third buffer", pool.acquire())
        assertEquals(1L, counters.get(Counter.inspect_pool_exhausted))
        assertEquals(0, pool.available())
    }

    @Test
    fun releaseReturnsTheSlotExactlyOnce() {
        val buffer = pool.acquire()!!
        assertEquals(1, pool.available())
        val owned = pool.wrap(buffer, length = 10)

        owned.release()
        assertEquals(2, pool.available())
        assertSame("the same buffer instance comes back", buffer, pool.acquire())
        assertEquals(1, pool.available())

        owned.release()
        assertEquals("a second release must not duplicate the slot", 1, pool.available())
    }

    @Test
    fun releaseIsSafeOnAConcurrentlyReleasingFlow() {
        val owned = listOf(pool.acquire()!!, pool.acquire()!!).map { pool.wrap(it, length = 1) }
        val threads = owned.map { bytes ->
            Thread { repeat(1000) { bytes.release() } }.also { it.start() }
        }
        threads.forEach { it.join() }
        assertEquals("both slots are back, no duplicates", 2, pool.available())
        assertNotNull(pool.acquire())
        assertNotNull(pool.acquire())
        assertNull(pool.acquire())
    }

    @Test
    fun wrappedBytesExposeTheBufferAndLength() {
        val buffer = pool.acquire()!!
        buffer[0] = 0x16
        val owned = pool.wrap(buffer, length = 7)
        assertSame(buffer, owned.bytes)
        assertEquals(7, owned.length)
    }
}
