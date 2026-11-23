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
 * boot-receiver call, `MilestoneAlerter.catchUpAll()`, so the task #14 catch-up path
 * is device-testable without a real reboot (BOOT_COMPLETED is a protected broadcast the
 * shell cannot send). `streak-backdate` seeds a day-crossing the register-#17 way: it adds
 * elapsed via the production version-guarded anchor update and rewrites the shared-streak
 * hint to past-due — the seed touches only persisted state; the periodic pass and the
 * emission that follow are production.
 */
@AndroidEntryPoint
class StreaksTestReceiver : BroadcastReceiver() {

    @Inject lateinit var alerter: MilestoneAlerter

    @Inject lateinit var storeFactory: AppRulesStoreFactory

    @Inject lateinit var crossingCache: PartnerStreakCrossingCache

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (action) {
                    ACTION_STREAK_READ -> readMilestones(context, "streak-read")
                    ACTION_STREAK_CATCHUP -> {
                        alerter.catchUpAll()
                        readMilestones(context, "streak-catchup")
                    }

                    ACTION_STREAK_BACKDATE -> backdate(context, intent)

                    else -> Log.w(TAG, "unknown action $action")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "$action failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    /** Seed half of the register-#17 device leg: the version-guarded production anchor update
     *  adds [Intent]'s `--el backdateMs` to the accumulator, and an existing shared-streak
     *  hint is rewritten past-due. Everything after this (periodic pass, evaluation, journal,
     *  outbox) is the production path. */
    private suspend fun backdate(context: Context, intent: Intent) {
        val backdateMs = intent.getLongExtra(EXTRA_BACKDATE_MS, 0L)
        if (backdateMs <= 0L) {
            Log.e(TAG, "streak-backdate: missing --el backdateMs")
            return
        }
        val store = storeFactory.open() ?: run {
            Log.e(TAG, "streak-backdate: store unavailable")
            return
        }
        val habitIds = intent.getStringExtra(EXTRA_HABIT)?.let { listOf(it) }
            ?: store.recoveryStreakDao().activeHabitIds()
        val readings = SystemStreakClock.read(context)
        habitIds.forEach { habitId ->
            var applied = 0
            for (attempt in 0 until 3) {
                val streak = store.recoveryStreakDao().getActiveStreak(habitId) ?: break
                applied = store.recoveryStreakDao().compareAndUpdateAnchor(
                    streakId = streak.streakId,
                    tWallLastObserved = readings.tWallMillis,
                    rBootLastObserved = readings.rBootMillis,
                    bootCountLastObserved = readings.bootCount,
                    currentStreakElapsedMs = streak.currentStreakElapsedMs + backdateMs,
                    expectedRowVersion = streak.rowVersion,
                )
                if (applied == 1) break
            }
            crossingCache.entry(habitId)?.let {
                crossingCache.record(it.copy(nextCrossingAtMs = readings.tWallMillis - 1_000L))
            }
            Log.i(
                TAG,
                "streak-backdate: habit=${habitId.take(8)} backdatedMs=$backdateMs applied=$applied " +
                    "hintDueAt=${crossingCache.entry(habitId)?.nextCrossingAtMs ?: "-"}",
            )
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
        const val ACTION_STREAK_BACKDATE = "streak-backdate"
        const val EXTRA_BACKDATE_MS = "backdateMs"
        const val EXTRA_HABIT = "habit"
    }
}
