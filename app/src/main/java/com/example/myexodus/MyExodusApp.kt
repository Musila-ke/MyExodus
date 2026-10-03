package com.example.myexodus

import android.app.Activity
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.UserManager
import android.util.Log
import android.view.accessibility.AccessibilityManager
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.myexodus.accountability.state.LocalWipeDetector
import com.example.myexodus.accountability.sync.AppCheckInstaller
import com.example.myexodus.accountability.sync.PartnerNotifications
import com.example.myexodus.accountability.sync.enqueuePartnerSync
import com.example.myexodus.blocking.BootReconciliationStore
import com.example.myexodus.blocking.EnhancedModeHealthMonitor
import com.example.myexodus.blocking.HealthCheckWorker
import com.example.myexodus.blocking.ProtectionStatusMonitor
import com.example.myexodus.blocking.ProtectionStatusStore
import com.example.myexodus.blocking.enqueueDailyMaintenance
import com.example.myexodus.blocking.systemBootCount
import com.example.myexodus.data.db.ActivityLogStore
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.DatabaseHealth
import com.example.myexodus.data.db.DatabaseHealthStore
import com.example.myexodus.data.db.DatabaseMigration
import com.example.myexodus.recovery.RecoverySessionRepository
import com.example.myexodus.telemetry.CrashReporting
import com.example.myexodus.widget.ProtectionSnapshotWriter
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class MyExodusApp : Application(), Configuration.Provider {

    @Inject lateinit var rulesStoreFactory: AppRulesStoreFactory
    @Inject lateinit var activityLogStore: ActivityLogStore
    @Inject lateinit var databaseMigration: DatabaseMigration
    @Inject lateinit var hiltWorkerFactory: HiltWorkerFactory
    @Inject lateinit var healthMonitor: EnhancedModeHealthMonitor
    @Inject lateinit var statusMonitor: ProtectionStatusMonitor
    @Inject lateinit var protectionStatusStore: ProtectionStatusStore
    @Inject lateinit var databaseHealthStore: DatabaseHealthStore
    @Inject lateinit var crashReporting: CrashReporting
    @Inject lateinit var bootReconciliation: BootReconciliationStore
    @Inject lateinit var localWipeDetector: LocalWipeDetector
    @Inject lateinit var recoverySessions: RecoverySessionRepository
    // Widget snapshot writer (widget spec §7 Step 3). Field injection here is the construction
    // trigger, not decoration: nothing calls into the writer from the app — its collectors and
    // its heartbeat only run while this process is alive, and this line is what starts them.
    @Inject lateinit var protectionSnapshotWriter: ProtectionSnapshotWriter

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var foregroundActivities = 0

    // True only when this process was started by `am instrument` (the test APK's classes are
    // loaded into the app process; production devices never load androidx.test). Instrumented
    // DB suites own the rules-store files and drive open/migration with their own factories —
    // this bootstrap's encrypted open (plaintext→encrypted conversion) and production
    // DatabaseMigration would convert or consume those files mid-test. X6532-observed
    // 2026-09-19: bootstrap open landed inside DatabaseMigrationInstrumentedTest's window
    // ("file unlinked while open" → SQLITE_IOERR_SHORT_READ on its raw read).
    private val underInstrumentation: Boolean by lazy {
        System.getProperty("robolectric") == null &&
            runCatching {
                Class.forName("androidx.test.platform.app.InstrumentationRegistry")
            }.isSuccess
    }

    private val accessibilityStateListener = AccessibilityManager.AccessibilityStateChangeListener {
        healthMonitor.refresh()
        // §8 Step 6: the connect/disconnect callback is an immediate authority trigger.
        statusMonitor.refresh()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(hiltWorkerFactory).build()

    override fun onCreate() {
        super.onCreate()
        // §8.5: App Check provider in place before ANY Firestore op (mailbox pull, backfill
        // responder, drain) — app-startup install covers paths that never route through drainOutbox.
        AppCheckInstaller.install(this)
        // Plan §8 Step 16's sync_status channel: debug-only (never user-visible in release).
        PartnerNotifications.ensureSyncStatusChannel(this)
        // SOS §8.14: the partner_nudges channel exists from startup — the §8.12 pushEligible
        // snapshot reads its presence/importance, so it cannot wait for the first nudge.
        PartnerNotifications.ensurePartnerNudgesChannel(this)
        // Decision 12 opt-out: FirebaseInitProvider ran before this call, so this is what the
        // stored preference overrides from the first moment of each process.
        crashReporting.applyStoredPreference()
        // §14: database/key failure classification as telemetry (§9's own taxonomy). One
        // observer instead of per-site emits — the health store is the single sink every DB
        // recovery path already writes, and it is in-memory, so each failing launch reports.
        applicationScope.launch {
            databaseHealthStore.health.collect { state ->
                if (state !is DatabaseHealth.Opening && state !is DatabaseHealth.Ready) {
                    crashReporting.nonFatal("db_health", cause = state::class.simpleName)
                }
            }
        }
        // Eager open off the main thread; failure lands in a defined DatabaseHealth state, not a crash.
        // Open FIRST: Room 3 creates the schema lazily on first DAO access, and the migration's
        // INSERTs fail with "no such table" against an unprimed store (caught 2026-08-28 review).
        // Migration no-ops when already complete.
        if (!underInstrumentation) {
            applicationScope.launch {
                rulesStoreFactory.open()
                databaseMigration.runOnceIfNeeded()
                // W7 session recovery (v2 M2.4 adapted): urge sessions a process death left
                // STARTED past the 15-minute bound close as INTERRUPTED, once per launch.
                runCatching { recoverySessions.interruptStale(System.currentTimeMillis()) }
                    .onFailure { Log.w(TAG, "stale recovery-session sweep failed", it) }
            }
        }
        registerActivityLifecycleCallbacks(backgroundCheckpointer)
        // §8 Step 9: USER_UNLOCKED is delivered dynamically-only (never a manifest receiver).
        val unlockFilter = IntentFilter(Intent.ACTION_USER_UNLOCKED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(userUnlockedReceiver, unlockFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            // RECEIVER_EXPORTED is a 33+ constant; its value (2) is ignored by the 26-32
            // registerReceiver (flags were reserved until 33) but keeps every path flagged.
            @Suppress("InlinedApi")
            registerReceiver(userUnlockedReceiver, unlockFilter, Context.RECEIVER_EXPORTED)
        }
        // WorkManager cannot be touched pre-unlock (direct-boot components; its CE workdb is
        // unreachable — "WorkManager can't be accessed from direct boot"). The health-check
        // period is unique-name KEEP: any post-unlock process start enqueues it.
        if (getSystemService(UserManager::class.java).isUserUnlocked) {
            // §8 Step 6 cold-start trigger; post-unlock only — the status store is CE prefs.
            val authority = statusMonitor.refresh()
            // §14 bullet 4: the install-base distribution signal (status × authority × OEM ×
            // API), one event per process start — transitions alone would only ever sample
            // the installs that changed.
            crashReporting.nonFatal(
                "protection_state",
                cause = protectionStatusStore.degradation?.name ?: "OK",
                "authority" to authority.name,
                "oem" to Build.MANUFACTURER,
                "api" to Build.VERSION.SDK_INT.toString(),
            )
            enqueueHealthCheck()
            enqueuePartnerSync(this)
            enqueueDailyMaintenance(this)
            // §8 Step 9: this boot's deferred reconciliation, if any (cold-start BOOT_COUNT
            // check + the locked-boot receiver's pending mark).
            reconcileBootIfNeeded()
            // §8 Step 17 (AD-22, NFR7): app-start wipe check — clears orphaned pair data and
            // reports the §14 trigger metric. Recovery is deliberately not attempted; the
            // Partner screen names SAS/QR re-enrollment as the only route forward.
            applicationScope.launch {
                if (localWipeDetector.detect()) crashReporting.nonFatal("local_wipe_detected")
            }
        }
        // Mid-session disablement (plan §8 Step 10): catch sooner than the next periodic tick.
        getSystemService(AccessibilityManager::class.java)
            .addAccessibilityStateChangeListener(accessibilityStateListener)
    }

    private fun enqueueHealthCheck() {
        val request = PeriodicWorkRequestBuilder<HealthCheckWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            HEALTH_CHECK_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /** §8 Step 9: post-unlock reconciliation — open the store, refresh protection status,
     *  re-enqueue the daily maintenance job, then claim the boot. The store's guard makes the
     *  second trigger for the same boot count a no-op (§12) and reports it (§14). Idempotent,
     *  so racing triggers do redundant-but-harmless work at worst. Post-unlock only: the
     *  reconciler block touches Room (CE) and WorkManager (CE workdb — verified 2.11.2). */
    private fun reconcileBootIfNeeded() {
        applicationScope.launch {
            bootReconciliation.reconcileOnce(systemBootCount(this@MyExodusApp)) {
                if (!underInstrumentation) rulesStoreFactory.open()
                statusMonitor.refresh()
                enqueueDailyMaintenance(this@MyExodusApp)
            }
        }
    }

    /** USER_UNLOCKED arrives only while this process is alive at unlock (e.g., started
     *  pre-unlock by the direct-boot receivers); the cold-start path covers the rest. */
    private val userUnlockedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_USER_UNLOCKED) return
            reconcileBootIfNeeded()
        }
    }

    private companion object {
        const val HEALTH_CHECK_WORK_NAME = "enhanced-mode-health-check"
        const val TAG = "MyExodusApp"
    }

    /** App backgrounded (last activity stopped): bounded WAL growth on both stores. */
    private val backgroundCheckpointer = object : ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            foregroundActivities++
        }

        override fun onActivityStopped(activity: Activity) {
            foregroundActivities--
            if (foregroundActivities == 0) {
                applicationScope.launch {
                    rulesStoreFactory.checkpoint()
                    activityLogStore.checkpoint()
                }
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
