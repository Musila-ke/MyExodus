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

    /** Owner ask 2026-11-02: a tap must land on the subpage the notification concerns, not
     *  one tap short on the hub. */
    @Test
    fun pairDeepLink_opensTheSubpage_theNotificationNamed() {
        assertEquals(VIEW_ACTIVITY, pairDeepLinkView(requestedPlanId = null, requestedViewId = VIEW_ACTIVITY))
        assertEquals(VIEW_RULES, pairDeepLinkView(requestedPlanId = null, requestedViewId = VIEW_RULES))
        assertEquals(VIEW_REQUESTS, pairDeepLinkView(requestedPlanId = null, requestedViewId = VIEW_REQUESTS))
    }

    /** A plan outranks the subpage extra: both ride the plan notification's tap. */
    @Test
    fun pairDeepLink_planWinsOverTheSubpageExtra() {
        assertEquals(
            "plans",
            pairDeepLinkView(requestedPlanId = "plan-7", requestedViewId = VIEW_ACTIVITY),
        )
    }
}
