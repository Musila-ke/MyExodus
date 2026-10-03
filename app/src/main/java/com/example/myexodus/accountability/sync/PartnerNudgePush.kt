package com.example.myexodus.accountability.sync

import android.content.Intent
import java.util.UUID

/**
 * Partner SOS (V1 Req 35) push contract — plan §4.6/§6.7/§8.17/§8.18. The FCM data payload
 * and the launcher-intent extras it produces carry exactly `type` + `nudge_id`; the Android
 * notification tag is the fixed FR-19 tag (one replaceable tray entry — the active pair is
 * per-user, so a second nudge replaces the first by design). Nothing here is content: the
 * card is always rendered from Firestore, never from push (AD: extras are hints only).
 */
object PartnerNudgePush {

    const val TYPE_KEY = "type"
    const val NUDGE_ID_KEY = "nudge_id"
    const val TYPE_PARTNER_NUDGE = "partner_nudge"

    /** FR-19 fixed Android tag — the post and the post-acknowledgment cancel (FR-16) share it. */
    const val TAG = "partner_nudge"

    /**
     * §6.7 step 1 / §8.18 step 2: present and UUIDv4-syntax only. Anything else is ignored —
     * never posted, never routed (stdlib parses the canonical 8-4-4-4-12 shape; the version
     * nibble must be 4).
     */
    fun validNudgeId(raw: String?): String? =
        raw?.takeIf { id -> runCatching { UUID.fromString(id) }.getOrNull()?.version() == 4 }

    /**
     * §8.18: consumes a launcher intent that may carry the nudge push contract — returns the
     * validated nudge id, and strips both extras (the handled-extras rule, so a later
     * recreate/restore never re-routes). Null when the intent is not a nudge push, or carries
     * a malformed id (extras still stripped — they are hints, not content).
     */
    fun consumeLauncherNudge(intent: Intent): String? {
        if (intent.getStringExtra(TYPE_KEY) != TYPE_PARTNER_NUDGE) return null
        val nudgeId = validNudgeId(intent.getStringExtra(NUDGE_ID_KEY))
        intent.removeExtra(TYPE_KEY)
        intent.removeExtra(NUDGE_ID_KEY)
        return nudgeId
    }
}
