package com.example.myexodus.accountability.activity

import com.example.myexodus.accountability.serialization.EventPayload
import com.example.myexodus.accountability.sync.PartnerSyncSignals
import com.example.myexodus.data.db.AppRulesStore
import com.example.myexodus.data.db.entity.HabitProgress
import com.example.myexodus.data.db.entity.PartnerActivityOutbox
import com.example.myexodus.data.db.entity.encodeMilestoneDays
import com.example.myexodus.streaks.StreakTimer
import java.text.Normalizer
import java.util.UUID

/**
 * Journal-first emission of partner-visible activity (plan §7 Step 5, Appendix A.1/A.3).
 *
 * The event is created inside the same Room transaction that applies the local transition
 * (plan §8.2 "construct event body from committed resulting state"), so a rolled-back
 * transition can never leave a phantom event. The envelope is deliberately NOT built here
 * (Appendix A.1): signing is biometric-gated (900 s warm window) and each pair's chain
 * sequence is allocated at reconcile time, so `PartnerSyncCoordinator` builds envelope +
 * signature from the journal row and records delivery in `partner_activity_link`.
 *
 * Consent (v16/ADR #135, owner mandate 2026-10-04): the per-(pair, habit) matrix in
 * `pair_streak_sharing` / `pair_profile_sharing` decides who sees this row. This gate is
 * only the write-side bound — "could any pair ever receive this kind" — so the 30-day
 * journal never fills with events nobody consented to; the exact per-pair decision (flags
 * plus the enable-time watermarks) is enforced at reconcile, where the delivering pair is
 * known. Kinds: STREAK_UPDATED rides the streak master alone (sub-content is scrubbed per
 * pair at reconcile), MILESTONE_REACHED needs master + milestones, RELAPSE_RECORDED master +
 * relapses, and COACHING_STEP_UPDATED needs some pair's recovery consent (and a visible habit
 * to anchor its snapshot to). The Req 15 motivational message is unconditional (ADR #285):
 * its value is the text, so a pure supervisor who shares nothing still sends one — reconcile
 * ships it snapshot-less when the pair has no visible habit to anchor truthfully. Only the
 * coaching item *count* travels; the text never does (INV-13).
 *
 * Rows are written unconditionally beyond that gate — no cross-database pair check inside
 * the domain transaction. Fan-out is a transport concern: the reconcile loop emits only for
 * enrolled pairs, and the retention sweep prunes unlinked rows older than the mailbox TTL.
 */
object PartnerActivityJournal {

    /** Habit-anchored kinds (streak / milestone / relapse); [habitId] must exist. [endedDays]
     *  (relapse rows) is the ordinal day-count of the streak that just ended — the same number
     *  the sender's own relapse card showed; [milestoneDay] (milestone rows) is the day
     *  reached. The habit's own name rides along automatically (wire v2, ADR #134).
     *  [targetPairId] addresses the row to one pair (the enable-time snapshot); null fans out. */
    suspend fun emitForHabit(
        db: AppRulesStore,
        habitId: String,
        kind: String,
        occurredAtMs: Long,
        createdAtMs: Long = occurredAtMs,
        endedDays: Int? = null,
        milestoneDay: Int? = null,
        targetPairId: String? = null,
    ): Boolean {
        val habit = db.habitProgressDao().getById(habitId) ?: return false
        return write(
            db, habit.profileId, habit, kind, text = "", occurredAtMs, createdAtMs,
            targetPairId = targetPairId, endedDays = endedDays, milestoneDay = milestoneDay,
        )
    }

    /**
     * Profile-anchored kinds: coaching edits and the Req 15 composer's motivational message
     * ([text]). Resolves the profile's displayed habit as the row's anchor — reconcile
     * re-anchors per pair when that habit is not visible to the delivering pair. A profile
     * with no habit yet still emits a valid zero snapshot (habits-only kinds never take this
     * path).
     *
     * [targetPairId] (#96) addresses the row to one enrolled pair — the composer sets it to
     * the partner the user was looking at, because a message is written *to someone*. Null
     * keeps the fan-out every other kind has.
     */
    suspend fun emitForProfile(
        db: AppRulesStore,
        profileId: String,
        kind: String,
        occurredAtMs: Long,
        createdAtMs: Long = occurredAtMs,
        text: String = "",
        targetPairId: String? = null,
    ): Boolean {
        val habit = db.habitProgressDao().getSelected(profileId)
            ?: db.habitProgressDao().getFirstForProfile(profileId)
        // The Req 15 bound lives at this choke point, not only in the composer: every sender
        // routes here, and a body the partner's phone would refuse to pin must never be sent.
        val body = if (kind == EventPayload.TYPE_MOTIVATIONAL_MESSAGE) normalizeMessage(text) ?: return false else text
        return write(db, profileId, habit, kind, body, occurredAtMs, createdAtMs, targetPairId)
    }

    /**
     * §10.6 bounded strings, the Req 15 composer contract: NFC-normalized, trimmed, and refused
     * when empty or over [EventPayload.MAX_ACTIVITY_TEXT_LENGTH]. Null = nothing to send.
     */
    fun normalizeMessage(text: String): String? =
        Normalizer.normalize(text, Normalizer.Form.NFC).trim()
            .takeIf { it.isNotEmpty() && it.length <= EventPayload.MAX_ACTIVITY_TEXT_LENGTH }

