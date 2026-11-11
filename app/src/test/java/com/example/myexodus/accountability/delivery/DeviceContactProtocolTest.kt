package com.example.myexodus.accountability.delivery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §6.3 cross-language contract: the golden canonical strings below were produced by the
 * server's own `canonicalJson(contactMessage(validateContact(data)))`, so any drift in field
 * names, nesting, ordering, or the optional probeId breaks this test before a device can sign
 * bytes the server would reject.
 */
class DeviceContactProtocolTest {

    private val health = EnforcementHealthSnapshot(
        accessibilityEnabled = true,
        vpnActive = false,
        deviceAdminActive = false,
        notificationPermissionGranted = true,
        requiredCapabilities = listOf(
            DeviceContactProtocol.CAPABILITY_ACCESSIBILITY,
            DeviceContactProtocol.CAPABILITY_VPN,
        ),
    )

    private fun contact(probeId: String? = null) = DeviceContactV1(
        ownerUid = "uid-1",
        installationId = INSTALLATION_ID,
        keyId = KEY_ID,
        heartbeatCounter = 7,
        deviceReportedAtMs = 1_700_000_000_000,
        health = health,
        probeId = probeId,
    )

    @Test
    fun `canonical bytes match the server's JCS output`() {
        assertEquals(
            """{"deviceReportedAtMs":1700000000000,"enforcement":{"accessibilityEnabled":true,"deviceAdminActive":false,"notificationPermissionGranted":true,"requiredCapabilities":["ACCESSIBILITY","VPN"],"vpnActive":false},"heartbeatCounter":7,"installationId":"$INSTALLATION_ID","keyId":"$KEY_ID","ownerUid":"uid-1","v":1}""",
            DeviceContactProtocol.canonicalBytes(contact()).toString(Charsets.UTF_8),
        )
    }

    @Test
    fun `probeId is signed only when present`() {
        assertEquals(
            """{"deviceReportedAtMs":1700000000000,"enforcement":{"accessibilityEnabled":true,"deviceAdminActive":false,"notificationPermissionGranted":true,"requiredCapabilities":["ACCESSIBILITY","VPN"],"vpnActive":false},"heartbeatCounter":7,"installationId":"$INSTALLATION_ID","keyId":"$KEY_ID","ownerUid":"uid-1","probeId":"probe-ep-7","v":1}""",
            DeviceContactProtocol.canonicalBytes(contact(probeId = "probe-ep-7"))
                .toString(Charsets.UTF_8),
        )
        // An absent probeId and the server's optional-field handling agree: no key at all.
        assertFalse(
            DeviceContactProtocol.canonicalBytes(contact())
                .toString(Charsets.UTF_8)
                .contains("\"probeId\""),
        )
    }

    @Test
    fun `submit payload is the flat contact plus the base64url signature`() {
        val signature = ByteArray(64) { it.toByte() }
        val payload = DeviceContactProtocol.submitPayload(contact(probeId = "probe-ep-7"), signature)
        assertEquals(DeviceContactProtocol.jcsValue(contact(probeId = "probe-ep-7")) + ("contactSignature" to PartnerEventProtocol.base64Url(signature)), payload)
        assertEquals(9, payload.size)
        assertEquals(PartnerEventProtocol.base64Url(signature), payload["contactSignature"])
        assertTrue(
            "the server's SIGNATURE regex needs 64-128 base64url chars",
            (payload["contactSignature"] as String).length in 64..128,
        )
    }

    @Test
    fun `reply parsing maps the three outcomes and rejects everything else`() {
        assertEquals(
            DeviceContactProtocol.Reply.Accepted,
            DeviceContactProtocol.parseReply(mapOf("outcome" to "ACCEPTED", "outboxCreated" to true)),
        )
        assertEquals(
            DeviceContactProtocol.Reply.Stale(11L),
            DeviceContactProtocol.parseReply(
                mapOf("outcome" to "STALE", "serverCounter" to 11),
            ),
        )
        assertEquals(
            DeviceContactProtocol.Reply.InvalidProbe,
            DeviceContactProtocol.parseReply(mapOf("outcome" to "INVALID_PROBE")),
        )
        assertNull(DeviceContactProtocol.parseReply(mapOf("outcome" to "STALE")))
        assertNull(DeviceContactProtocol.parseReply(mapOf("outcome" to "SOMETHING_NEW")))
        assertNull(DeviceContactProtocol.parseReply(null))
        assertNull(DeviceContactProtocol.parseReply("not a map"))
    }

    @Test
    fun `counter bound matches the server's JCS-safe ceiling`() {
        assertEquals(9_007_199_254_740_991L, DeviceContactProtocol.MAX_COUNTER)
    }

    @Test
    fun `probe id regex mirrors the server's`() {
        // From functions/device-liveness-logic.js: /^[A-Za-z0-9_-]{8,160}$/.
        assertTrue(DeviceContactProtocol.PROBE_ID.matches("probe-ep-7"))
        assertTrue(DeviceContactProtocol.PROBE_ID.matches("a".repeat(160)))
        assertFalse(DeviceContactProtocol.PROBE_ID.matches("short"))
        assertFalse(DeviceContactProtocol.PROBE_ID.matches("a".repeat(161)))
        assertFalse(DeviceContactProtocol.PROBE_ID.matches("has space"))
        assertFalse(DeviceContactProtocol.PROBE_ID.matches("has/slash"))
    }

    private companion object {
        const val INSTALLATION_ID = "3f0a2f0e-9a51-4f4d-8c3e-2c9b0a6d1e77"
        const val KEY_ID = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    }
}
