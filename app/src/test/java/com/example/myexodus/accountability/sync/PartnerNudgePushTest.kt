package com.example.myexodus.accountability.sync

import android.app.Application
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SOS §6.7/§8.17/§8.18 push-contract validation: UUIDv4-only nudge ids, and the launcher
 * intent's handled-extras rule (checked, consumed, never left to re-route a recreate).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PartnerNudgePushTest {

    private val nudgeId = "123e4567-e89b-42d3-a456-426614174000"

    @Test
    fun validNudgeId_acceptsCanonicalUuidV4() {
        assertEquals(nudgeId, PartnerNudgePush.validNudgeId(nudgeId))
    }

    @Test
    fun validNudgeId_rejectsEverythingElse() {
        assertNull("absent", PartnerNudgePush.validNudgeId(null))
        assertNull("blank", PartnerNudgePush.validNudgeId("  "))
        assertNull("not a uuid", PartnerNudgePush.validNudgeId("nudge-1"))
        assertNull("wrong shape", PartnerNudgePush.validNudgeId("123e4567e89b42d3a456426614174000"))
        // Version nibble must be 4 — a v1 UUID is syntactically valid but off-contract.
        assertNull("uuid v1", PartnerNudgePush.validNudgeId("123e4567-e89b-12d3-a456-426614174000"))
    }

    @Test
    fun consumeLauncherNudge_stripsBothExtras_andReturnsTheId() {
        val intent = launcherIntent().putExtra(PartnerNudgePush.NUDGE_ID_KEY, nudgeId)
        assertEquals(nudgeId, PartnerNudgePush.consumeLauncherNudge(intent))
        assertNull("type consumed", intent.getStringExtra(PartnerNudgePush.TYPE_KEY))
        assertNull("nudge_id consumed", intent.getStringExtra(PartnerNudgePush.NUDGE_ID_KEY))
    }

    @Test
    fun consumeLauncherNudge_invalidIdIsStrippedWithoutRouting() {
        val intent = launcherIntent().putExtra(PartnerNudgePush.NUDGE_ID_KEY, "not-a-uuid")
        assertNull(PartnerNudgePush.consumeLauncherNudge(intent))
        assertNull(intent.getStringExtra(PartnerNudgePush.TYPE_KEY))
        assertNull(intent.getStringExtra(PartnerNudgePush.NUDGE_ID_KEY))
    }

    @Test
    fun consumeLauncherNudge_missingIdIsStrippedWithoutRouting() {
        assertNull(PartnerNudgePush.consumeLauncherNudge(launcherIntent()))
    }

    /** The domain-event wake and every other intent must pass through untouched — only
     *  handled extras are removed (§8.18 step 4). */
    @Test
    fun consumeLauncherNudge_nonNudgeIntentIsUntouched() {
        val intent = Intent()
            .putExtra(PartnerNudgePush.TYPE_KEY, "domain-event")
            .putExtra("messageId", "m-1")
        assertNull(PartnerNudgePush.consumeLauncherNudge(intent))
        assertEquals("domain-event", intent.getStringExtra(PartnerNudgePush.TYPE_KEY))
        assertEquals("m-1", intent.getStringExtra("messageId"))
    }

    private fun launcherIntent(): Intent =
        Intent().putExtra(PartnerNudgePush.TYPE_KEY, PartnerNudgePush.TYPE_PARTNER_NUDGE)
}
