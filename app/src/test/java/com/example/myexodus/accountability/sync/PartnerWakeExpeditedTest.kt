package com.example.myexodus.accountability.sync

import androidx.work.NetworkType
import com.example.myexodus.accountability.delivery.DEVICE_CONTACT_PROBE_ID
import com.example.myexodus.accountability.delivery.deviceContactProbeRequest
import com.example.myexodus.accountability.delivery.partnerEventSyncNowRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner ruling 2026-11-09: a partner alert must land within seconds of being sent. The wake
 * and the post-mutation pass are the two device-side hops that used to wait for a scheduler
 * slot (Deferred outright in Doze), so their expedited flag is part of the delivery contract,
 * not a tuning detail. Below API 31 expedited work runs as a foreground service and fails
 * without getForegroundInfo, so the same builders must produce an ordinary request there.
 */
class PartnerWakeExpeditedTest {

    @Test
    fun `fcm wake is expedited and carries the wake message id`() {
        val request = partnerWakeRequest("message-1", expedited = true)

        assertTrue(request.workSpec.expedited)
        assertEquals("message-1", request.workSpec.input.getString(PARTNER_WAKE_MESSAGE_ID))
    }

    @Test
    fun `fcm wake below android 12 is an ordinary request`() {
        val request = partnerWakeRequest("message-1", expedited = false)

        assertFalse(request.workSpec.expedited)
        assertEquals("message-1", request.workSpec.input.getString(PARTNER_WAKE_MESSAGE_ID))
    }

    @Test
    fun `liveness probe is expedited and forces the heartbeat`() {
        val request = partnerProbeRequest(expedited = true)

        assertTrue(request.workSpec.expedited)
        assertTrue(request.workSpec.input.getBoolean(PARTNER_FORCE_HEARTBEAT, false))
    }

    @Test
    fun `liveness probe below android 12 is an ordinary request`() {
        val request = partnerProbeRequest(expedited = false)

        assertFalse(request.workSpec.expedited)
        assertTrue(request.workSpec.input.getBoolean(PARTNER_FORCE_HEARTBEAT, false))
    }

    @Test
    fun `post-mutation pass is expedited and keeps its network constraint`() {
        val request = partnerSyncNowRequest(expedited = true)

        assertTrue(request.workSpec.expedited)
        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
    }

    @Test
    fun `post-mutation pass below android 12 is an ordinary request`() {
        val request = partnerSyncNowRequest(expedited = false)

        assertFalse(request.workSpec.expedited)
        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
    }

    @Test
    fun `event capture kick is expedited and keeps its network constraint`() {
        val request = partnerEventSyncNowRequest(expedited = true)

        assertTrue(request.workSpec.expedited)
        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
    }

    @Test
    fun `event capture kick below android 12 is an ordinary request`() {
        val request = partnerEventSyncNowRequest(expedited = false)

        assertFalse(request.workSpec.expedited)
        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
    }

    @Test
    fun `device contact probe follows the same expedited split`() {
        val expedited = deviceContactProbeRequest("probe-1", expedited = true)
        val ordinary = deviceContactProbeRequest("probe-1", expedited = false)

        assertTrue(expedited.workSpec.expedited)
        assertFalse(ordinary.workSpec.expedited)
        assertEquals("probe-1", expedited.workSpec.input.getString(DEVICE_CONTACT_PROBE_ID))
    }
}
