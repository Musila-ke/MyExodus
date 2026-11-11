package com.example.myexodus.accountability.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Plan Step 9 (§8.12) payload contract, client side: the wake is a generic sync trigger, the
 * message id is optional, the legacy type still routes, an unknown schema version or malformed
 * id is ignored, and the silent liveness probe maps to a forced heartbeat (never a visible
 * notification).
 */
class PartnerPushParserTest {

    @Test
    fun genericWakeWithMessageId_routesThatMessageFirst() {
        assertEquals(
            PartnerPushWake.Sync(MESSAGE_ID),
            PartnerPushParser.parse(
                mapOf("v" to "1", "type" to "SYNC_AVAILABLE", "messageId" to MESSAGE_ID),
            ),
        )
    }

    @Test
    fun genericWakeWithoutMessageId_runsAFullSyncPass() {
        assertEquals(
            PartnerPushWake.Sync(null),
            PartnerPushParser.parse(mapOf("v" to "1", "type" to "SYNC_AVAILABLE")),
        )
        assertEquals(
            PartnerPushWake.Sync(null),
            PartnerPushParser.parse(
                mapOf("v" to "1", "type" to "SYNC_AVAILABLE", "messageId" to ""),
            ),
        )
    }

    @Test
    fun preVersioningPayload_stillRoutes() {
        assertEquals(
            PartnerPushWake.Sync(MESSAGE_ID),
            PartnerPushParser.parse(
                mapOf("type" to "domain-event", "messageId" to MESSAGE_ID),
            ),
        )
    }

    @Test
    fun unknownSchemaVersion_isIgnoredRatherThanGuessed() {
        assertEquals(
            PartnerPushWake.Ignored,
            PartnerPushParser.parse(
                mapOf("v" to "2", "type" to "SYNC_AVAILABLE", "messageId" to MESSAGE_ID),
            ),
        )
    }

    @Test
    fun legacyTypeWithoutAnId_isIgnored() {
        assertEquals(
            PartnerPushWake.Ignored,
            PartnerPushParser.parse(mapOf("type" to "domain-event")),
        )
        assertEquals(
            PartnerPushWake.Ignored,
            PartnerPushParser.parse(mapOf("type" to "domain-event", "messageId" to "")),
        )
    }

    @Test
    fun malformedMessageId_isIgnored_neverSanitized() {
        for (id in listOf("has space", "has/slash", "has.dot", "a".repeat(129), "?", "ümlaut")) {
            assertEquals(
                "id=$id",
                PartnerPushWake.Ignored,
                PartnerPushParser.parse(
                    mapOf("v" to "1", "type" to "SYNC_AVAILABLE", "messageId" to id),
                ),
            )
        }
    }

    @Test
    fun checkinReminder_hasNothingToPull() {
        // Legacy DMS probe carries no probeId; the signed-contact evaluator's probe does.
        assertEquals(
            PartnerPushWake.LivenessProbe(null),
            PartnerPushParser.parse(mapOf("v" to "1", "type" to "LIVENESS_PROBE")),
        )
        assertEquals(
            PartnerPushWake.LivenessProbe(PROBE_ID),
            PartnerPushParser.parse(
                mapOf("v" to "1", "type" to "LIVENESS_PROBE", "probeId" to PROBE_ID),
            ),
        )
    }

    @Test
    fun malformedProbeId_isIgnored_neverRepaired() {
        for (id in listOf("short", "has space", "has/slash", "a".repeat(161), "ümlaut")) {
            assertEquals(
                "id=$id",
                PartnerPushWake.Ignored,
                PartnerPushParser.parse(
                    mapOf("v" to "1", "type" to "LIVENESS_PROBE", "probeId" to id),
                ),
            )
        }
    }

    @Test
    fun partnerEventWake_runsASyncPassThatPullsTheMailbox() {
        // functions/partner-notify.js wake: data { v:'1', type:'PARTNER_EVENT', eventId }.
        // The event id is not a message id — the mailbox pull reconciles by receipt watermark.
        assertEquals(
            PartnerPushWake.Sync(null),
            PartnerPushParser.parse(
                mapOf("v" to "1", "type" to "PARTNER_EVENT", "eventId" to "evt-1"),
            ),
        )
    }

    @Test
    fun unknownOrAbsentType_isIgnored() {
        assertEquals(PartnerPushWake.Ignored, PartnerPushParser.parse(emptyMap()))
        assertEquals(PartnerPushWake.Ignored, PartnerPushParser.parse(mapOf("v" to "1")))
        assertEquals(
            PartnerPushWake.Ignored,
            PartnerPushParser.parse(mapOf("v" to "1", "type" to "SOMETHING_ELSE")),
        )
    }

    @Test
    fun theServerPayloadConstantsAgree() {
        // functions/dms-processor.js and functions/relay-logic.js build exactly these keys.
        assertEquals("SYNC_AVAILABLE", PartnerPushParser.TYPE_SYNC_AVAILABLE)
        assertEquals("LIVENESS_PROBE", PartnerPushParser.TYPE_LIVENESS_PROBE)
        assertEquals("messageId", PartnerPushParser.KEY_MESSAGE_ID)
    }

    private companion object {
        const val MESSAGE_ID = "3f0a2f0e-9a51-4f4d-8c3e-2c9b0a6d1e77"
        const val PROBE_ID = "probe-ep-3f0a2f0e9a514f4d8c3e2c9b0a6d1e77"
    }
}
