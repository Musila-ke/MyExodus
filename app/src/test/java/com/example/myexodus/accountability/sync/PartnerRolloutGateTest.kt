package com.example.myexodus.accountability.sync

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan §13 line 564: the registration rollout gate's policy — a remote `false` halts
 *  registration, and every failure mode degrades to enabled (never to a broken rail). */
class PartnerRolloutGateTest {

    private fun gate(readings: Map<String, Boolean>) =
        PartnerRolloutGate { key -> readings.getValue(key) }

    @Test
    fun remoteFalse_haltsRegistration() = runTest {
        assertFalse(gate(mapOf(PUSH_REGISTRATION_CONFIG_KEY to false)).pushRegistrationEnabled())
    }

    @Test
    fun remoteTrue_allowsRegistration() = runTest {
        assertTrue(gate(mapOf(PUSH_REGISTRATION_CONFIG_KEY to true)).pushRegistrationEnabled())
    }

    @Test
    fun fetchFailure_readsRegistrationAsEnabled() = runTest {
        val gate = PartnerRolloutGate { throw IllegalStateException("offline") }
        assertTrue(
            "a failed/unavailable remote read must degrade to today's behavior, never to a broken rail",
            gate.pushRegistrationEnabled(),
        )
    }

    @Test
    fun futureDatedFetchStamp_isWedged_untilTheClockCatchesUp() {
        val now = 1_700_000_000_000L
        val interval = 900_000L
        assertTrue(isFetchStampWedged(now + interval + 1, now, interval))
        assertFalse(
            "a stamp within one interval ahead is an ordinary recent fetch, not corruption",
            isFetchStampWedged(now + interval - 1_000, now, interval),
        )
        assertFalse(isFetchStampWedged(now - 60_000, now, interval))
        assertFalse("never fetched (-1) is not a wedge", isFetchStampWedged(-1, now, interval))
    }
}
