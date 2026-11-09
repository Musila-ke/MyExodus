package com.example.myexodus.accountability.activity

import com.example.myexodus.accountability.serialization.ActivityEvent
import com.example.myexodus.accountability.serialization.EventPayload
import com.example.myexodus.accountability.serialization.classifyActivity
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.entity.encodeMilestoneDays

/**
 * Receive-side projection (plan §8 Step 7/§8.10 delta, Appendix A.1): one appended activity
 * event updates the pair's `partner_activity_projection` wholesale from its `stateAfter`.
 *
 * The chain is the source of truth — a Malformed or future-schema body is already appended
 * (it is cryptographically valid); only the projection declines it (FR-17), which is why
 * dispatch goes through [classifyActivity] rather than the payload type alone.
 *
 * Wholesale application is the §8.10 idempotence argument: a duplicate delivery re-applies an
 * identical snapshot, and the DAO's monotonic guard drops anything at or below the last
 * applied chain sequence, so out-of-order or replayed states can never roll the card backwards.
 * The optional motivational message is *additionally* pinned; it is bound-checked here because
 * the composer-side bound is not a receiver-side guarantee (§10.6 bounded strings). A message
 * may also arrive with no snapshot at all (ADR #285) — then the pin is the whole effect, and
 * its send time comes from the payload's own key.
 */
class PartnerActivityReceiver(private val storeFactory: AppRulesStoreFactory) {

    /** Called from the mailbox pipeline's APPENDED hook — never throws is the caller's
     *  contract, so this returns quietly when the store is unavailable. */
    suspend fun applyAppended(pairId: String, payload: EventPayload, sequence: Long) {
        val valid = classifyActivity(payload) as? ActivityEvent.Valid ?: return
        val dao = storeFactory.open()?.partnerActivityDao() ?: return

        // An oversized or blank message is refused at the boundary while its snapshot still
        // applies — the message is the optional part, never the state. Same rule the emitter
        // applies, from one place: the composer's bound is not a receiver-side guarantee.
        val pinned = if (valid.payload.type == EventPayload.TYPE_MOTIVATIONAL_MESSAGE) {
            PartnerActivityJournal.normalizeMessage(valid.payload.text)
        } else {
            null
        }

        val state = valid.payload.stateAfter
        if (state == null) {
            // ADR #285: snapshot-less message — pin only, and nothing at all when the body
            // was refused, so a bad message can never create a phantom projection row.
            if (pinned != null) {
                dao.pinMessage(pairId, pinned, valid.payload.messageAtMs ?: System.currentTimeMillis())
            }
            return
        }

        dao.applyStateAfter(
            pairId = pairId,
            daysFree = state.daysFree,
            relapseCount = state.relapseCount,
            milestonesReached = encodeMilestoneDays(state.milestonesReached),
            coachingProgress = state.coachingProgress,
            occurredAtMs = state.eventOccurredAtMs,
            sequence = sequence,
            pinnedText = pinned,
            pinnedAtMs = pinned?.let { state.eventOccurredAtMs },
        )

        // Owner directive 2026-11-09: the per-streak view. A revoke or delete removes the
        // streak from the partner's view; every other habit-anchored kind applies its
        // snapshot wholesale under the row's own monotonic guard. Pre-v3 senders carry no
        // habit id and simply never populate this table (the aggregate above still applies).
        val habitId = state.habitId ?: return
        when (valid.payload.type) {
            EventPayload.TYPE_STREAK_SHARING_STOPPED, EventPayload.TYPE_STREAK_DELETED -> {
                dao.deleteStreakProjection(pairId, habitId)
                // The aggregate above took this row's stopped snapshot; re-read it from what
                // the partner still shares, or clear it when nothing is left.
                dao.refreshAggregateAfterStreakRemoved(pairId)
            }

            else -> dao.applyStreakState(
                pairId = pairId,
                habitId = habitId,
                label = state.habitLabel ?: "",
                daysFree = state.daysFree,
                relapseCount = state.relapseCount,
                milestonesReached = encodeMilestoneDays(state.milestonesReached),
                occurredAtMs = state.eventOccurredAtMs,
                sequence = sequence,
            )
        }
    }
}
