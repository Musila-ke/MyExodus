package com.example.myexodus.streaks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.myexodus.data.db.AppRulesStoreFactory
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Debug-only §12 harness for the milestone delivery legs, driven by `am broadcast` from the
 * host shell (which cannot reach non-exported receivers — same rule as
 * `DeviceVerificationReceiver`). `streak-read` dumps every active streak's milestone rows
 * (achieved/sent/acknowledged) without changing anything; `streak-catchup` runs the exact
 * boot-receiver call, `MilestoneAlerter.catchUpAfterBoot()`, so the task #14 catch-up path
 * is device-testable without a real reboot (BOOT_COMPLETED is a protected broadcast the
 * shell cannot send).
 */
@AndroidEntryPoint
class StreaksTestReceiver : BroadcastReceiver() {

    @Inject lateinit var alerter: MilestoneAlerter

    @Inject lateinit var storeFactory: AppRulesStoreFactory

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (action) {
                    ACTION_STREAK_READ -> readMilestones(context, "streak-read")
                    ACTION_STREAK_CATCHUP -> {
                        alerter.catchUpAfterBoot()
                        readMilestones(context, "streak-catchup")
                    }

                    else -> Log.w(TAG, "unknown action $action")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "$action failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun readMilestones(context: Context, label: String) {
        val store = storeFactory.open() ?: run {
            Log.e(TAG, "$label: store unavailable")
            return
        }
        val habitIds = store.recoveryStreakDao().activeHabitIds()
        Log.i(TAG, "$label: activeHabits=${habitIds.size}")
        habitIds.forEach { habitId ->
            val streak = store.recoveryStreakDao().getActiveStreak(habitId) ?: return@forEach
            Log.i(
                TAG,
                "$label: habit=${habitId.take(8)} streak=${streak.streakId.take(8)} " +
                    "elapsed=${streak.currentStreakElapsedMs} boot=${streak.bootCountLastObserved}",
            )
            store.milestoneEventDao().getForStreak(streak.streakId).forEach { milestone ->
                Log.i(
                    TAG,
                    "$label: day=${milestone.milestoneDay} achieved=${milestone.achievedAt ?: "-"} " +
                        "sent=${milestone.notificationSentAt ?: "-"} acked=${milestone.acknowledgedAt ?: "-"}",
                )
            }
        }
    }

    companion object {
        private const val TAG = "MyExodusVerification"
        const val ACTION_STREAK_READ = "streak-read"
        const val ACTION_STREAK_CATCHUP = "streak-catchup"
    }
}
