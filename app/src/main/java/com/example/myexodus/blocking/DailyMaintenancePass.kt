package com.example.myexodus.blocking

import android.os.SystemClock
import android.util.Log
import com.example.myexodus.blocking.usage.LedgerMaintenanceState
import com.example.myexodus.blocking.usage.MaintenanceOutcome
import com.example.myexodus.blocking.usage.UsageAccountingEngine
import com.example.myexodus.blocking.usage.UsageLedgerRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §8 Step 8's maintenance body — stale-day rollover, protection-status refresh, the §8.8
 * ledger retention sweep (§4.6) — shared by the two things that can trigger it: the scheduled
 * [DailyMaintenanceWorker], and the enforcement service's cycle on devices where WorkManager
 * never dispatches (XOS, ADR #27). One body and one cool-down, so the triggers cannot diverge
 * in what a maintenance run means.
 */
@Singleton
class DailyMaintenancePass @Inject constructor(
    private val engine: UsageAccountingEngine,
    private val statusMonitor: ProtectionStatusMonitor,
    private val ledger: UsageLedgerRepository,
    private val maintenanceState: LedgerMaintenanceState,
) {
    /** Unconditional run — the scheduled job's own cadence is the guard there. */
    suspend fun run(nowWallClockMs: Long = System.currentTimeMillis()) {
        engine.rolloverStaleDays()
        statusMonitor.refresh()
        val outcome = maintenanceState.beginRun(nowWallClockMs, SystemClock.elapsedRealtime())
        when (outcome) {
            MaintenanceOutcome.SUSPENDED ->
                Log.w(TAG, "ledger pruning suspended: §8.8 clock-anomaly cool-down")
            MaintenanceOutcome.PRUNE -> ledger.prune(nowWallClockMs)
        }
    }

    /**
     * The frequent trigger's form: a no-op unless [LedgerMaintenanceState.MIN_INTERVAL_MS] has
     * passed since the last run, so a 30 s service cycle performs one pass a day rather than a
     * pass thirty times an hour. A suspended run still counts as a run (its clock frame is
     * recorded), so the cool-down also paces the trigger.
     */
    suspend fun runIfDue(nowWallClockMs: Long = System.currentTimeMillis()): Boolean {
        if (!maintenanceState.maintenanceDue(nowWallClockMs)) return false
        run(nowWallClockMs)
        return true
    }

    private companion object {
        const val TAG = "MyExodusLedger"
    }
}
