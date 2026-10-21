package com.example.myexodus.streaks

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.AlarmManagerCompat
import androidx.core.net.toUri
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.entity.MilestoneEvent
import com.example.myexodus.data.db.entity.RecoveryStreak

/**
 * §8 Step 4 reconciliation: the single function that computes "what alarms should exist
 * right now" from durable state and makes the OS match it — it cancels alarms that shouldn't
 * exist and schedules ones that should, assuming nothing about prior calls. Runs after
 * onboarding, after relapse, after every evaluation (the foreground backstop), and on
 * `BOOT_COMPLETED` ([reconcileAll]).
 *
 * Trigger time is projected from the accumulator, not the raw start date:
 * `t_wall_last_observed + (milestone day − current_streak_elapsed_ms)`. A backdated
 * `recovery_start_at` therefore cannot fire an alarm immediately — the accumulator starts
 * at 0 regardless (see [HabitRepository]).
 *
 * A milestone whose projected time has already passed but is not yet achieved is left
 * untouched: if its alarm is still pending it will fire and the receiver re-verifies from
 * durable state; if the device was off (alarms do not survive reboot) the next evaluation
 * stamps it and the splash card carries it — rescheduling it here would only risk an
 * immediate-fire loop.
 */
class MilestoneScheduler(
    private val context: Context,
    private val storeFactory: AppRulesStoreFactory,
    private val readings: () -> TimeReadings,
) {

    /** Every habit with a live streak — the boot-reconciliation work list (§8 Step 4). */
    suspend fun reconcileAll() {
        val store = storeFactory.open() ?: return
        store.recoveryStreakDao().activeHabitIds().forEach { reconcile(it) }
    }

    /**
     * Cancels a habit's pending milestone alarms. Deletion cannot rely on [reconcile]: it
     * derives its work list from durable rows, and by the time the call would run those rows
     * are gone, leaving orphaned PendingIntents behind. Called before the delete transaction
     * so the streak ids the alarms are keyed by are still readable.
     */
    suspend fun cancelFor(habitId: String) {
        val store = storeFactory.open() ?: return
        store.recoveryStreakDao().getByHabit(habitId).forEach { streak ->
            MilestoneEvent.MILESTONE_DAYS.forEach { day ->
                alarmManager().cancel(pendingIntentFor(streak.streakId, day))
            }
        }
    }

    suspend fun reconcile(habitId: String) {
        val store = storeFactory.open() ?: return
        val nowWall = readings().tWallMillis
        // Every streak of the habit, so a rollover's stale alarms are cancelled by the same
        // pass that schedules the new streak's (no reliance on the rollover path succeeding).
        store.recoveryStreakDao().getByHabit(habitId).forEach { streak ->
            val active = streak.relapsedAt == null
            store.milestoneEventDao().getForStreak(streak.streakId).forEach { milestone ->
                val triggerAtWall =
                    if (active && milestone.achievedAt == null) triggerAtWallOf(streak, milestone) else null
                when {
                    triggerAtWall != null && triggerAtWall > nowWall -> {
                        AlarmManagerCompat.setAndAllowWhileIdle(
                            alarmManager(),
                            AlarmManager.RTC_WAKEUP,
                            triggerAtWall,
                            pendingIntentFor(streak.streakId, milestone.milestoneDay),
                        )
                    }

                    triggerAtWall != null -> Unit // passed but unachieved: see class KDoc

                    else -> alarmManager().cancel(pendingIntentFor(streak.streakId, milestone.milestoneDay))
                }
            }
        }
    }

    private fun triggerAtWallOf(streak: RecoveryStreak, milestone: MilestoneEvent): Long =
        streak.tWallLastObserved +
            (milestone.milestoneDay.toLong() * StreakTimer.DAY_MS - streak.currentStreakElapsedMs)

    private fun alarmManager(): AlarmManager = context.getSystemService(AlarmManager::class.java)

    private fun pendingIntentFor(streakId: String, milestoneDay: Int): PendingIntent {
        val intent = Intent(context, MilestoneAlarmReceiver::class.java)
            .setAction(ACTION_MILESTONE_ALARM)
            // The data URI carries the real identity; the request code is a stable hash only.
            .setData("myexodus://milestone/$streakId/$milestoneDay".toUri())
            .putExtra(MilestoneAlarmReceiver.EXTRA_STREAK_ID, streakId)
            .putExtra(MilestoneAlarmReceiver.EXTRA_MILESTONE_DAY, milestoneDay)
        return PendingIntent.getBroadcast(
            context,
            requestCodeFor(streakId, milestoneDay),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** String.hashCode is specified, so the code is stable across processes (§8 Step 4). */
    private fun requestCodeFor(streakId: String, milestoneDay: Int): Int =
        (streakId.hashCode() * 31 + milestoneDay) and 0x7FFFFFFF

    companion object {
        const val ACTION_MILESTONE_ALARM = "com.example.myexodus.streaks.MILESTONE_ALARM"
    }
}
