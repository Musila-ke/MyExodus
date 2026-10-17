package com.example.myexodus.streaks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * §8 Step 4 boot reconciliation (Decision 11): alarms do not survive reboot — unconditional
 * platform behavior, not an exact/inexact-alarm consequence — so every reboot re-derives the
 * pending milestone alarms from durable state. The same broadcast is an evaluation trigger
 * (Decision 9): after reconciling alarms, [MilestoneAlerter.catchUpAll] evaluates every
 * active habit and delivers any milestone whose alarm could not fire (e.g. the crossing fell
 * inside the device-off window). OEMs that delay or suppress this broadcast are covered by the
 * app-foreground reconciliation backstop in [HabitRepository] plus the ViewModel's foreground
 * sweep (the same [MilestoneAlerter.catchUpAll]).
 */
@AndroidEntryPoint
class MilestoneBootReceiver : BroadcastReceiver() {

    @Inject lateinit var scheduler: MilestoneScheduler

    @Inject lateinit var alerter: MilestoneAlerter

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeout(GO_ASYNC_TIMEOUT_MS) {
                    scheduler.reconcileAll()
                    alerter.catchUpAll()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Milestone boot reconciliation failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "MilestoneBootReceiver"
        private const val GO_ASYNC_TIMEOUT_MS = 8_000L
    }
}
