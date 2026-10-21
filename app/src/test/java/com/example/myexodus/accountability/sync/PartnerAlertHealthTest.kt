package com.example.myexodus.accountability.sync

import com.example.myexodus.accountability.sync.PartnerAlertHealth.State
import org.junit.Assert.assertEquals
import org.junit.Test

/** Plan Step 6 notification health: the one actionable reason a partner alert cannot show,
 *  in precedence order (local switch before device capability). */
class PartnerAlertHealthTest {

    @Test
    fun everythingWorking_isOk() {
        assertEquals(State.OK, PartnerAlertHealth.state(canPost = true, alertsChannelEnabled = true, providerSupported = true))
    }

    @Test
    fun permissionDenied_outranksEveryOtherCause() {
        assertEquals(
            State.NOTIFICATIONS_OFF,
            PartnerAlertHealth.state(canPost = false, alertsChannelEnabled = false, providerSupported = false),
        )
    }

    @Test
    fun mutedChannel_outranksProviderSupport() {
        assertEquals(
            State.CHANNEL_OFF,
            PartnerAlertHealth.state(canPost = true, alertsChannelEnabled = false, providerSupported = false),
        )
    }

    @Test
    fun missingProvider_isUnsupportedOnlyWhenNotificationsCanOtherwisePost() {
        assertEquals(
            State.PROVIDER_UNSUPPORTED,
            PartnerAlertHealth.state(canPost = true, alertsChannelEnabled = true, providerSupported = false),
        )
    }
}
