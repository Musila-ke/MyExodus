package com.example.myexodus.blocking

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.myexodus.blocking.usage.WarningState
import com.example.myexodus.data.db.entity.DailyUsageTracker
import com.example.myexodus.data.db.entity.UserProfile
import dagger.hilt.android.EntryPointAccessors
import java.time.LocalDate
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * §8 Step 8 execution leg. WM dispatch cannot be depended on to fire this job on schedule —
 * XOS specifically (ADR #27 as amended 2026-10-01: dispatch observed, not a guarantee, and no
 * OEM build guarantees it) — so the maintenance job's real execution is verified here: the
 * periodic worker dispatched by the production WorkManager, and its stale-row rollover
 * (UsageAccountingEngine.rolloverStaleDays). The production pin is next 00:05
 * (dumpsys-verified separately); a production
 * image cannot have its clock moved, so the test makes the work due now with a
 * one-second-past [PeriodicWorkRequestBuilder.setNextScheduleTimeOverride] — AFTER
 * cancelling the existing spec: WM 2.11 honours a past-time override only while the work
 * has never run (periodCount == 0), clamping it to lastEnqueue + interval otherwise, and
 * UPDATE on a CANCELLED spec deletes and re-enqueues it fresh. The production pin and the
 * test row are restored in a finally, so a failed run never leaves the device on the test
 * spec (finding #24, 2026-10-16 — the restore used to be a happy-path tail).
 */
@RunWith(AndroidJUnit4::class)
class DailyMaintenanceWorkerInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val entryPoint = EntryPointAccessors.fromApplication(
        context, EnhancedModeTestEntryPoint::class.java,
    )

    /** Same identity as the device harness's profile so at most one test profile exists. */
    private val fallbackProfile = UserProfile(
        userId = "device-test-profile",
        name = "Device Test",
        email = "devicetest@example.com",
        phoneNumber = null,
        dateOfBirth = 0L,
        hasAccessibilityPermission = false,
        hasUsageStatsPermission = false,
        hasVpnPermission = false,
        hasNotificationPermission = false,
        hasDeviceAdminPermission = false,
        devicePublicKey = null,
        recoveryEscrowKey = null,
        isActive = true,
    )

    @Test
    fun maintenanceRun_rollsStaleTrackerRow() = runBlocking {
        val pkg = "com.example.myexodus.maintenance-test"
        val store = entryPoint.appRulesStoreFactory().open() ?: error("store unavailable")
        val profile = store.userProfileDao().observeActive().first() ?: fallbackProfile.also {
            store.userProfileDao().insert(it)
        }
        val trackerDao = store.dailyUsageTrackerDao()
        trackerDao.findByPackage(profile.userId, pkg)?.let { trackerDao.delete(it) }
        trackerDao.insert(
            DailyUsageTracker(
                trackerId = UUID.randomUUID().toString(),
                profileId = profile.userId, packageName = pkg,
                localDateAnchor = LocalDate.now().minusDays(2).toString(),
                timezoneIdAtAnchor = TimeZone.getDefault().id,
                msUsedToday = 123_456L, warning1State = WarningState.DELIVERED,
                warning2State = WarningState.PENDING_DELIVERY, blockedToday = true,
            ),
        )

        // UPDATE, not the production KEEP: this test's spec must replace the device's pin.
        // Cancel the stale spec first — a past-time override only fires for never-run work.
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(DAILY_MAINTENANCE_WORK_NAME)
        awaitCancelled(workManager)
        val dueNow = PeriodicWorkRequestBuilder<DailyMaintenanceWorker>(24, TimeUnit.HOURS)
            .setNextScheduleTimeOverride(System.currentTimeMillis() - 1_000L)
            .build()
        workManager.enqueueUniquePeriodicWork(
            DAILY_MAINTENANCE_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, dueNow,
        )

        try {
            // Real dispatch: WorkManager → scheduler → the @HiltWorker doWork() body.
            val today = LocalDate.now().toString()
            val deadline = System.currentTimeMillis() + 30_000L
            var rolled: DailyUsageTracker? = null
            while (System.currentTimeMillis() < deadline) {
                val row = trackerDao.findByPackage(profile.userId, pkg)
                if (row?.localDateAnchor == today) { rolled = row; break }
                delay(500)
            }
            val row = rolled ?: error("maintenance job did not roll the stale row within 30s")

            assertEquals(0L, row.msUsedToday)
            assertEquals(WarningState.NOT_DUE, row.warning1State)
            assertEquals(WarningState.NOT_DUE, row.warning2State)
            assertFalse(row.blockedToday)
        } finally {
            // Restore the production state even on failure: drop the test spec, re-pin next
            // 00:05, and remove the test row (looked up — `rolled` may be null on timeout).
            workManager.cancelUniqueWork(DAILY_MAINTENANCE_WORK_NAME)
            awaitCancelled(workManager)
            trackerDao.findByPackage(profile.userId, pkg)?.let { trackerDao.delete(it) }
            enqueueDailyMaintenance(context)
        }
    }

    private suspend fun awaitCancelled(workManager: WorkManager) {
        val deadline = System.currentTimeMillis() + 10_000L
        while (System.currentTimeMillis() < deadline) {
            val remaining = workManager.getWorkInfosForUniqueWork(DAILY_MAINTENANCE_WORK_NAME).get()
                .any { it.state != WorkInfo.State.CANCELLED }
            if (!remaining) break
            delay(200)
        }
    }
}