    /** Caller must be inside the domain write transaction — see the class doc. */
    private suspend fun write(
        db: AppRulesStore,
        profileId: String,
        habit: HabitProgress?,
        kind: String,
        text: String,
        occurredAtMs: Long,
        createdAtMs: Long,
        targetPairId: String? = null,
        endedDays: Int? = null,
        milestoneDay: Int? = null,
    ): Boolean {
        if (!anyPairCouldReceive(db, kind, habit, targetPairId)) return false
        val streak = habit?.let { db.recoveryStreakDao().getActiveStreak(it.progressId) }
        val achieved = streak?.let { db.milestoneEventDao().getForStreak(it.streakId) }
            ?.filter { it.achievedAt != null }
            ?.map { it.milestoneDay }
            ?.sorted()
            .orEmpty()
        db.partnerActivityDao().insertJournal(
            PartnerActivityOutbox(
                eventId = UUID.randomUUID().toString(),
                kind = kind,
                targetPairId = targetPairId,
                // The consent key (v16): which habit's slice this snapshot belongs to.
                habitProgressId = habit?.progressId,
                text = text,
                occurredAtMs = occurredAtMs,
                // The same displayed day count the sender's own surfaces render — completed
                // days floored at 1, never 0 (ADR #128/#176). No streak at all still reports 0.
                daysFree = streak?.let {
                    maxOf(1, (it.currentStreakElapsedMs / StreakTimer.DAY_MS).toInt())
                } ?: 0,
                relapseCount = habit?.totalRelapses ?: 0,
                // Achieved days of the *current* streak — a relapse's fresh streak reports none.
                milestonesReached = encodeMilestoneDays(achieved),
                // Wire v2 (ADR #134): the partner sees *which* streak an event concerns.
                // Habit-anchored kinds carry the sender's own name for it, clamped to the
                // wire bound the receiver enforces; profile-anchored kinds stay nameless.
                habitLabel = habit?.takeIf { kind in EventPayload.HABIT_ANCHORED_ACTIVITY_TYPES }
                    ?.targetIdentifier?.take(EventPayload.MAX_HABIT_LABEL_LENGTH),
                endedDays = endedDays,
                milestoneDay = milestoneDay,
                coachingProgress = db.coachingStepDao().getForProfile(profileId).size,
                protocolVersion = EventPayload.ACTIVITY_PROTOCOL_VERSION,
                schemaVersion = EventPayload.ACTIVITY_SCHEMA_VERSION,
                // The retention clock is real write time, not a backdated transition time.
                createdAtMs = createdAtMs,
            ),
        )
        // Realtime kick (owner mandate 2026-10-04): without it the drain waits up to the full
        // 15-min cadence. One choke point covers every current and future kind; a pass that
        // starts before this transaction commits simply finds nothing — the cadence backstops.
        PartnerSyncSignals.activityEmitted.tryEmit(Unit)
        return true
    }

    /** The write-side bound (v16): could any enrolled-or-later pair receive this row? Exact
     *  per-pair consent + watermarks live in the reconcile filter; a false answer here is
     *  final (no pair could ever have consented — the matrix is the only consent there is),
     *  a true answer only means "journal it". */
    private suspend fun anyPairCouldReceive(
        db: AppRulesStore,
        kind: String,
        habit: HabitProgress?,
        targetPairId: String?,
    ): Boolean {
        val dao = db.pairSharingDao()
        return when (kind) {
            // Owner directive 2026-11-09: the add / rename / delete acts ride the streak
            // master alone, the same consent STREAK_UPDATED needs. A delete's rows are written
            // before the consent rows disappear (same transaction), so this gate still sees
            // the sharing that justified telling the pair.
            EventPayload.TYPE_STREAK_UPDATED,
            EventPayload.TYPE_STREAK_SHARED,
            EventPayload.TYPE_STREAK_RENAMED,
            EventPayload.TYPE_STREAK_DELETED,
            -> streakRows(dao, targetPairId)
                .any { it.habitProgressId == habit?.progressId && it.shareStreak }

            EventPayload.TYPE_MILESTONE_REACHED -> streakRows(dao, targetPairId)
                .any { it.habitProgressId == habit?.progressId && it.shareStreak && it.shareMilestones }

            EventPayload.TYPE_RELAPSE_RECORDED -> streakRows(dao, targetPairId)
                .any { it.habitProgressId == habit?.progressId && it.shareStreak && it.shareRelapses }

            // A visible habit is the delivery prerequisite: without one the row has no
            // truthful aggregate to carry (reconcile re-anchors profile rows).
            EventPayload.TYPE_COACHING_STEP_UPDATED -> dao.profileAll().any { it.shareRecovery } &&
                dao.streakAll().any { it.shareStreak }

            // ADR #285: messaging never depends on sharing. The addressee is a partner of
            // record; text is the whole payload and it carries no state anyone consented to.
            EventPayload.TYPE_MOTIVATIONAL_MESSAGE -> true

            // Owner ruling 2026-11-09: the revoke tells the partner a streak left their view,
            // so it must survive the very consent flip that stops every other streak row. A
            // (pair, habit) consent row still existing means there was sharing to revoke.
            EventPayload.TYPE_STREAK_SHARING_STOPPED -> streakRows(dao, targetPairId)
                .any { it.habitProgressId == habit?.progressId }

            // Owner ruling 2026-11-10: the check-ins revoke must land after share_checkins
            // was switched off — the consent row's existence is the only condition.
            EventPayload.TYPE_CHECKINS_SHARING_STOPPED,
            // Owner ruling 2026-11-12: same gate for the availability switch-off.
            EventPayload.TYPE_AVAILABILITY_SHARING_STOPPED ->
                if (targetPairId != null) dao.profileFor(targetPairId) != null
                else dao.profileAll().isNotEmpty()

            else -> true
        }
    }

    private suspend fun streakRows(
        dao: com.example.myexodus.data.db.dao.PairSharingDao,
        targetPairId: String?,
    ) = if (targetPairId != null) dao.streakForPair(targetPairId) else dao.streakAll()
}
