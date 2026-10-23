package com.example.myexodus.accountability.sync

import android.content.Intent
import java.util.UUID

/**
 * Partner SOS (V1 Req 35) push contract — plan §4.6/§6.7/§8.17/§8.18. The FCM data payload
 * carries `type` + `nudge_id` + (since D16, 2026-10-23) `pair_id`; the pair id is a local
 * lookup key, never content: the service resolves this phone's pairing label and names the
 * sender, falling back to the generic copy. The Android notification tag is the fixed FR-19
 * tag (one replaceable tray entry — the active pair is per-user, so a second nudge replaces
 * the first by design). The card itself is always rendered from Firestore (extras are hints).
 */
object PartnerNudgePush {

    const val TYPE_KEY = "type"
    const val NUDGE_ID_KEY = "nudge_id"
    const val PAIR_ID_KEY = "pair_id"
    const val TYPE_PARTNER_NUDGE = "partner_nudge"

    /** FR-19 fixed Android tag — the post and the post-acknowledgment cancel (FR-16) share it. */
    const val TAG = "partner_nudge"

    /** D16: the pair id is `<uidA>_<uidB>` (AD-02). Strict shape and length so a malformed
     *  or hostile push can only ever degrade to the generic copy — never reach a lookup with
     *  an arbitrary string. */
    private val PAIR_ID_SHAPE = Regex("[A-Za-z0-9-]{1,128}_[A-Za-z0-9-]{1,128}")

    fun validPairId(raw: String?): String? = raw?.takeIf { PAIR_ID_SHAPE.matches(it) }

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
