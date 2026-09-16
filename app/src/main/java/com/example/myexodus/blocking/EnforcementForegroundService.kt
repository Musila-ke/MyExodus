package com.example.myexodus.blocking

import android.app.ActivityManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.annotation.SuppressLint
import android.util.Log
import android.view.Display
import androidx.core.content.ContextCompat
import com.example.myexodus.blocking.usage.EnforcementAction
import com.example.myexodus.blocking.usage.UsageAccountingEngine
import com.example.myexodus.blocking.usage.UsageSessionMachine
import com.example.myexodus.settings.EnhancedModeSettings
import com.example.myexodus.telemetry.CrashReporting
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
 * App-block + daily-usage-limit foreground service (blocking-core plan §8.1/§8.2;
 * enforcement plan v2 §8 Step 5 "Path B", folded in per the 2026-09-15 user ruling — one
 * always-on process instead of a second one). Polls [UsageStatsManager.queryEvents] for
 * ACTIVITY_RESUMED (same underlying value as the deprecated MOVE_TO_FOREGROUND) and:
 *
 *  - launches [BlockerActivity] when the schedule decision engine blocks the current
 *    foreground package (§8.1);
 *  - feeds the same observation into Path B's usage session machine and adaptively accounts
 *    it (§8 Step 5): 30s baseline cadence, 5s while the remaining budget is under 3 minutes.
 *    Path B's block is the overlay itself — a bare-addView TYPE_APPLICATION_OVERLAY wall,
 *    never a launched Activity and never a HOME-launch dependency (Decision 16).
 *
 * Deviation from §8 Step 5, ruled 2026-09-15: the plan's separate 30s discovery loop is
 * replaced by this service's existing 1.5s poll (a strict superset — session transitions are
 * detected at ≤1.5s instead of ≤30s), and the plan's "stop the loop when Usage Access is
 * revoked" is replaced by the service's existing degraded-state machinery, which already
 * skips every usage-stats call while revoked (no looping on failures) while keeping the
 * loop alive to re-arm the instant access is restored.
 *
 * Detection is deliberately best-effort and poll-based — no push API exists; the measured
 * latency lands in the §12 suite. The accessibility overlay (Enhanced Mode) is not required
 * here; when it is also enabled, its instant overlay doubles as the §8.1 transient bridge
 * while BlockerActivity starts — since ADR #32 it shows the same card (same naming, same
 * recovery action), so the handover is visually seamless. Usage accounting has the mirror
 * gate: Path B writes usage only while [ProtectionStatusMonitor] says it holds authority
 * (Decision 2 / §8 Step 6).
 *
 * §8.2/§8 Step 6: protection state is re-evaluated on its own 30s cycle (never only
 * MainActivity.onResume) by the shared [ProtectionStatusMonitor]; a Task-Manager "Stop"
 * (REASON_USER_REQUESTED since the last known-good watermark) puts protection into degraded
 * state requiring explicit re-enable. The service keeps running while degraded so it can
 * post/clear the alert and resume the instant it is re-enabled.
 */
@AndroidEntryPoint
class EnforcementForegroundService : Service() {

    @Inject lateinit var settings: EnhancedModeSettings
    @Inject lateinit var engine: BlockDecisionEngine
    @Inject lateinit var emergency: EmergencyExclusionManager
    @Inject lateinit var publisher: RulesSnapshotPublisher
    @Inject lateinit var status: ProtectionStatusStore
    @Inject lateinit var statusMonitor: ProtectionStatusMonitor
    @Inject lateinit var attempts: AttemptRecorder
    @Inject lateinit var usageEngine: UsageAccountingEngine
    @Inject lateinit var profiler: LatencyProfiler
    @Inject lateinit var crashReporting: CrashReporting

    /** Path B's session machine + overlay host (§8 Step 5). Service-scoped like Path A's
     *  per-bind pair: a lost overlay/window state must not outlive the service instance. */
    private val usageSessions = UsageSessionMachine()
    private val usageStateMachine = ForegroundStateMachine()
    private lateinit var usageOverlay: OverlayController
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Deadline (elapsed-realtime ms) for the open session's next accounting tick — the
     *  adaptive cadence (§8 Step 5). Set at session open, refined after every credit from the
     *  package's remaining budget. */
    @Volatile private var nextUsageAccountAtElapsedMs = 0L

