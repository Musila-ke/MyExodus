package com.example.myexodus.streaks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

/**
 * §8 Step 4: milestone alarm firing. Thin shell — the receiver must call `goAsync()` and
 * finish its `PendingResult` unconditionally, so all real work lives in [MilestoneAlerter]
 * (Hilt-injectable and testable without Robolectric receiver plumbing).
 *
 * The internal timeout bounds how long we hold the broadcast; `finish()` in `finally` means
 * a slow DB open or a hung evaluation still releases the broadcast slot.
 */
@AndroidEntryPoint
class MilestoneAlarmReceiver : BroadcastReceiver() {

    @Inject lateinit var alerter: MilestoneAlerter

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != MilestoneScheduler.ACTION_MILESTONE_ALARM) return
        val streakId = intent.getStringExtra(EXTRA_STREAK_ID) ?: return
        val milestoneDay = intent.getIntExtra(EXTRA_MILESTONE_DAY, -1)
        if (milestoneDay <= 0) return

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeout(GO_ASYNC_TIMEOUT_MS) {
                    alerter.handle(streakId, milestoneDay)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Milestone alarm handling failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "MilestoneAlarmReceiver"
        private const val GO_ASYNC_TIMEOUT_MS = 8_000L

        const val EXTRA_STREAK_ID = "com.example.myexodus.streaks.EXTRA_STREAK_ID"
        const val EXTRA_MILESTONE_DAY = "com.example.myexodus.streaks.EXTRA_MILESTONE_DAY"
    }
}
