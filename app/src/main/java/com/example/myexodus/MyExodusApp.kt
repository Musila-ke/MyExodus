package com.example.myexodus

import android.app.Activity
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.UserManager
import android.util.Log
import android.view.accessibility.AccessibilityManager
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.myexodus.account.DeletionLocalData
import com.example.myexodus.account.GuestImportCoordinator
import com.example.myexodus.account.GuestTransitionCoordinator
import com.example.myexodus.account.LegacyCachePurge
import com.example.myexodus.account.LocalSessionManager
import com.example.myexodus.account.ScopeRecoveryImporter
import com.example.myexodus.accountability.delivery.PartnerDeliveryCoordinator
import com.example.myexodus.accountability.delivery.enqueueDeviceContact
import com.example.myexodus.accountability.delivery.enqueueDeviceContactNow
import com.example.myexodus.accountability.delivery.enqueuePartnerEventSync
import com.example.myexodus.accountability.state.LocalWipeDetector
import com.example.myexodus.accountability.sync.AppCheckInstaller
import com.example.myexodus.accountability.sync.PartnerNotifications
import com.example.myexodus.accountability.sync.PartnerRolloutGate
import com.example.myexodus.accountability.sync.PartnerSyncCoordinator
import com.example.myexodus.accountability.sync.PartnerSyncSignals
import com.example.myexodus.accountability.sync.PushInstallationRegistrar
import com.example.myexodus.accountability.sync.enqueuePartnerSync
import com.example.myexodus.accountability.sync.runPartnerSyncPass
import com.example.myexodus.applock.AppLockCoordinator
import com.example.myexodus.applock.AppLockState
import com.example.myexodus.applock.VaultDekSource
import com.example.myexodus.applock.VaultMigration
import com.example.myexodus.backup.BackupStagingSweeper
import com.example.myexodus.blocking.AttemptRecorder
import com.example.myexodus.blocking.BootReconciliationStore
import com.example.myexodus.blocking.EnforcementForegroundService
import com.example.myexodus.blocking.EnhancedModeHealthMonitor
import com.example.myexodus.blocking.HealthCheckWorker
import com.example.myexodus.blocking.ProtectionStatusMonitor
import com.example.myexodus.blocking.ProtectionStatusStore
import com.example.myexodus.blocking.enqueueDailyMaintenance
import com.example.myexodus.update.UpdateCheckWorker
import com.example.myexodus.update.UpdateCoordinator
import com.example.myexodus.blocking.systemBootCount
import com.example.myexodus.data.db.ActivityLogStore
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.DatabaseHealth
import com.example.myexodus.data.db.DatabaseHealthStore
import com.example.myexodus.data.db.DatabaseMigration
import com.example.myexodus.data.db.LocalDatabaseMigration
import com.example.myexodus.domainusage.AndroidDomainUsageHost
import com.example.myexodus.recovery.RecoverySessionRepository
import com.example.myexodus.streaks.MilestoneScheduler
import com.example.myexodus.streaks.StreakNotifications
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
    // Account plan §6.2: a prepared guest transition finalizes as soon as the target session
    // exists, even if the Account pane was closed or the process restarted mid-flow.
    // Lazy: this graph reaches FirebaseAuth and CE preferences, which throw before first
    // unlock — eager construction killed the LOCKED_BOOT_COMPLETED process (§8.9 Direct Boot).
    @Inject lateinit var guestTransitionCoordinator: dagger.Lazy<GuestTransitionCoordinator>
    // Account plan §8.4: the post-switch import previews itself as soon as a bound quarantine
    // exists, so the import is never silently skipped after a restart.
    @Inject lateinit var guestImportCoordinator: dagger.Lazy<GuestImportCoordinator>
    // ADR #240: a bound retained scope is staged at start and imported without a tap.
    @Inject lateinit var scopeRecoveryImporter: dagger.Lazy<ScopeRecoveryImporter>
    // Account plan §7 step 10 / §12.4: the one session fence every remote operation consults.
    @Inject lateinit var localSessionManager: dagger.Lazy<LocalSessionManager>
    // Account plan §6.4 item 14: stale plaintext/compressed staging never survives a start.
    // Lazy: its upload-session store builds the Tink secrets keyset from CE prefs at
    // construction — unreadable in a locked-boot process (§8.9 Direct Boot).
    @Inject lateinit var backupStagingSweeper: dagger.Lazy<BackupStagingSweeper>
    // Account plan §6.8/§6.9.3: a deletion accepted before the last restart erases local data
    // before any store of that identity opens.
    @Inject lateinit var deletionLocalData: DeletionLocalData
    // Plan §8.3/§8.6: the updater's durable record has one main-process owner; this startup
    // touch reconciles it after process death and after package replacement.
    // Lazy: its state/diagnostics stores open CE prefs at construction (§8.9 Direct Boot).
    @Inject lateinit var updateCoordinator: dagger.Lazy<UpdateCoordinator>
    // §8.8: process-scope screen-off revocation must not depend on MainActivity being alive.
    // Lazy because a locked-boot process cannot read the CE feature status this constructs.
    @Inject lateinit var appLockCoordinator: dagger.Lazy<AppLockCoordinator>
    // §8.3: the session DEKs the coordinator publishes on unlock and this app zeroes on lock.
    @Inject lateinit var vaultDekSource: VaultDekSource
    // §8.12: startup journal reconciliation runs before the gate's first frame.
    @Inject lateinit var vaultMigration: VaultMigration
    // §8.9: while the vault was locked, blocked attempts wait in a bounded outbox and boot
    // reconciliation cannot re-arm milestone alarms — both land on the first unlocked start.
    // Lazy: the outbox's prefs are CE and throw when constructed pre-unlock (§8.9).
    @Inject lateinit var attemptRecorder: dagger.Lazy<AttemptRecorder>
    @Inject lateinit var milestoneScheduler: MilestoneScheduler
    // Partner delivery plan §4.1/§6.1 step 4: foreground drains and startup reconciliation.
    // Lazy: the chain reaches FirebaseAuth and the accountability stores (§8.9 Direct Boot).
    @Inject lateinit var partnerDelivery: dagger.Lazy<PartnerDeliveryCoordinator>

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

    /** §8.8: screen-off revokes the session even when a background service keeps the process alive. */
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_SCREEN_OFF) return
            // Pre-unlock there is no UI session and CE feature status is unreadable; a new
            // process always starts locked anyway, so a missed broadcast is harmless.
            if (!context.getSystemService(UserManager::class.java).isUserUnlocked) return
            appLockCoordinator.get().onScreenOff()
        }
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
        // Plan §7 step 20: the one-time legacy disk-cache purge must precede the first
        // Firestore operation, i.e. before super.onCreate() starts the rest of bootstrap.
        // A locked-boot process start cannot reach CE prefs or the disk cache; it defers to
        // USER_UNLOCKED (userUnlockedReceiver), and nothing opens Firestore pre-unlock.
        purgeLegacyCacheIfUnlocked()
        super.onCreate()
        startupTrace.lap("super")
        registerSystemReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
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
        // Owner report 2026-10-31: retired channel ids were still listed in system settings
        // beside their replacements because cleanup ran only on the new id's first post.
        // Create-or-clean at startup so the list never shows a dead duplicate.
        StreakNotifications.ensureMilestonesChannel(this)
        AndroidDomainUsageHost.ensureStatusChannel(this)
        EnhancedModeHealthMonitor.ensureChannel(this)
        startupTrace.lap("channels")
        // Decision 12 opt-out: FirebaseInitProvider ran before this call, so this is what the
        // stored preference overrides from the first moment of each process.
        crashReporting.applyStoredPreference()
        startupTrace.lap("crashpref")
        // Plan §7 step 20: resolve the active identity and adopt its DB scope BEFORE the
        // migration/open block below and before any worker can run (the fence reads CE prefs,
        // so a locked-boot process defers this to USER_UNLOCKED).
        val unlocked = getSystemService(UserManager::class.java).isUserUnlocked
        if (unlocked) {
            // §8.12: the durable journal decides the gate's first state before any frame.
            reconcileAppLockJournal()
            attachAccountCollectors()
            localSessionManager.get().onAppStart(applicationScope)
            // §6.4 item 14: the journal names the one object an active operation still needs;
            // everything else in staging is stale by construction. CE storage → post-unlock only.
            applicationScope.launch {
                runCatching { backupStagingSweeper.get().sweep() }
                    .onFailure { Log.w(TAG, "backup staging sweep failed", it) }
            }
        }
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
            // Account plan §6.8/§6.9.3: an accepted deletion's purge must land before the scope
            // decision below, or this process would reopen the store it is meant to erase.
            if (unlocked) {
                runCatching { deletionLocalData.purgePendingFiles() }
                    .onFailure { Log.w(TAG, "pending local-data purge failed", it) }
            }
            // Plan §8.1: scoped database paths must be active before any store opens. Sync on
            // purpose — the open below and every component's own open read the scope; the first
            // launch after the update copies three files, later launches read one pref.
            runCatching { LocalDatabaseMigration.start(this) }
                .onFailure { Log.w(TAG, "local DB scope migration failed; legacy paths stay active", it) }
            // §8.9: with App Lock enabled the vault is closed until a real in-app unlock, so
            // this upkeep must wait for UNLOCKED instead of throwing VaultLockedException at
            // startup (verified crash on emulator-5556, inventory §12).
            if (unlocked && appLockCoordinator.get().state.value != AppLockState.DISABLED) {
                applicationScope.launch {
                    appLockCoordinator.get().state.first { it == AppLockState.UNLOCKED }
                    runStartupVaultMaintenance(true)
                }
            } else if (unlocked) {
                applicationScope.launch { runStartupVaultMaintenance(true) }
            }
            // Pre-unlock: the stores and the app-lock status are both CE, so the whole pass is
            // deferred — userUnlockedReceiver runs it once the user unlocks (§8.9).
        }
        startupTrace.lap("launches+open")
        registerActivityLifecycleCallbacks(backgroundCheckpointer)
        // §8 Step 9: USER_UNLOCKED is delivered dynamically-only (never a manifest receiver).
        val unlockFilter = IntentFilter(Intent.ACTION_USER_UNLOCKED)
        registerSystemReceiver(userUnlockedReceiver, unlockFilter)
        // WorkManager cannot be touched pre-unlock (direct-boot components; its CE workdb is
        // unreachable — "WorkManager can't be accessed from direct boot"). The health-check
        // period is unique-name KEEP: any post-unlock process start enqueues it.
        if (unlocked) {
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
            // Plan §7 step 10: no sync work under a signed-out or transitioning session —
            // the worker would only no-op against the fence, and a signed-out account has no
            // pairs to serve. Anonymous sessions are fenced-active and keep syncing (ADR #125).
            if (localSessionManager.get().current() != null) {
                enqueuePartnerDeliveryWork()
            }
            enqueueDailyMaintenance(this)
            UpdateCheckWorker.enqueue(this)
            UpdateCheckWorker.enqueueForegroundIfDue(this)
            // Off the main thread: the sweep, the installer query, and the cached-envelope
            // signature check are all disk or crypto work (plan §11).
            applicationScope.launch { updateCoordinator.get().onMainProcessStart() }
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
                // The 5 s startForegroundService contract is serviced by this same main thread.
                // Issued from onCreate, the service-create message sits behind the activity
                // launch and first composition; a cold start past 5 s kills the process
                // (Lean_API34 2026-10-31: create dispatched 23 s after the call). The first
                // idle turn is when that message is guaranteed to run straight after the call.
                Looper.myQueue().addIdleHandler {
                    EnforcementForegroundService.ensureOnProcessStart(this@MyExodusApp)
                    false
                }
            }
            startupTrace.lap("fgs-queued")
            // §8 Step 9: this boot's deferred reconciliation, if any (cold-start BOOT_COUNT
            // check + the locked-boot receiver's pending mark).
            reconcileBootIfNeeded()
            // §8.3/§8.8 lock trigger: whenever the coordinator leaves the vault-access states
            // (manual lock, screen-off, host stop, stale unlock), zero the session DEKs and
            // close both vault stores so the next access reconstructs fresh. Edge-triggered on
            // `allowsVaultAccess`: LOCKED→PREFLIGHT→AUTHENTICATING→OPENING must not re-clear
            // the DEKs an unlock attempt just adopted, and OPENING owns the forced open.
            applicationScope.launch {
                appLockCoordinator.get().state
                    .map { it.allowsVaultAccess }
                    .distinctUntilChanged()
                    .collect { allowed ->
                        if (allowed) return@collect
                        vaultDekSource.clear()
                        var closed = false
                        var attempts = 0
                        // §8.3 item 4: never force-close underneath an executing lease — retry
                        // safe cleanup while the UI stays locked, and stop if a session returns.
                        while (!closed && attempts++ < 20 &&
                            !appLockCoordinator.get().state.value.allowsVaultAccess
                        ) {
                            closed = runCatching { rulesStoreFactory.lockVault() }
                                .onFailure { Log.w(TAG, "vault lock: rules store close failed", it) }
                                .getOrDefault(false)
                            if (!closed) delay(500)
                        }
                        // A session that started while cleanup was retrying owns the stores now.
                        if (appLockCoordinator.get().state.value.allowsVaultAccess) {
                            Log.i(TAG, "vault lock superseded by a new session")
                            return@collect
                        }
                        activityLogStore.close()
                        if (!closed) Log.w(TAG, "vault lock: rules store close deferred")
                    }
            }
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

    private var accountCollectorsAttached = false

    /**
     * Account plan §6.2/§8.4: app-scoped collectors so finalization and import survive a
     * closed pane or a process death. Post-unlock only, from both unlock paths; once per
     * process (the graph constructs FirebaseAuth and CE prefs — §8.9 Direct Boot).
     */
    private fun attachAccountCollectors() {
        if (accountCollectorsAttached) return
        accountCollectorsAttached = true
        guestTransitionCoordinator.get().attach(applicationScope)
        guestImportCoordinator.get().attach(applicationScope)
    }

    /**
     * §4.2 rule 3 / §8.12: inspect the durable migration journal before the ordinary store is
     * constructed. Idempotent; a missing envelope under an enabled status becomes
     * RECOVERY_REQUIRED, never a fresh database.
     */
    private fun reconcileAppLockJournal() {
        runCatching {
            appLockCoordinator.get().adoptRecoveredState(vaultMigration.recoverInterrupted())
        }.onFailure { Log.w(TAG, "App Lock journal recovery failed", it) }
    }

    /**
     * §8.9 startup upkeep that needs the protected stores: eager open, legacy DB split, scope
     * recovery import and the stale-session sweep. Runs at once while App Lock is off and on
     * the first verified unlock while it is on. A lock landing between the unlock and any step
     * below fails closed; it must not take the process down from a background coroutine.
     */
    private suspend fun runStartupVaultMaintenance(osUnlocked: Boolean) {
        if (osUnlocked) {
            runCatching { deletionLocalData.finishPendingPurge() }
                .onFailure { Log.w(TAG, "pending deletion local-state purge failed", it) }
        }
        runCatching { rulesStoreFactory.open() }
            .onFailure { Log.w(TAG, "rules store startup open refused", it) }
        databaseMigration.runOnceIfNeeded()
        // ADR #240: a bound retained scope restores itself; only a conflict, the
        // portable-preferences opt-in, an unreadable copy, or an unbound offer waits.
        if (osUnlocked) {
            runCatching {
                if (scopeRecoveryImporter.get().stageIfOffered()) {
                    guestImportCoordinator.get().refresh()
                }
            }.onFailure { Log.w(TAG, "scope recovery staging failed", it) }
        }
        // W7 session recovery (v2 M2.4 adapted): urge sessions a process death left
        // STARTED past the 15-minute bound close as INTERRUPTED, once per launch.
        runCatching { recoverySessions.interruptStale(System.currentTimeMillis()) }
            .onFailure { Log.w(TAG, "stale recovery-session sweep failed", it) }
        // §8.9: the first authenticated vault generation drains what the locked one held back.
        runCatching { attemptRecorder.get().flushPending() }
            .onFailure { Log.w(TAG, "attempt outbox flush failed", it) }
        runCatching { milestoneScheduler.reconcileAll() }
            .onFailure { Log.w(TAG, "milestone reconcile failed", it) }
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
                // §8.3/§8.9: a locked vault refuses the open; the deferred startup pass runs
                // it on the next verified unlock. The boot claim itself needs no vault data.
                if (!underInstrumentation &&
                    appLockCoordinator.get().state.value.showsProtectedUi
                ) {
                    runCatching { rulesStoreFactory.open() }
                        .onFailure { Log.w(TAG, "boot reconcile store open refused", it) }
                }
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
            // A process started pre-unlock could not purge, resolve the session or migrate
            // (CE prefs and Keystore are unreachable); run all three now, in dependency
            // order: purge first (no Firestore op may precede it), then the fence's scope
            // adoption, then the migration, before the store open that reconcile triggers.
            purgeLegacyCacheIfUnlocked()
            reconcileAppLockJournal()
            attachAccountCollectors()
            localSessionManager.get().onAppStart(applicationScope)
            if (!underInstrumentation) {
                runCatching { deletionLocalData.purgePendingFiles() }
                    .onFailure { Log.w(TAG, "pending local-data purge failed", it) }
                LocalDatabaseMigration.start(context)
                applicationScope.launch {
                    // §8.9: the lock status is readable now; with the feature on, the pass waits
                    // for the first verified in-app unlock like the cold-start path.
                    if (appLockCoordinator.get().state.value != AppLockState.DISABLED) {
                        appLockCoordinator.get().state.first { it == AppLockState.UNLOCKED }
                    }
                    runStartupVaultMaintenance(true)
                }
            }
            // A process started pre-unlock skipped onCreate's scheduling block; R09 requires
            // the first unlock after boot to resume partner delivery without opening the app.
            // Ungated like the boot receiver: FirebaseAuth can still be restoring here, and
            // the workers fence themselves on the session anyway.
            enqueuePartnerDeliveryWork()
            reconcileBootIfNeeded()
        }
    }

    /** Partner delivery plan §4.1/§6.1 step 4: the 15-minute backstop plus the startup
     *  reconciliation for rows a crash left without scheduled work; §6.3's signed-contact
     *  cadence and immediate contact. */
    private fun enqueuePartnerDeliveryWork() {
        enqueuePartnerSync(this)
        enqueuePartnerEventSync(this)
        enqueueDeviceContact(this)
        enqueueDeviceContactNow(this)
        registerContactOnConnectivityRecovery()
        applicationScope.launch { partnerDelivery.get().reconcileOnStart() }
    }

    /** §6.3 connectivity-recovery opportunity: the first reachable network after a loss sends
     *  a fresh signed contact instead of waiting for the next 15-minute slot. Process-lifetime
     *  registration — the system drops it on process death. */
    private fun registerContactOnConnectivityRecovery() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (localSessionManager.get().current() != null) {
                    enqueueDeviceContactNow(this@MyExodusApp)
                }
            }
        }
        runCatching { cm.registerNetworkCallback(request, callback) }
            .onFailure { Log.w(TAG, "contact connectivity listener not registered", it) }
    }

    /** Plan §7 step 20's one-time upgrade purge. Instrumented suites own Firestore/DB state
     *  (the same reason [underInstrumentation] skips the migration below). */
    private fun purgeLegacyCacheIfUnlocked() {
        if (underInstrumentation) return
        if (!getSystemService(UserManager::class.java).isUserUnlocked) return
        runCatching { LegacyCachePurge.run(this) }
            .onFailure { Log.w(TAG, "legacy cache purge failed", it) }
    }

    private companion object {
        const val HEALTH_CHECK_WORK_NAME = "enhanced-mode-health-check"
        const val TAG = "MyExodusApp"
    }

    /** App backgrounded (last activity stopped): bounded WAL growth on both stores. */
    private val backgroundCheckpointer = object : ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            val firstInForeground = foregroundActivities == 0
            foregroundActivities++
            if (!firstInForeground) return
            // Partner delivery plan §4.1: every foreground transition drains in-process,
            // ahead of any WorkManager dispatch latency. App Lock gates enrollment/rotation
            // to the vault-access states (§8.3.1).
            applicationScope.launch {
                partnerDelivery.get().drainInProcess(
                    appLockCoordinator.get().state.value.allowsVaultAccess,
                )
            }
            // §6.3: every foreground transition is also a signed-contact opportunity; the
            // worker itself skips when signed out or unregistered.
            if (localSessionManager.get().current() != null) {
                enqueueDeviceContactNow(this@MyExodusApp)
            }
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