    /** Current foreground package per the poll (the FGS has no window list — its foreground
     *  knowledge is this observation). Carried forward across event-less polls: null latest
     *  means "no new evidence", not "nothing is foreground" (device-caught 2026-09-15 —
     *  clearing it here made every tick-originated verdict fail the render gate). */
    @Volatile private var lastObservedForegroundPackage: String? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pollingJob: Job? = null
    private var screenOffReceiver: BroadcastReceiver? = null

    /** Fresh thread per submission; a wedged binder call abandons its thread (it un-wedges
     *  and exits on the next system mutation) instead of stalling the loop. */
    private val tickExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "myexodus-fgs-tick").apply { isDaemon = true }
    }

    private val scanner by lazy { AppBlockScanner(engine, emergency) }

    /** §14 bullet 5 uptime anchor; 0 until [onCreate] (a destroy without create is not real). */
    private var serviceStartedElapsedRealtimeMs = 0L

    override fun onCreate() {
        super.onCreate()
        // §14 bullet 5: one event per service creation (the system's post-kill restart lands
        // here too), paired with the destroy event when the stop is graceful — starts without
        // a stop are the kill-rate signal, stop events carry the uptime.
        serviceStartedElapsedRealtimeMs = SystemClock.elapsedRealtime()
        crashReporting.nonFatal(
            "path_b_service_start",
            cause = "service_create",
            "oem" to Build.MANUFACTURER,
            "api" to Build.VERSION.SDK_INT.toString(),
        )
        ProtectionNotifications.createChannels(this)
        startForeground(
            ProtectionNotifications.NOTIF_FGS,
            ProtectionNotifications.ongoing(this),
        )
        // Path B's host: bare addView + TYPE_APPLICATION_OVERLAY on every device (the
        // accessibility token type is unavailable to a plain foreground service).
        usageOverlay = OverlayController(
            this, usageStateMachine, profiler,
            onOpenRecovery = {}, onDifferentSite = {},
            applicationOverlayHost = true,
        )
        registerScreenOffReceiver()
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
        // §8 Step 6: publish the current authority + status at service start (covers the
        // master switch being turned on/off and any permission change while stopped).
        statusMonitor.refresh()
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
            // The flag only: the refresh below derives and publishes APP_STOPPED from it.
            status.requiresExplicitReEnable = true
        }
    }

    private suspend fun pollLoop() {
        var lastQueryEndMillis = 0L
        var ticks = 0L
        // Handoff A→B (device QA 2026-09-15): while Path A held authority the machine was
        // reset every tick, and after the flip a narrow window stays eventless as long as
        // the user sits in one app — re-acquisition then waited on the next real
        // ACTIVITY_RESUMED (28s measured). The tick that first sees the authority regained
        // widens to the startup lookback so the app already in the foreground is found; the
        // session anchors at poll-now, never at the event's timestamp, so the minutes Path A
        // already credited can never be re-credited (at worst a one-poll overlap at the
        // handoff instant).
        var heldAuthority = false
        while (coroutineContext.isActive) {
            if (!settings.enhancedModeEnabled) {
                stopSelf()
                return
            }
            val holdsAuthority = pathBHoldsAuthority()
            if (holdsAuthority && !heldAuthority && lastQueryEndMillis != 0L) {
                Log.i(TAG, "authority regained — widening lookback to re-acquire the foreground")
                lastQueryEndMillis = 0L
            }
            heldAuthority = holdsAuthority
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
        // Usage access revoked means queryEvents is impossible; APP_STOPPED means the user
        // stopped the app and blocking stays off until they explicitly re-enable. Either way
        // the scan is skipped — the status itself is §8 Step 6's job (statusMonitor below).
        if (usageOk && !status.requiresExplicitReEnable) {
            val end = System.currentTimeMillis()
            // First tick looks back far enough that the last ACTIVITY_RESUMED in the
            // window is the current foreground app (§8.1 startup recompute).
            val begin = if (lastQueryEndMillis == 0L) end - STARTUP_LOOKBACK_MILLIS else lastQueryEndMillis
            if (begin < end) {
                val latest = latestResumedPackage(runCatching { usageStats().queryEvents(begin, end) }.getOrNull())
                val outcome = scanner.evaluate(latest, packageName, publisher.snapshot, end)
                feedUsage(latest, scheduleBlocked = outcome is AppBlockDecision.Launch)
                maybeAccountUsage()
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
        if (tickNumber % PERMISSION_CHECK_TICKS == 0L) {
            // §8 Step 6: this IS the permission re-check now — one refresh recomputes
            // authority from accessibility/usage-access/overlay state and publishes the one
            // visible degradation (replacing the FGS's old per-mechanism alert branches).
            statusMonitor.refresh()
        }
        if (tickNumber % HEARTBEAT_TICKS == 0L) {
            Log.i(
                TAG,
                "tick $tickNumber: usage=$usageOk authority=${statusMonitor.authority.value} " +
                    "degradation=${status.degradation}",
            )
        }
        return lastQueryEndMillis
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

    // --- Path B: daily-usage sessions + wall (enforcement plan v2 §8 Step 5, §6 steps 2–4) --

    /** Decision 2 via §8 Step 6's arbiter: Path B credits usage only while it holds the
     *  accounting authority, so exactly one path writes any given span. */
    private fun pathBHoldsAuthority(): Boolean =
        statusMonitor.authority.value == ProtectionAuthority.DEGRADED_POLLING_ONLY

    /** One poll observation in: keeps the wall following the foreground and feeds Path B's
     *  session machine. [scheduleBlocked] = this poll ruled the app blocked by its schedule
     *  (BlockerActivity is covering it) — covered time must not accrue as usage. */
    private fun feedUsage(latest: String?, scheduleBlocked: Boolean) {
        lastObservedForegroundPackage = currentForegroundAfterPoll(lastObservedForegroundPackage, latest)
        val wall = (usageOverlay.currentContent(Display.DEFAULT_DISPLAY) as? OverlayContent.DailyLimitBlocked)
            ?.packageName
        if (shouldHideWall(wall, latest)) {
            mainHandler.post {
                // Re-checked at run time: a queued hide whose foreground returned to the
                // walled app must not lift a live wall (the re-arm gap).
                if (lastObservedForegroundPackage != wall) {
                    usageOverlay.hide(usageStateMachine.currentGeneration, Display.DEFAULT_DISPLAY)
                }
            }
        }
        if (!pathBHoldsAuthority()) {
            // Path A is the accounting authority: Path B keeps no session across the handoff —
            // crediting a span that began under Path A would double-count it. Dropping the
            // session loses at most one poll interval of accrual instead.
            usageSessions.reset()
            return
        }
        if (latest == null) return
        // A limit wall already up for this package (or a schedule block) means covered time:
        // the machine closes any open session without reopening.
        val observed = usageSessions.observeForeground(
            latest,
            tracked = !scheduleBlocked && latest != wall && isLimitTracked(latest),
            displayId = Display.DEFAULT_DISPLAY,
            nowElapsedMs = SystemClock.elapsedRealtime(),
            bootCount = systemBootCount(this),
        )
        observed.closedTick?.let { accountUsage(it) }
        if (observed.opened) {
            // Baseline deadline first (a failed store then backs off a full interval), refined
            // by the engine read below — a re-entry near the boundary starts at 5s, not 30s.
            nextUsageAccountAtElapsedMs = SystemClock.elapsedRealtime() + USAGE_TICK_DEFAULT_MS
            // Instant zero-delta evaluation on open: re-arms the wall within one poll of
            // re-entry (§8 Step 4 re-arm) and makes a non-positive limit block immediately.
            accountUsage(UsageSessionMachine.Tick(latest, 0L, Display.DEFAULT_DISPLAY))
        }
    }

    /** The adaptive ticker (§8 Step 5): credits the open session once its deadline passes.
     *  Deadline-driven rather than a delay loop, so the cadence is exact — the poll only
     *  bounds how late a tick can run (≤ one poll interval). */
    private fun maybeAccountUsage() {
        if (usageSessions.activePackage == null || !pathBHoldsAuthority()) return
        val now = SystemClock.elapsedRealtime()
        if (now < nextUsageAccountAtElapsedMs) return
        // Consume the deadline before ticking: the tick re-anchors the session, so even a
        // dropped one (boot-count mismatch) can never credit the same span twice — the next
        // credit is re-anchored by the refine below, and a failure here re-anchors at open.
        nextUsageAccountAtElapsedMs = now + USAGE_TICK_TIGHT_MS
        usageSessions.tick(now, systemBootCount(this))?.let { accountUsage(it) }
    }

    /** Ends the open session with its final tick (§6 step 4) — idempotent. */
    private fun closeUsageSession() {
        usageSessions.close(SystemClock.elapsedRealtime())?.let { accountUsage(it) }
    }

    /** One accounting write + render, on Path B's own engine dispatcher. */
    private fun accountUsage(tick: UsageSessionMachine.Tick) {
        scope.launch {
            runCatching {
                // Refine the cadence before the write: the budget read is advisory, and a
                // failed write must not leave the session at a stale deadline. Only the
                // active session's budget may set it — a closing session's late refine would
                // otherwise loosen the new session's 5s boundary cadence back to 30s.
                val budget = usageEngine.remainingBudgetMs(tick.packageName)
                if (usageSessions.activePackage == tick.packageName) {
                    nextUsageAccountAtElapsedMs =
                        SystemClock.elapsedRealtime() + usageTickIntervalMs(budget)
                }
                val action = usageEngine.processUsageObservation(
                    tick.packageName, tick.deltaMs, SystemClock.elapsedRealtime(),
                )
                mainHandler.post { renderUsageAction(tick.packageName, action) }
            }.onFailure { Log.w(TAG, "usage accounting failed pkg=${tick.packageName}", it) }
        }
    }

    /** Renders the engine's verdict on the main thread (§8 Steps 5/10). A verdict rendered
     *  after its app already left the foreground touches nothing — the pending warning state
     *  survives and retries on the app's next observation (Decision 14), and the wall re-arms
     *  on the next foreground enter. */
    private fun renderUsageAction(packageName: String, action: EnforcementAction) {
        if (!pathBHoldsAuthority() || lastObservedForegroundPackage != packageName) return
        when (action) {
            is EnforcementAction.NoAction -> Unit
            is EnforcementAction.ShowWarning -> {
                // Never over a wall: a banner for an app whose wall is up is noise (and its
                // delivery confirm would be a lie — the state stays PENDING).
                if (usageOverlay.currentContent(Display.DEFAULT_DISPLAY) is OverlayContent.DailyLimitBlocked) {
                    return
                }
                usageOverlay.show(
                    usageStateMachine.currentGeneration,
                    Display.DEFAULT_DISPLAY,
                    usageOverlay.warningBounds(Display.DEFAULT_DISPLAY),
                    OverlayContent.Warning(action.level, action.remainingMs),
                ) {
                    // Delivery confirmed only once the banner actually attached (§9: a warning
                    // that failed to attach stays PENDING_DELIVERY / Decision 14).
                    scope.launch { runCatching { usageEngine.markWarningDelivered(packageName, action.level) } }
                }
            }
            is EnforcementAction.Block -> {
                // The wall replaces the session: nothing accrues under a covered app. No
                // eviction (Decision 16) — the wall is focusable and touch-modal, so Home is
                // the user's own escape, never a performed action.
                if (usageSessions.activePackage == packageName) closeUsageSession()
                if (usageOverlay.currentContent(Display.DEFAULT_DISPLAY) !is OverlayContent.DailyLimitBlocked) {
                    usageOverlay.show(
                        usageStateMachine.currentGeneration,
                        Display.DEFAULT_DISPLAY,
                        usageOverlay.displayBounds(Display.DEFAULT_DISPLAY),
                        OverlayContent.DailyLimitBlocked(packageName),
                    )
                }
            }
        }
    }

    /** A tracked package = an enabled rule with a real limit — mirrors BlockDecisionEngine
     *  Rule 2's `dailyLimitMs > 0` gate and the snapshot's disabled-rule filtering. Read off
     *  the cached snapshot: no DB I/O on the poll path. */
    private fun isLimitTracked(packageName: String): Boolean =
        (publisher.snapshot.appRules[packageName]?.dailyLimitMs ?: 0L) > 0L

    /** Deterministic hide on screen-off, mirroring Path A's: the keyguard foreground change
     *  can lag seconds, and a wall left on the lock screen would greet the user on unlock.
     *  The session closes with its final tick — no phantom accrual while dark. */
    private fun registerScreenOffReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                usageOverlay.hideAll(usageStateMachine.currentGeneration)
                closeUsageSession()
            }
        }
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            // RECEIVER_EXPORTED is a 33+ constant; its value (2) is ignored by the 26-32
            // registerReceiver (flags were reserved until 33) but keeps every path flagged.
            @Suppress("InlinedApi")
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        }
        screenOffReceiver = receiver
    }

    override fun onDestroy() {
        pollingJob?.cancel()
        scope.cancel()
        tickExecutor.shutdownNow()
        screenOffReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenOffReceiver = null
        mainHandler.removeCallbacksAndMessages(null)
        usageSessions.reset()
        usageOverlay.detachAll()
        activeBlockTarget = null
        // §8 Step 6: the service is no longer running, so it can no longer hold authority.
        // (Idempotent — the master switch's stop path and a system kill land here alike.)
        runCatching { statusMonitor.refresh() }
        // §14 bullet 5, graceful half — a process kill never reaches here, which is exactly
        // what makes start-without-stop the kill signal.
        crashReporting.nonFatal(
            "path_b_service_stop",
            cause = "service_destroy",
            "uptimeMs" to (SystemClock.elapsedRealtime() - serviceStartedElapsedRealtimeMs).toString(),
        )
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

        /** Protection-status refresh cadence in poll ticks (30s at the poll interval — the
         *  plan's §8 Step 6 "Path B discovery-loop tick" for status re-evaluation). */
        const val PERMISSION_CHECK_TICKS = 20L

        /** A tick's hard time bound — far above the ~ms normal cost, below the point where
         *  a hang becomes a §9 silent enforcement loss. */
        const val TICK_TIMEOUT_MILLIS = 10_000L

        /** Heartbeat log cadence in poll ticks (≈30s): tick count, usage access, degradation. */
        const val HEARTBEAT_TICKS = 20L

        /** Path B's baseline accounting cadence (enforcement plan v2 §8 Step 5). */
        const val USAGE_TICK_DEFAULT_MS = 30_000L

        /** Tightened cadence once the remaining budget drops under three minutes (§8 Step 5):
         *  bounds near-boundary warning/block slip to ~5s instead of ~30s. */
        const val USAGE_TICK_TIGHT_MS = 5_000L

        /** §8 Step 5's "under 3 minutes" boundary — strict, per the plan's wording. */
        private const val USAGE_TIGHT_THRESHOLD_MS = 3 * 60_000L

        /** Adaptive cadence decision (§8 Step 5); pure for direct testing. Null = budget
         *  unknown (untracked package, store unavailable) — the safe baseline. */
        fun usageTickIntervalMs(remainingBudgetMs: Long?): Long =
            if (remainingBudgetMs != null && remainingBudgetMs < USAGE_TIGHT_THRESHOLD_MS) {
                USAGE_TICK_TIGHT_MS
            } else {
                USAGE_TICK_DEFAULT_MS
            }

        /** Wall lifecycle: the wall follows the foreground, so a different package in front
         *  (Home included) lifts it. Null on either side is no evidence of a change — a
         *  failed query must never drop a live wall. Pure for direct testing. */
        fun shouldHideWall(wallPackage: String?, latestForeground: String?): Boolean =
            wallPackage != null && latestForeground != null && latestForeground != wallPackage

        /** The poll's foreground knowledge, carried forward: an event-less poll (null latest)
         *  carries no evidence of a change and must not erase the current foreground the
         *  render gate and the wall-hide re-check read. Pure for direct testing. */
        fun currentForegroundAfterPoll(previous: String?, latest: String?): String? =
            latest ?: previous

        /** Package the blocker is currently covering; null when none. Cleared in
         *  BlockerActivity.onDestroy; the poll skips relaunching while it is set. */
        @Volatile
        var activeBlockTarget: String? = null

        fun start(context: Context) {
            val intent = Intent(context, EnforcementForegroundService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, EnforcementForegroundService::class.java))
        }
    }
}
