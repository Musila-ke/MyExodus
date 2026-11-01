package com.example.myexodus.ui.partner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Device regression 2026-10-31: tapping "EmuB shared a plan with you" opened the Partner hub.
 * The plan notification carries BOTH extras; the partner-selection effect reset `view` to the
 * hub after the plan effect had claimed the Plans surface. Whichever effect runs first, the
 * landing must be the plan.
 */
class PartnerDeepLinkViewTest {

    @Test
    fun pairDeepLink_returnsToHub_whenItNamesNoPlan() {
        assertNull(pairDeepLinkView(requestedPlanId = null))
    }

    @Test
    fun pairDeepLink_keepsThePlansSurface_whenTheSameTapNamedAPlan() {
        assertEquals("plans", pairDeepLinkView(requestedPlanId = "plan-7"))
    }
}
