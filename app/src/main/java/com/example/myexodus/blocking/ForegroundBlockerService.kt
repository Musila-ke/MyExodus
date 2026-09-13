package com.example.myexodus.blocking

import android.app.ActivityManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.annotation.SuppressLint
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.myexodus.settings.EnhancedModeSettings
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.inject.Inject
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * App-block foreground service (blocking-core plan §8.1/§8.2). Polls
 * [UsageStatsManager.queryEvents] for ACTIVITY_RESUMED (same underlying value as the
 * deprecated MOVE_TO_FOREGROUND, per plan Decision 4) and launches [BlockerActivity] when
 * the decision engine blocks the current foreground package. Detection is deliberately
 * best-effort and poll-based — no push API exists; the measured latency lands in the §12
 * suite. The accessibility overlay (Enhanced Mode) is not required here; when it is also
 * enabled, its instant overlay doubles as the §8.1 transient bridge while BlockerActivity
 * starts — since ADR #32 it shows the same card (same naming, same recovery action), so the
 * handover is visually seamless.
 *
 * §8.2: permission state is checked on its own cycle (never only MainActivity.onResume);
 * a Task-Manager "Stop" (REASON_USER_REQUESTED since the last known-good watermark) puts
 * protection into degraded state requiring explicit re-enable. The service keeps running
 * while degraded so it can post/clear the alert and resume the instant it is re-enabled.
 */
@AndroidEntryPoint
class ForegroundBlockerService : Service() {

    @Inject lateinit var settings: EnhancedModeSettings
    @Inject lateinit var engine: BlockDecisionEngine
    @Inject lateinit var emergency: EmergencyExclusionManager
    @Inject lateinit var publisher: RulesSnapshotPublisher
    @Inject lateinit var status: ProtectionStatusStore
    @Inject lateinit var attempts: AttemptRecorder

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pollingJob: Job? = null

