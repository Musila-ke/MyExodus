package com.example.myexodus.blocking

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Daily maintenance job (§8 Step 8): on days no tracked app is opened this is what finally
 * clears stale `blockedToday`/warning state, plus a protection-status refresh and the §8.8
 * ledger retention sweep (§4.6) — the body lives in [DailyMaintenancePass], shared with the
 * service-side trigger that covers devices where WorkManager never dispatches (ADR #27).
 * Explicitly NOT a rollover-correctness mechanism (Decision 5) — every enforcement path rolls
 * its own row over; this only cleans state nothing else would touch on an idle day.
 */
@HiltWorker
class DailyMaintenanceWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val pass: DailyMaintenancePass,
) : CoroutineWorker(appContext, params) {
    /** Unconditional — the scheduled daily cadence is this trigger's own guard. */
    override suspend fun doWork(): Result {
        pass.run()
        return Result.success()
    }
}

/**
 * §8 Step 8 enqueue (also §9's post-unlock reboot re-enqueue): KEEP, so the very first
 * enqueue pins the schedule and later process starts leave it alone. The pin uses
 * [PeriodicWorkRequestBuilder.setNextScheduleTimeOverride] — a flex interval can only offset
 * the first run relative to enqueue time and cannot anchor it to a clock time (WorkManager
 * 2.11.2 sources).
 */
fun enqueueDailyMaintenance(context: Context) {
    val request = PeriodicWorkRequestBuilder<DailyMaintenanceWorker>(24, TimeUnit.HOURS)
        .setNextScheduleTimeOverride(nextDailyMaintenanceRunMs())
        .build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        DAILY_MAINTENANCE_WORK_NAME,
        ExistingPeriodicWorkPolicy.KEEP,
        request,
    )
}

/** §13 rollback: disabling Enhanced Mode leaves nothing of this feature registered. The
 *  next unlocked process start re-enqueues, so re-enabling needs no extra call. */
fun cancelDailyMaintenance(context: Context) {
    WorkManager.getInstance(context).cancelUniqueWork(DAILY_MAINTENANCE_WORK_NAME)
}

/** Next 00:05 local — today's if still ahead, otherwise tomorrow's. */
private fun nextDailyMaintenanceRunMs(): Long {
    val zone = ZoneId.systemDefault()
    val now = ZonedDateTime.now(zone)
    val next = now.toLocalDate().atTime(0, 5).atZone(zone)
        .let { if (it.isAfter(now)) it else it.plusDays(1) }
    return next.toInstant().toEpochMilli()
}

/** internal (not private): the instrumented execution-leg test re-enqueues this same work. */
internal const val DAILY_MAINTENANCE_WORK_NAME = "daily-maintenance"
