package com.example.myexodus.streaks

import android.content.Context
import com.example.myexodus.data.db.AppRulesStoreFactory

/**
 * §6/§8 Step 4 "milestone alarm firing", kept out of the receiver so it is testable without
 * Robolectric broadcast plumbing. The alarm firing is never proof the milestone is real:
 * the on-read evaluation re-derives elapsed time from the live clocks, the milestone is
 * re-read from durable state, and only then does the atomic claim decide whether this
 * invocation posts — overlapping firings of the same alarm post at most once.
 */
class MilestoneAlerter(
    private val context: Context,
    private val storeFactory: AppRulesStoreFactory,
    private val repository: HabitRepository,
    private val anomalyLog: StreakAnomalyLog,
    private val readings: () -> TimeReadings,
) {

    suspend fun handle(streakId: String, milestoneDay: Int) {
        val store = storeFactory.open() ?: return
        val streak = store.recoveryStreakDao().getById(streakId) ?: return
        if (streak.relapsedAt != null) return

        repository.evaluate(streak.habitId)

        val milestone = store.milestoneEventDao().get(streakId, milestoneDay) ?: return
        if (milestone.achievedAt == null) return
        // Fetched before the claim: a missing habit row skips like a denial, without consuming
        // the claim (FK cascade makes it impossible while the streak exists).
        val habit = store.habitProgressDao().getById(streak.habitId) ?: return
        // §6 step 4 / §9: denied permission skips silently WITHOUT consuming the claim, so the
        // §14 delivery counter keeps meaning "a notification was actually posted".
        if (!StreakNotifications.canPost(context)) return
        val nowWall = readings().tWallMillis
        if (store.milestoneEventDao().claimNotification(streakId, milestoneDay, nowWall) != 1) return

        anomalyLog.appendMilestonePosted(milestoneDay, nowWall)
        StreakNotifications.postMilestone(
            context = context,
            streakId = streakId,
            milestoneDay = milestoneDay,
            habitLabel = habit.targetIdentifier,
            habitId = streak.habitId,
        )
    }
}
