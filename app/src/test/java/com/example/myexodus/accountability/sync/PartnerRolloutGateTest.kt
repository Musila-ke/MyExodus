package com.example.myexodus.accountability.sync

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan §13 line 564: the registration rollout gate's policy — a remote `false` halts
 *  registration, and every failure mode degrades to enabled (never to a broken rail). */
class PartnerRolloutGateTest {

    @Test
    fun remoteFalse_haltsRegistration() = runTest {
        assertFalse(PartnerRolloutGate { false }.pushRegistrationEnabled())
    }

    @Test
    fun remoteTrue_allowsRegistration() = runTest {
        assertTrue(PartnerRolloutGate { true }.pushRegistrationEnabled())
    }

    @Test
    fun fetchFailure_readsAsEnabled() = runTest {
        val gate = PartnerRolloutGate { throw IllegalStateException("offline") }
        assertTrue(
            "a failed/unavailable remote read must degrade to today's behavior, never to a broken rail",
            gate.pushRegistrationEnabled(),
        )
    }
}
