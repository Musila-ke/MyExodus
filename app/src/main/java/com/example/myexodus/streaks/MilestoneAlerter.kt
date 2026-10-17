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
 * atomic claim was always designed for exactly-once across paths, so it now also backs
 * [catchUpAll], one sweep with two callers: [MilestoneBootReceiver] after boot reconciliation
 * (Decision 9's `BOOT_COMPLETED` evaluation trigger) and the streaks ViewModel on every
 * app-foreground refresh. Finding #22 (device-caught 2026-10-16): the first foreground version
 * delivered only the *displayed* habit, so an unfocused habit's missed crossing sat
 * achieved-and-unclaimed with no retry (its day-7 alarm left the queue unposted, and a 16-day
 * uptime meant the boot sweep never ran) — the sweep now covers every active habit, and its
 * per-habit evaluation also re-arms each habit's projected alarms. Whichever path runs first
 * posts; the rest return on the consumed claim.
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
     * §14 catch-up sweep: every active habit is evaluated (a crossing that happened while the
     * device was off — or while no alarm could fire — is stamped here), then any
     * achieved-but-unclaimed milestone is delivered. Runs right after the boot alarm
     * reconciliation and on every app-foreground refresh; the foreground run is what covers
     * OEM-suppressed alarms with no reboot, for every habit, focused or not (finding #22). An
     * unavailable store (pre-unlock) skips silently — the next trigger repeats the sweep.
     */
    suspend fun catchUpAll() {
        val store = storeFactory.open() ?: return
        store.recoveryStreakDao().activeHabitIds().forEach { habitId ->
            repository.evaluate(habitId)
            postUnclaimedFor(store, habitId)
        }
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
