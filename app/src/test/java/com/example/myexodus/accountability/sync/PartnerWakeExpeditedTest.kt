package com.example.myexodus.accountability.sync

import androidx.work.NetworkType
import com.example.myexodus.accountability.delivery.partnerEventSyncNowRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner ruling 2026-11-09: a partner alert must land within seconds of being sent. The wake
 * and the post-mutation pass are the two device-side hops that used to wait for a scheduler
 * slot (Deferred outright in Doze), so their expedited flag is part of the delivery contract,
 * not a tuning detail — pin it.
 */
class PartnerWakeExpeditedTest {

    @Test
    fun `fcm wake is expedited and carries the wake message id`() {
        val request = partnerWakeRequest("message-1")

        assertTrue(request.workSpec.expedited)
        assertEquals("message-1", request.workSpec.input.getString(PARTNER_WAKE_MESSAGE_ID))
    }

    @Test
    fun `liveness probe is expedited and forces the heartbeat`() {
        val request = partnerProbeRequest()

        assertTrue(request.workSpec.expedited)
        assertTrue(request.workSpec.input.getBoolean(PARTNER_FORCE_HEARTBEAT, false))
    }

    @Test
    fun `post-mutation pass is expedited and keeps its network constraint`() {
        val request = partnerSyncNowRequest()

        assertTrue(request.workSpec.expedited)
        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
    }

    @Test
    fun `event capture kick is expedited and keeps its network constraint`() {
        val request = partnerEventSyncNowRequest()

        assertTrue(request.workSpec.expedited)
        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
    }
}
