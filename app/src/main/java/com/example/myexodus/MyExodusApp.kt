package com.example.myexodus

import android.app.Activity
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.os.Bundle
import android.os.UserManager
import android.view.accessibility.AccessibilityManager
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.myexodus.accountability.sync.AppCheckInstaller
import com.example.myexodus.accountability.sync.PartnerSyncWorker
import com.example.myexodus.blocking.EnhancedModeHealthMonitor
import com.example.myexodus.blocking.HealthCheckWorker
import com.example.myexodus.data.db.ActivityLogStore
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.DatabaseMigration
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

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var foregroundActivities = 0

    private val accessibilityStateListener = AccessibilityManager.AccessibilityStateChangeListener {
        healthMonitor.refresh()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(hiltWorkerFactory).build()

    override fun onCreate() {
        super.onCreate()
        // §8.5: App Check provider in place before ANY Firestore op (mailbox pull, backfill
        // responder, drain) — app-startup install covers paths that never route through drainOutbox.
        AppCheckInstaller.install(this)
        // Eager open off the main thread; failure lands in a defined DatabaseHealth state, not a crash.
        // Open FIRST: Room 3 creates the schema lazily on first DAO access, and the migration's
        // INSERTs fail with "no such table" against an unprimed store (caught 2026-08-28 review).
        // Migration no-ops when already complete.
        applicationScope.launch {
            rulesStoreFactory.open()
            databaseMigration.runOnceIfNeeded()
        }
        registerActivityLifecycleCallbacks(backgroundCheckpointer)
        // WorkManager cannot be touched pre-unlock (direct-boot components; its CE workdb is
        // unreachable — "WorkManager can't be accessed from direct boot"). The health-check
        // period is unique-name KEEP: any post-unlock process start enqueues it.
        if (getSystemService(UserManager::class.java).isUserUnlocked) {
            enqueueHealthCheck()
            enqueuePartnerSync()
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

    /** §14 partner sync housekeeping (plan §7 Decision 7): attempt → event reconcile, outbox
     *  drain, mailbox pull, backfill response. No-op per run when unpaired. */
    private fun enqueuePartnerSync() {
        val request = PeriodicWorkRequestBuilder<PartnerSyncWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            PARTNER_SYNC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    private companion object {
        const val HEALTH_CHECK_WORK_NAME = "enhanced-mode-health-check"
        const val PARTNER_SYNC_WORK_NAME = "partner-sync"
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
