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
import com.example.myexodus.accountability.sync.PartnerRolloutGate
import com.example.myexodus.accountability.sync.PartnerSyncCoordinator
import com.example.myexodus.accountability.sync.PartnerSyncSignals
import com.example.myexodus.accountability.sync.PushInstallationRegistrar
import com.example.myexodus.accountability.sync.enqueuePartnerSync
import com.example.myexodus.accountability.sync.runPartnerSyncPass
import com.example.myexodus.blocking.BootReconciliationStore
import com.example.myexodus.blocking.EnforcementForegroundService
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
import com.example.myexodus.data.db.LocalDatabaseMigration
import com.example.myexodus.recovery.RecoverySessionRepository
import com.example.myexodus.telemetry.CrashReporting
import com.example.myexodus.telemetry.StartupTrace
import com.example.myexodus.widget.ProtectionSnapshotWriter
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.Executor
import java.util.concurrent.Executors
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
    // Owner mandate 2026-10-04 (realtime): the sender kick's three collaborators — a shared
    // transition must move off-device the moment it is journalled, not at the next 15-minute
    // tick (see the collector below). Lazy on purpose: the chain reaches the keystore-backed
    // signer, and app startup must not construct that before the first actual tick.
    @Inject lateinit var partnerSyncCoordinator: dagger.Lazy<PartnerSyncCoordinator>
    @Inject lateinit var pushInstallationRegistrar: dagger.Lazy<PushInstallationRegistrar>
    @Inject lateinit var partnerRolloutGate: dagger.Lazy<PartnerRolloutGate>
    // Widget snapshot writer (widget spec §7 Step 3). Field injection here is the construction
    // trigger, not decoration: nothing calls into the writer from the app — its collectors and
    // its heartbeat only run while this process is alive, and this line is what starts them.
    @Inject lateinit var protectionSnapshotWriter: ProtectionSnapshotWriter

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ADR #153: field init = construction time, so the first lap delta covers Hilt field
    // injection, not just onCreate.
    private val startupTrace = StartupTrace("App")

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
        get() = Configuration.Builder()
            .setWorkerFactory(hiltWorkerFactory)
            // B-10: without this, a throwable escaping any WM internal task (disk-full
            // ForceStopRunnable ISE, 2026-10-15) kills the process on its thread.
            .setTaskExecutor(guardedWorkManagerExecutor)
            .build()

    private val guardedWorkManagerExecutor: Executor = GuardedTaskExecutor(
        Executors.newFixedThreadPool(4) { runnable -> Thread(runnable, "myexodus-wm") },
    )

    override fun onCreate() {
        super.onCreate()
        startupTrace.lap("super")
        // §8.5: App Check provider in place before ANY Firestore op (mailbox pull, backfill
        // responder, drain) — app-startup install covers paths that never route through drainOutbox.
        AppCheckInstaller.install(this)
        startupTrace.lap("appcheck")
        // Plan §8 Step 16's sync_status channel: debug-only (never user-visible in release).
        PartnerNotifications.ensureSyncStatusChannel(this)
        // SOS §8.14: the partner_nudges_v3 channel exists from startup — the §8.12 pushEligible
        // snapshot reads its presence/importance, so it cannot wait for the first nudge.
        PartnerNotifications.ensurePartnerNudgesChannel(this)
        // Plan Step 6/Step 9: the accountability-events channel exists before the first event,
        // so the notification-health reading can tell "muted by the user" from "not created
        // yet". The old `partner_alerts` id is retired in the same call.
        PartnerNotifications.ensureAccountabilityEventsChannel(this)
        // Plan Step 8 (§8.12): the server's six-hour availability notice channel exists before
        // the worker's first dispatch — its id is the worker's `channelId`.
        PartnerNotifications.ensureAvailabilityAlertsChannel(this)
        // Owner ruling 2026-10-28: check-in is automatic; retire the old visible
        // "check-in reminder" channel (the server now sends a silent liveness probe).
        PartnerNotifications.deleteLegacyCheckinChannel(this)
        startupTrace.lap("channels")
        // Decision 12 opt-out: FirebaseInitProvider ran before this call, so this is what the
        // stored preference overrides from the first moment of each process.
        crashReporting.applyStoredPreference()
        startupTrace.lap("crashpref")
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
        // Owner mandate 2026-10-04 (realtime): PartnerActivityJournal emits one tick per shared
        // transition (relapse, milestone, streak, message); this is its only consumer. Ring
        // straight into an in-process pass so a relapse left the device seconds ago rather than
        // "hasn't even arrived". Ticks can only fire from post-unlock UI work, so this starts
        // unconditionally; a failed pass (offline) is dropped, and the periodic run heals it.
        applicationScope.launch {
            PartnerSyncSignals.activityEmitted.collect {
                runCatching {
                    runPartnerSyncPass(
                        pushInstallationRegistrar.get(),
                        partnerRolloutGate.get(),
                        partnerSyncCoordinator.get(),
                    )
                }.onFailure { Log.w(TAG, "activity kick pass failed", it) }
            }
        }
        // Eager open off the main thread; failure lands in a defined DatabaseHealth state, not a crash.
        // Open FIRST: Room 3 creates the schema lazily on first DAO access, and the migration's
        // INSERTs fail with "no such table" against an unprimed store (caught 2026-08-28 review).
        // Migration no-ops when already complete.
        if (!underInstrumentation) {
            // Plan §8.1: scoped database paths must be active before any store opens. Sync on
            // purpose — the open below and every component's own open read the scope; the first
            // launch after the update copies three files, later launches read one pref.
            runCatching { LocalDatabaseMigration.start(this) }
                .onFailure { Log.w(TAG, "local DB scope migration failed; legacy paths stay active", it) }
            applicationScope.launch {
                rulesStoreFactory.open()
                databaseMigration.runOnceIfNeeded()
                // W7 session recovery (v2 M2.4 adapted): urge sessions a process death left
                // STARTED past the 15-minute bound close as INTERRUPTED, once per launch.
                runCatching { recoverySessions.interruptStale(System.currentTimeMillis()) }
                    .onFailure { Log.w(TAG, "stale recovery-session sweep failed", it) }
            }
        }
        startupTrace.lap("launches+open")
        registerActivityLifecycleCallbacks(backgroundCheckpointer)
        // §8 Step 9: USER_UNLOCKED is delivered dynamically-only (never a manifest receiver).
        val unlockFilter = IntentFilter(Intent.ACTION_USER_UNLOCKED)
        registerSystemReceiver(userUnlockedReceiver, unlockFilter)
        // WorkManager cannot be touched pre-unlock (direct-boot components; its CE workdb is
        // unreachable — "WorkManager can't be accessed from direct boot"). The health-check
        // period is unique-name KEEP: any post-unlock process start enqueues it.
        if (getSystemService(UserManager::class.java).isUserUnlocked) {
            // §8 Step 6 cold-start trigger; post-unlock only — the status store is CE prefs.
            val authority = statusMonitor.refresh()
            startupTrace.lap("status-refresh")
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
            startupTrace.lap("wm")
            enqueuePartnerSync(this)
            enqueueDailyMaintenance(this)
            startupTrace.lap("wm-rest")
            // Owner report 2026-10-15 (ADR #152): XOS kills the FGS-hosting process ("prcp
            // FGS" cgroup kill, X6532 15:31:40) and ignores START_STICKY — the old re-arm
            // covered only app-open/toggle/boot/update, so enforcement stayed dead until the
            // app was next opened (6-min window live-observed, 18h35m largest in the ledger;
            // Instagram ran unblocked through today's window). Every process start now re-arms
            // the FGS, which turns the always-present 15-min WorkManager cadence (health
            // check; the 30-min widget backstop — both device-proven to wake a dead process)
            // into the keeper, and the FGS's exit-path repair resolves the ledger window via
            // the ADR #149 watermark. Not a watchdog: ADR #105's rejected levers stay
            // rejected; the premise this revises is its "sticky restart covers the kill case".
            if (!underInstrumentation) {
                EnforcementForegroundService.ensureOnProcessStart(this)
            }
            startupTrace.lap("fgs-ensure")
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
        startupTrace.lap("done")
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
            // A process started pre-unlock could not migrate (CE prefs and Keystore are
            // unreachable); run it now, before the store open that reconcile triggers.
            if (!underInstrumentation) LocalDatabaseMigration.start(context)
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