    /** Fresh thread per submission; a wedged binder call abandons its thread (it un-wedges
     *  and exits on the next system mutation) instead of stalling the loop. */
    private val tickExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "myexodus-fgs-tick").apply { isDaemon = true }
    }

    private val scanner by lazy { AppBlockScanner(engine, emergency) }

    override fun onCreate() {
        super.onCreate()
        ProtectionNotifications.createChannels(this)
        startForeground(
            ProtectionNotifications.NOTIF_FGS,
            ProtectionNotifications.ongoing(this),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        start()
        // START_STICKY: after a crash the system restarts us with a null intent, which is
        // exactly the §8.1 "startup (including after a crash) recompute from current state"
        // path — no stale-overlay detection exists or is attempted.
        return START_STICKY
    }

    private fun start() {
        if (!settings.enhancedModeEnabled) {
            stopSelf()
            return
        }
        checkForUserStop()
        publisher.loadFastPath()
        scope.launch { publisher.loadFromDb() }
        pollingJob?.cancel()
        pollingJob = scope.launch { pollLoop() }
    }

    /** §8.2: a Task-Manager Stop since the last known-good startup is an enforcement-loss
     *  event — degraded state, blocking stays off until the user explicitly re-enables. */
    private fun checkForUserStop() {
        val watermark = status.lastKnownGoodExitCheckEpochMillis
        status.lastKnownGoodExitCheckEpochMillis = System.currentTimeMillis()
        val exitReasons = runCatching {
            val manager = getSystemService(ActivityManager::class.java)
            val packageName = packageName
            // getHistoricalProcessExitReasons is API 30+; on older releases there is no
            // Task-Manager Stop surface to detect.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                @Suppress("InlinedApi")
                manager.getHistoricalProcessExitReasons(packageName, 0, 0)
                    .map { ProcessExit(it.reason, it.timestamp) }
            } else {
                emptyList()
            }
        }.getOrDefault(emptyList())
        if (UserStoppedDetector.wasUserStopped(exitReasons, watermark)) {
            Log.w(TAG, "Task-Manager Stop detected — protection degraded, re-enable required")
            status.degradation = ProtectionDegradation.APP_STOPPED
            status.requiresExplicitReEnable = true
            ProtectionNotifications.updateDegraded(this, ProtectionDegradation.APP_STOPPED)
        }
    }

    private suspend fun pollLoop() {
        var lastQueryEndMillis = 0L
        var ticks = 0L
        while (coroutineContext.isActive) {
            if (!settings.enhancedModeEnabled) {
                stopSelf()
                return
            }
            // Time-bounded tick (caught on-device 2026-08-30 on XOS: a sync appops/usage-stats
            // binder call can wedge indefinitely after a system-side permission mutation, which
            // would otherwise freeze enforcement while "Protection active" still shows — the
            // §9 silent-loss state. A wedged call abandons its thread and the next tick runs
            // on a fresh one; the wedged thread un-wedges on the next mutation and exits.
            // EVERYTHING touching binder (usage check, permission check, queryEvents) runs
            // inside the executor — the coroutine itself must stay binder-free.
            runCatching { lastQueryEndMillis = tickWithTimeout(++ticks, lastQueryEndMillis) }
                .onFailure { Log.w(TAG, "tick failed", it) }
            delay(POLL_INTERVAL_MILLIS)
        }
    }

    /** Runs one tick body on a fresh-thread-per-submission pool so a hung call can't stall
     *  the loop; times out and logs rather than blocking forever. Returns the tick's query
     *  end (unchanged on timeout — next tick just re-queries a wider window). */
    private fun tickWithTimeout(tickNumber: Long, lastQueryEndMillis: Long): Long {
        // submit(Callable), not the Runnable overload — Kotlin SAM inference otherwise
        // picks Runnable and the Future loses its type (Any! → compile error).
        val future = tickExecutor.submit(Callable { tick(tickNumber, lastQueryEndMillis) })
        return try {
            future.get(TICK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            Log.w(TAG, "tick $tickNumber exceeded ${TICK_TIMEOUT_MILLIS}ms — abandoning wedged thread")
            lastQueryEndMillis
        }
    }

    private fun tick(tickNumber: Long, lastQueryEndMillisAtStart: Long): Long {
        var lastQueryEndMillis = lastQueryEndMillisAtStart
        val usageOk = hasUsageAccess()
        if (!usageOk) {
            // Detection impossible without usage access — §8.2/§9 degraded state, clearly
            // labeled, not silent. Re-checked every cycle so restoration re-arms instantly.
            // APP_STOPPED (requiresExplicitReEnable) outranks this alert.
            if (status.degradation != ProtectionDegradation.USAGE_ACCESS_REVOKED &&
                !status.requiresExplicitReEnable
            ) {
                Log.w(TAG, "degraded: USAGE_ACCESS_REVOKED")
                status.degradation = ProtectionDegradation.USAGE_ACCESS_REVOKED
                ProtectionNotifications.updateDegraded(this, ProtectionDegradation.USAGE_ACCESS_REVOKED)
            }
        } else {
            if (status.degradation == ProtectionDegradation.USAGE_ACCESS_REVOKED) {
                Log.i(TAG, "recovered: usage access restored")
                status.degradation = null
                ProtectionNotifications.updateDegraded(this, null)
            }
            if (!status.requiresExplicitReEnable) {
                val end = System.currentTimeMillis()
                // First tick looks back far enough that the last ACTIVITY_RESUMED in the
                // window is the current foreground app (§8.1 startup recompute).
                val begin = if (lastQueryEndMillis == 0L) end - STARTUP_LOOKBACK_MILLIS else lastQueryEndMillis
                if (begin < end) {
                    val latest = latestResumedPackage(runCatching { usageStats().queryEvents(begin, end) }.getOrNull())
                    val outcome = scanner.evaluate(latest, packageName, publisher.snapshot, end)
                    if (outcome is AppBlockDecision.Launch && activeBlockTarget != outcome.packageName) {
                        Log.i(TAG, "detected ${outcome.packageName} — launching blocker")
                        activeBlockTarget = outcome.packageName
                        runCatching { launchBlocker(outcome) }
                        // §6 attempt log: recorded on the detection path (the durable local
                        // record; partner delivery is the PartnerSync worker's reconcile).
                        // runBlocking bridges the suspend DAO from the executor thread; the
                        // tick's 10s hard bound covers a wedged store.
                        runCatching { runBlocking { attempts.recordBlocked(outcome.ruleId, outcome.packageName) } }
                    }
                }
                lastQueryEndMillis = end
            }
        }
        if (tickNumber % PERMISSION_CHECK_TICKS == 0L) {
            checkNotificationsPermission()
            checkOverlayPermission()
        }
        if (tickNumber % HEARTBEAT_TICKS == 0L) {
            Log.i(TAG, "tick $tickNumber: usage=$usageOk degradation=${status.degradation}")
        }
        return lastQueryEndMillis
    }

    /** NOTIFICATIONS_OFF is a soft state and never overrides a harder degradation
     *  (APP_STOPPED / USAGE_ACCESS_REVOKED — plan §9 keeps those the visible alert). */
    private fun checkNotificationsPermission() {
        // POST_NOTIFICATIONS only exists on 33+; on older releases the permission is granted
        // by default and the user-facing toggle is exactly areNotificationsEnabled, so a raw
        // checkSelfPermission there would report DENIED for an unknown permission — a
        // spurious NOTIFICATIONS_OFF on every pre-33 device.
        val denied = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED ||
                !NotificationManagerCompat.from(this).areNotificationsEnabled()
        } else {
            !NotificationManagerCompat.from(this).areNotificationsEnabled()
        }
        val harder = status.degradation == ProtectionDegradation.APP_STOPPED ||
            status.degradation == ProtectionDegradation.USAGE_ACCESS_REVOKED
        if (denied) {
            if (!harder && status.degradation != ProtectionDegradation.NOTIFICATIONS_OFF) {
                Log.w(TAG, "degraded: NOTIFICATIONS_OFF")
                status.degradation = ProtectionDegradation.NOTIFICATIONS_OFF
            }
        } else if (status.degradation == ProtectionDegradation.NOTIFICATIONS_OFF) {
            Log.i(TAG, "recovered: notifications restored")
            status.degradation = null
            ProtectionNotifications.updateDegraded(this, null)
        }
    }

    /** Overlay permission matters only on Transsion (TYPE_APPLICATION_OVERLAY path); on stock
     *  the accessibility overlay needs no permission. Revocation otherwise silently kills the
     *  overlay while "Protection active" still shows — a §9 silent enforcement loss. */
    private fun checkOverlayPermission() {
        if (!OverlayController.isTranssion()) return
        val granted = OverlayController.overlayPermissionGranted(this)
        val harder = status.degradation == ProtectionDegradation.APP_STOPPED ||
            status.degradation == ProtectionDegradation.USAGE_ACCESS_REVOKED
        if (!granted) {
            if (!harder && status.degradation != ProtectionDegradation.OVERLAY_REVOKED) {
                Log.w(TAG, "degraded: OVERLAY_REVOKED")
                status.degradation = ProtectionDegradation.OVERLAY_REVOKED
                ProtectionNotifications.updateDegraded(this, ProtectionDegradation.OVERLAY_REVOKED)
            }
        } else if (status.degradation == ProtectionDegradation.OVERLAY_REVOKED) {
            Log.i(TAG, "recovered: overlay permission restored")
            status.degradation = null
            ProtectionNotifications.updateDegraded(this, null)
        }
    }

    private fun usageStats(): UsageStatsManager = getSystemService(UsageStatsManager::class.java)

    /** Latest ACTIVITY_RESUMED event in the window; pre-API 29 the package field is read
     *  reflectively (getPackageName is API 29+; the value of ACTIVITY_RESUMED equals the
     *  deprecated MOVE_TO_FOREGROUND on older runtimes, so one comparison covers both). */
    private fun latestResumedPackage(events: UsageEvents?): String? {
        if (events == null) return null
        var latest: String? = null
        while (events.hasNextEvent()) {
            val event = UsageEvents.Event()
            if (!events.getNextEvent(event)) continue
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                latest = eventPackage(event)
            }
        }
        return latest
    }

    @SuppressLint("DiscouragedPrivateApi") // pre-29-only fallback; getPackageName is API 29+ (ADR #24)
    private fun eventPackage(event: UsageEvents.Event): String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        event.packageName
    } else {
        runCatching {
            UsageEvents.Event::class.java.getDeclaredField("mPackage")
                .apply { isAccessible = true }
                .get(event) as? String
        }.getOrNull()
    }

    private fun launchBlocker(outcome: AppBlockDecision.Launch) {
        val intent = Intent(this, BlockerActivity::class.java)
            .putExtra(BlockerActivity.EXTRA_PACKAGE, outcome.packageName)
            .putExtra(BlockerActivity.EXTRA_RULE_ID, outcome.ruleId)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
            )
        startActivity(intent)
    }

    override fun onDestroy() {
        pollingJob?.cancel()
        scope.cancel()
        tickExecutor.shutdownNow()
        activeBlockTarget = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val TAG = "MyExodusBlockerFgs"
        const val ACTION_STOP = "com.example.myexodus.action.STOP_BLOCKER_FGS"

        /** Poll cadence (plan §8.1: fixed interval; no zero-latency claim). Latency is
         *  measured per plan §11 on physical devices in the §12 suite — no number is claimed
         *  here or in docs until then. */
        const val POLL_INTERVAL_MILLIS = 1500L

        /** First-tick lookback so the current foreground app is picked up at startup
         *  (the §8.1 recompute-from-current-state path, not stale-overlay detection): the
         *  last ACTIVITY_RESUMED within the window is the current foreground app, so the
         *  window must exceed any realistic idle dwell. */
        const val STARTUP_LOOKBACK_MILLIS = 12 * 60 * 60 * 1000L

        /** Permission re-check cadence in poll ticks (≈45s at the poll interval). */
        const val PERMISSION_CHECK_TICKS = 30L

        /** A tick's hard time bound — far above the ~ms normal cost, below the point where
         *  a hang becomes a §9 silent enforcement loss. */
        const val TICK_TIMEOUT_MILLIS = 10_000L

        /** Heartbeat log cadence in poll ticks (≈30s): tick count, usage access, degradation. */
        const val HEARTBEAT_TICKS = 20L

        /** Package the blocker is currently covering; null when none. Cleared in
         *  BlockerActivity.onDestroy; the poll skips relaunching while it is set. */
        @Volatile
        var activeBlockTarget: String? = null

        fun start(context: Context) {
            val intent = Intent(context, ForegroundBlockerService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ForegroundBlockerService::class.java))
        }
    }
}
