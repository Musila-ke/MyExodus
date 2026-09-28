package com.example.myexodus.blocking.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * §8.7/R8's prompt routing truth table. The offer exists only while a corroborated gap is
 * waiting AND the app is not already exempt; the direct exemption intent is taken when the
 * distribution permits it, and the settings-guidance fallback is real code, not a comment —
 * a build without the permission must still route the user somewhere that can fix the state.
 */
class BatteryPromptGateTest {

    @Test
    fun alreadyExempt_neverOffers_howeverEligible() {
        assertNull(BatteryPromptGate.route(alreadyExempt = true, promptEligible = true))
        assertNull(BatteryPromptGate.route(alreadyExempt = true, promptEligible = false))
    }

    @Test
    fun noEligibleGap_neverOffers() {
        assertNull(BatteryPromptGate.route(alreadyExempt = false, promptEligible = false))
    }

    @Test
    fun eligibleAndNotExempt_takesTheDirectRequestWhenDistributionPermits() {
        assertEquals(
            BatteryPromptRoute.DIRECT_REQUEST,
            BatteryPromptGate.route(alreadyExempt = false, promptEligible = true, directRequestPermitted = true),
        )
    }

    @Test
    fun eligibleAndNotExempt_fallsBackToSettingsGuidanceWhenItDoesNot() {
        assertEquals(
            BatteryPromptRoute.SETTINGS_GUIDANCE,
            BatteryPromptGate.route(alreadyExempt = false, promptEligible = true, directRequestPermitted = false),
        )
    }
}
