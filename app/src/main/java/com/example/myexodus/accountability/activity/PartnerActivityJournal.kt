package com.example.myexodus.accountability.activity

import com.example.myexodus.accountability.serialization.EventPayload
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
 * Consent (Appendix A.3) uses the shipped habit flags: `shareMilestones` gates STREAK_UPDATED
 * and MILESTONE_REACHED, `shareRelapses` gates RELAPSE_RECORDED. Coaching items and the Req 15
 * motivational message are user-initiated shares — ungated. Only the coaching item *count*
 * travels; the text never does (plan §8.12 privacy delta, INV-13).
 *
 * Rows are written unconditionally — no cross-database pair check inside the domain
 * transaction. Fan-out is a transport concern: the reconcile loop emits only for enrolled
 * pairs, and the retention sweep prunes unlinked rows older than the mailbox TTL.
 */
object PartnerActivityJournal {

    /** Habit-anchored kinds (streak / milestone / relapse); [habitId] must exist. */
    suspend fun emitForHabit(
        db: AppRulesStore,
        habitId: String,
        kind: String,
        occurredAtMs: Long,
        createdAtMs: Long = occurredAtMs,
    ): Boolean {
        val habit = db.habitProgressDao().getById(habitId) ?: return false
        return write(db, habit.profileId, habit, kind, text = "", occurredAtMs, createdAtMs)
    }

    /**
     * Profile-anchored kinds: coaching edits and the Req 15 composer's motivational message
     * ([text]). Resolves the profile's displayed habit for the snapshot — a profile with no
     * habit yet still emits a valid zero snapshot (habits-only kinds never take this path).
     */
    suspend fun emitForProfile(
        db: AppRulesStore,
        profileId: String,
        kind: String,
        occurredAtMs: Long,
        createdAtMs: Long = occurredAtMs,
        text: String = "",
    ): Boolean {
        val habit = db.habitProgressDao().getSelected(profileId)
            ?: db.habitProgressDao().getFirstForProfile(profileId)
        // The Req 15 bound lives at this choke point, not only in the composer: every sender
        // routes here, and a body the partner's phone would refuse to pin must never be sent.
        val body = if (kind == EventPayload.TYPE_MOTIVATIONAL_MESSAGE) normalizeMessage(text) ?: return false else text
        return write(db, profileId, habit, kind, body, occurredAtMs, createdAtMs)
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
    ): Boolean {
        if (!isShared(kind, habit)) return false
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
                text = text,
                occurredAtMs = occurredAtMs,
                daysFree = ((streak?.currentStreakElapsedMs ?: 0L) / StreakTimer.DAY_MS).toInt(),
                relapseCount = habit?.totalRelapses ?: 0,
                // Achieved days of the *current* streak — a relapse's fresh streak reports none.
                milestonesReached = encodeMilestoneDays(achieved),
                coachingProgress = db.coachingStepDao().getForProfile(profileId).size,
                protocolVersion = EventPayload.ACTIVITY_PROTOCOL_VERSION,
                schemaVersion = EventPayload.ACTIVITY_SCHEMA_VERSION,
                // The retention clock is real write time, not a backdated transition time.
                createdAtMs = createdAtMs,
            ),
        )
        return true
    }

    /** Appendix A.3 consent matrix; an unresolvable habit cannot have consented. */
    private fun isShared(kind: String, habit: HabitProgress?): Boolean = when (kind) {
        EventPayload.TYPE_STREAK_UPDATED, EventPayload.TYPE_MILESTONE_REACHED ->
            habit?.shareMilestones == true

        EventPayload.TYPE_RELAPSE_RECORDED -> habit?.shareRelapses == true

        else -> true
    }
}
