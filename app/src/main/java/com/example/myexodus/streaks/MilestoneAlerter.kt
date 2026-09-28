package com.example.myexodus.streaks

import android.content.Context
import com.example.myexodus.data.db.AppRulesStore
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.entity.RecoveryStreak
import com.example.myexodus.notifications.NotificationAccess

/**
 * §6/§8 Step 4 "milestone alarm firing", kept out of the receiver so it is testable without
 * Robolectric broadcast plumbing. The alarm firing is never proof the milestone is real:
 * the on-read evaluation re-derives elapsed time from the live clocks, the milestone is
 * re-read from durable state, and only then does the atomic claim decide whether this
 * invocation posts — overlapping firings of the same alarm post at most once.
 *
 * §14 catch-up delivery (device-caught 2026-09-27): an alarm cannot fire when its crossing
 * lands in an off/reboot window (alarms never survive a reboot) or when an OEM suppresses the
 * broadcast, and until now the alarm was the *only* caller that posted — so a missed firing
 * meant the milestone was never announced at all (the owner's Day 7 crossed during a
 * three-minute reboot window; the row sat achieved-and-unclaimed with no notification). The
 * atomic claim was always designed for exactly-once across paths, so it now also backs two
 * catch-up callers: [catchUpAfterBoot] (Decision 9's `BOOT_COMPLETED` evaluation trigger,
 * invoked by [MilestoneBootReceiver]) and [deliverUnclaimed] (the app-foreground evaluation
 * backstop, invoked after the streaks ViewModel's refresh). Whichever path runs first posts;
 * the rest return on the consumed claim.
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
        claimAndPost(store, streak, milestoneDay)
    }

    /**
     * §14 catch-up at boot: every active habit is evaluated (a crossing that happened while the
     * device was off is stamped here), then any achieved-but-unclaimed milestone is delivered.
     * Runs right after the boot alarm reconciliation; an unavailable store (pre-unlock) skips
     * silently — the foreground backstop repeats this.
     */
    suspend fun catchUpAfterBoot() {
        val store = storeFactory.open() ?: return
        store.recoveryStreakDao().activeHabitIds().forEach { habitId ->
            repository.evaluate(habitId)
            postUnclaimedFor(store, habitId)
        }
    }

    /**
     * §14 catch-up on the foreground evaluation backstop: the caller has just evaluated
     * [habitId]; anything achieved but never claimed is delivered now. Covers alarms an OEM
     * suppressed or dropped with no reboot — the one case the boot catch-up cannot see.
     */
    suspend fun deliverUnclaimed(habitId: String) {
        val store = storeFactory.open() ?: return
        postUnclaimedFor(store, habitId)
    }

    private suspend fun postUnclaimedFor(store: AppRulesStore, habitId: String) {
        val streak = store.recoveryStreakDao().getActiveStreak(habitId) ?: return
        store.milestoneEventDao().getForStreak(streak.streakId)
            .filter { it.achievedAt != null && it.notificationSentAt == null }
            .forEach { claimAndPost(store, streak, it.milestoneDay) }
    }

    private suspend fun claimAndPost(store: AppRulesStore, streak: RecoveryStreak, milestoneDay: Int) {
        // Fetched before the claim: a missing habit row skips like a denial, without consuming
        // the claim (FK cascade makes it impossible while the streak exists).
        val habit = store.habitProgressDao().getById(streak.habitId) ?: return
        // §6 step 4 / §9: denied permission skips silently WITHOUT consuming the claim, so the
        // §14 delivery counter keeps meaning "a notification was actually posted".
        if (!NotificationAccess.canPost(context)) return
        val nowWall = readings().tWallMillis
        if (store.milestoneEventDao()
                .claimNotification(streak.streakId, milestoneDay, nowWall) != 1
        ) {
            return
        }

        anomalyLog.appendMilestonePosted(milestoneDay, nowWall)
        StreakNotifications.postMilestone(
            context = context,
            streakId = streak.streakId,
            milestoneDay = milestoneDay,
            habitLabel = habit.targetIdentifier,
            habitId = streak.habitId,
        )
    }
}
