package com.example.myexodus.backup

import androidx.room3.withWriteTransaction
import com.example.myexodus.account.GuestSnapshot
import com.example.myexodus.data.db.AppRulesStore
import com.example.myexodus.data.db.entity.MilestoneEvent
import com.example.myexodus.data.db.entity.RiskWindow

/**
 * §6.6 item 7: validates the authenticated snapshot's entity relationships before anything is
 * written. Key and foreign-key coherence is what says the payload is self-consistent; no SQL,
 * table or path ever comes from it (every statement is a typed DAO call).
 */
internal fun GuestSnapshot.validateRestorePayload() {
    fun invalid(): Nothing =
        throw BackupUploadException(BackupUploadFailure.PAYLOAD_CORRUPT)

    val habitIds = habits.map { it.progressId }
    val streakIds = streaks.map { it.streakId }
    if (habitIds.toSet().size != habitIds.size) invalid()
    if (streakIds.toSet().size != streakIds.size) invalid()
    if (coaching.map { it.stepId }.toSet().size != coaching.size) invalid()
    if (riskWindows.size > RiskWindow.MAX_WINDOWS) invalid()
    if (riskWindows.map { it.id }.toSet().size != riskWindows.size) invalid()
    if (streaks.any { it.habitId !in habitIds }) invalid()
    if (milestones.any {
            it.streakId !in streakIds || it.milestoneDay !in MilestoneEvent.MILESTONE_DAYS
        }
    ) {
        invalid()
    }
}

/**
 * §6.6 item 7: replaces the logical tables of the staging database with the snapshot. Unlike
 * the guest import this is not a merge — the rows land exactly as serialized, with the owning
 * profile remapped to the one this device will have active after the swap. Per-(pair, habit)
 * consent is the one adjacent table that survives: pairing never travels in a backup, so the
 * rows for habits that come back keep sharing with the pairs they already had.
 */
internal suspend fun AppRulesStore.writeRestoredSnapshot(
    snapshot: GuestSnapshot,
    profileId: String,
) {
    val habits = snapshot.habits.map { it.copy(profileId = profileId) }
    val habitIds = habits.map { it.progressId }.toSet()
    withWriteTransaction {
        // Saved before the wipe: pair_streak_sharing cascades with its habit row.
        val consent = pairSharingDao().streakAll().filter { it.habitProgressId in habitIds }
        milestoneEventDao().deleteAll()
        recoveryStreakDao().deleteAll()
        attemptLogDao().deleteAllLinks()
        attemptLogDao().deleteAll()
        recoverySessionDao().deleteAllLinks()
        recoverySessionDao().deleteAll()
        coachingStepDao().deleteAll()
        riskWindowDao().deleteAll()
        habitProgressDao().deleteAll()

        snapshot.riskWindows.forEach { riskWindowDao().insert(it) }
        snapshot.coaching.forEach { coachingStepDao().insert(it.copy(profileId = profileId)) }
        habits.forEach { habitProgressDao().insert(it) }
        consent.forEach { pairSharingDao().upsertStreak(it) }
        // Streaks precede milestones: the milestone rows carry a foreign key to them.
        snapshot.streaks.forEach { recoveryStreakDao().insert(it) }
        if (snapshot.milestones.isNotEmpty()) milestoneEventDao().insertAll(snapshot.milestones)
        snapshot.attempts.forEach { attemptLogDao().insert(it) }
        snapshot.sessions.forEach { recoverySessionDao().insert(it) }
        snapshot.policy?.let { recoveryPolicyDao().put(it) }
    }
}
