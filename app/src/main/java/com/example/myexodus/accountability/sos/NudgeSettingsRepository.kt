package com.example.myexodus.accountability.sos

import com.example.myexodus.accountability.enrollment.EnrollmentRecord
import com.example.myexodus.accountability.enrollment.targetEnrollment
import com.example.myexodus.accountability.sync.displayNameOf

/**
 * SOS plan Step 11 (§8.19): the recipient's block preference — one owner-owned document at
 * `pairings/{pairId}/nudgeSettings/{uid}` (rules R-F6 pin its schema, ownership, and active-pair
 * gate). Loading resolves the shared §19.5 target pair — the same pair the inbox listens on — so
 * the switch always configures the partner the rest of the recipient UI speaks for; a missing
 * document is the unblocked default, never an error.
 *
 * Enabling the block writes exactly one document and touches nothing else: §8.19 forbids
 * retroactive deletion, so already-delivered tray notifications and an open Home card survive.
 * Server-side, the block suppresses only nudges that have not yet left for FCM (accept-time
 * §6.2 step 19; dispatch-time re-read after lease — T-F11), and the sender is never told
 * (FR-10: the same `accepted` result either way).
 */
class NudgeSettingsRepository(
    private val enrollments: suspend () -> List<EnrollmentRecord>,
    private val selectedPairId: () -> String?,
    private val ensureUid: suspend () -> String?,
    private val readBlocked: suspend (pairId: String, uid: String) -> Boolean,
    private val writeBlocked: suspend (pairId: String, uid: String, blocked: Boolean) -> Unit,
) {

    /** The switch's whole input: which partner it configures and the current state. */
    data class BlockSettings(
        val pairId: String,
        val partnerName: String,
        val blocked: Boolean,
    )

    /** Null when there is nothing to configure (no live enrollment or no uid) or the read
     *  failed; the row hides and the next refresh (tab entry / foreground) retries. */
    suspend fun load(): BlockSettings? {
        val uid = runCatching { ensureUid() }.getOrNull() ?: return null
        val enrollment = runCatching { enrollments().targetEnrollment(selectedPairId()) }
            .getOrNull() ?: return null
        val blocked = runCatching { readBlocked(enrollment.pairId, uid) }.getOrNull() ?: return null
        return BlockSettings(enrollment.pairId, displayNameOf(enrollment), blocked)
    }

    /** True when the write committed; false means server state is unchanged and the UI reverts. */
    suspend fun setBlocked(settings: BlockSettings, blocked: Boolean): Boolean {
        val uid = runCatching { ensureUid() }.getOrNull() ?: return false
        return runCatching { writeBlocked(settings.pairId, uid, blocked) }.isSuccess
    }
}
