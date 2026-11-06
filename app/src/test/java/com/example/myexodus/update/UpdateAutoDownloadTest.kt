package com.example.myexodus.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan revision 2026-11-06: automatic transfer requires consent, an offer, and unmetered Wi-Fi. */
class UpdateAutoDownloadTest {

    private val wifi = NetworkState(connected = true, metered = false, roaming = false)

    @Test
    fun downloadsOnlyWithConsentAndAnOfferOnUnmeteredNonRoamingNetwork() {
        assertTrue(shouldAutoDownload(true, DisplayStatus.AVAILABLE, wifi))
    }

    @Test
    fun consentDefaultsOffByBeingRequired() {
        assertFalse(shouldAutoDownload(false, DisplayStatus.AVAILABLE, wifi))
    }

    @Test
    fun everyOtherDisplayStateDoesNotDownload() {
        val others = listOf(
            null,
            DisplayStatus.UP_TO_DATE,
            DisplayStatus.HALTED,
            DisplayStatus.NOT_IN_ROLLOUT,
            DisplayStatus.CHECK_STALE,
            DisplayStatus.POLICY_ROLLBACK,
        )
        others.forEach { assertFalse("$it", shouldAutoDownload(true, it, wifi)) }
    }

    @Test
    fun disconnectedMeteredOrRoamingNetworksDoNotDownload() {
        assertFalse(shouldAutoDownload(true, DisplayStatus.AVAILABLE, wifi.copy(connected = false)))
        assertFalse(shouldAutoDownload(true, DisplayStatus.AVAILABLE, wifi.copy(metered = true)))
        assertFalse(shouldAutoDownload(true, DisplayStatus.AVAILABLE, wifi.copy(roaming = true)))
    }
}
