package com.example.myexodus.accountability.sync

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan §13 line 564: the registration rollout gate's policy — a remote `false` halts
 *  registration, and every failure mode degrades to enabled (never to a broken rail).
 *  SOS §8.20 gives the same gate the `partner_sos_enabled` read with the opposite failure
 *  default: a new feature whose read fails stays OFF. */
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
    fun sos_remoteFalse_haltsReachOut() = runTest {
        assertFalse(gate(mapOf(PARTNER_SOS_CONFIG_KEY to false)).sosEnabled())
    }

    @Test
    fun sos_remoteTrue_enablesReachOut() = runTest {
        assertTrue(gate(mapOf(PARTNER_SOS_CONFIG_KEY to true)).sosEnabled())
    }

    @Test
    fun sos_fetchFailure_readsAsDisabled() = runTest {
        val gate = PartnerRolloutGate { throw IllegalStateException("offline") }
        assertFalse(
            "a new feature whose read fails must stay off — never a surprise surface on",
            gate.sosEnabled(),
        )
    }
}
