package com.example.myexodus.accountability.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The 2026-10-01 invariant under the new bound: a timed-out wait must never overlap a second
 *  mint — the abandoned mint keeps running and the next caller joins its result. */
class SingleFlightMintTest {

    @Test
    fun timedOutWait_leavesTheMintRunning_andTheNextCallerReusesIt() = runTest {
        val gate = CompletableDeferred<Unit>()
        var mints = 0
        val single = SingleFlightMint<String>(this) {
            mints++
            gate.await()
            "uid"
        }

        assertNull(single.awaitOrNull(timeoutMs = 100))
        assertTrue("the abandoned wait must not cancel the mint", single.active)

        gate.complete(Unit)
        assertEquals("uid", single.awaitOrNull(timeoutMs = 1_000))
        assertEquals("one mint per flight — no second concurrent sign-in", 1, mints)
    }

    @Test
    fun settledFlight_isReplacedByTheNextCall() = runTest {
        var mints = 0
        val single = SingleFlightMint<String>(this) { "uid-${++mints}" }
        assertEquals("uid-1", single.awaitOrNull(timeoutMs = 1_000))
        assertEquals("uid-2", single.awaitOrNull(timeoutMs = 1_000))
    }

    @Test
    fun failedMint_returnsNull_andStopsBeingActive() = runTest {
        val single = SingleFlightMint<String>(this) { null }
        assertNull(single.awaitOrNull(timeoutMs = 1_000))
        assertFalse("a settled failure is not a wedged mint", single.active)
    }
}
