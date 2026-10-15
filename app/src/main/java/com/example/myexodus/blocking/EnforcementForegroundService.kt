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
import com.example.myexodus.BuildConfig
import com.example.myexodus.blocking.usage.EnforcementAction
import com.example.myexodus.blocking.usage.AccountingOutageSource
import com.example.myexodus.blocking.usage.GapRecoveryRequest
import com.example.myexodus.blocking.usage.UsageAccountingEngine
import com.example.myexodus.blocking.usage.UsageDiagnosticsRecorder
import com.example.myexodus.blocking.usage.UsageGapRepair
import com.example.myexodus.blocking.usage.UsageMark
import com.example.myexodus.blocking.usage.UsageSessionMachine
import com.example.myexodus.blocking.usage.latestResumedPackage
import com.example.myexodus.blocking.usage.usageEventPackage
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.entity.RestrictionRule
import com.example.myexodus.data.db.entity.UsageLedgerSource
import com.example.myexodus.settings.EnhancedModeSettings
import com.example.myexodus.telemetry.CrashReporting
import com.example.myexodus.telemetry.StartupTrace
import com.example.myexodus.ui.appLabel
import com.example.myexodus.ui.seedAppLabel
import dagger.hilt.android.AndroidEntryPoint
import java.time.LocalDate
import java.time.ZoneId
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

    private val startupTrace = StartupTrace("Fgs")

    @Inject lateinit var settings: EnhancedModeSettings
    @Inject lateinit var engine: BlockDecisionEngine
    @Inject lateinit var emergency: EmergencyExclusionManager
    @Inject lateinit var publisher: RulesSnapshotPublisher
    @Inject lateinit var status: ProtectionStatusStore
    @Inject lateinit var statusMonitor: ProtectionStatusMonitor
    @Inject lateinit var attempts: AttemptRecorder
    @Inject lateinit var usageEngine: UsageAccountingEngine
    @Inject lateinit var diagnostics: UsageDiagnosticsRecorder
    @Inject lateinit var outageSource: AccountingOutageSource
    // §8 Step 8's daily body, in-process: XOS never dispatches WorkManager (ADR #27), so the
    // scheduled job alone would leave the ledger's retention sweep unrun on the primary device.
    @Inject lateinit var maintenancePass: DailyMaintenancePass
    @Inject lateinit var storeFactory: AppRulesStoreFactory
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

    /** Previous tick's view of [ProtectionStatus.requiresExplicitReEnable]; its true→false edge
     *  is the pause release the credit-edge audit fires on (ADR #160). Ticks run strictly one
     *  at a time through [tickWithTimeout]'s Future, so a plain field suffices. */
    private var lastTickSawReenableRequired = false

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
        enterForeground()
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
        enterForeground()
        startupTrace.lap("foreground")
        start()
        startupTrace.lap("start")
        // START_STICKY: after a crash the system restarts us with a null intent, which is
        // exactly the §8.1 "startup (including after a crash) recompute from current state"
        // path — no stale-overlay detection exists or is attempted.
        return START_STICKY
    }

    /**
     * Every `startForegroundService()` call — including one on this existing service — re-arms
     * the contract to post a foreground notification within seconds; `onCreate` alone missed it
     * when a second start arrived while the service already ran (the app returning to the
     * foreground), and the platform killed the process with
     * `ForegroundServiceDidNotStartInTimeException` (X6532, 2026-09-19; reproduced: ON_START →
     * `refreshProtectionChecks` → `start()` → 30 s deadline → crash). Re-asserting per start
     * also repairs any state where the service lost its foreground status while still started.
     */
    private fun enterForeground() {
        startForeground(
            ProtectionNotifications.NOTIF_FGS,
            ProtectionNotifications.ongoing(this),
        )
    }

    private fun start() {
        if (!settings.enhancedModeEnabled) {
            stopSelf()
            return
        }
        checkForUserStop()
        // ADR #160: every interruption — death, stand-down, a heartbeat-fresh pipeline that
        // stopped crediting, an OEM freezer — ends at this start, and everything after the
        // ledger's own last credit is unaccounted time. Credit it from the platform's own
        // foreground record instead of letting the limit hold against a frozen counter.
        scope.launch { reconcileUncreditedSpan(processStartWall()) }
        // §8 Step 6: publish the current authority + status at service start (covers the
        // master switch being turned on/off and any permission change while stopped).
        statusMonitor.refresh()
        publisher.loadFastPath()
        scope.launch { publisher.loadFromDb() }
        pollingJob?.cancel()
        pollingJob = scope.launch { pollLoop() }
    }

    /** §8.2: a Task-Manager Stop since the last known-good startup is an enforcement-loss
     *  event — degraded state, blocking stays off until the user explicitly re-enables. The
     *  watermark drives only this detection; the credit-edge audit bounds itself at the last
     *  credit instead (ADR #160). */
    private fun checkForUserStop() {
        val watermark = status.lastKnownGoodExitCheckEpochMillis
        status.lastKnownGoodExitCheckEpochMillis = System.currentTimeMillis()
        val exitReasons = processExits()
        val candidates = UserStoppedDetector.userStopCandidates(exitReasons, watermark)
        if (UserStoppedDetector.wasUserStopped(exitReasons, watermark)) {
            // The description is logged because it is the discriminator the detector reads —
            // an unrecognized OEM shape shows up here instead of silently degrading protection.
            val seen = candidates.maxByOrNull { it.timestampEpochMillis }
            Log.w(
                TAG,
                "user stop detected — protection degraded, re-enable required " +
                    "(reason=${seen?.reason} desc=${seen?.description})",
            )
            // The flag only: the refresh below derives and publishes APP_STOPPED from it.
            status.requiresExplicitReEnable = true
        } else if (candidates.isNotEmpty()) {
            // ADR #156: would-be stops existed but every one was error-correlated (ANR/crash
            // storm). Without this line that era's kills are invisible in logcat.
            val seen = candidates.maxByOrNull { it.timestampEpochMillis }
            Log.w(
                TAG,
                "user-requested exits ignored — error-correlated with the app's own stop storm, " +
                    "not a deliberate pause (count=${candidates.size} desc=${seen?.description})",
            )
        }
    }

    /** The platform's process-exit record for this package; empty before API 30 (there is no
     *  exit-history surface) or when the query fails. Shared by the user-stop check and the
     *  credit-edge audit's pause clip — one read of the record, two judgments. */
    private fun processExits(): List<ProcessExit> = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        @Suppress("InlinedApi")
        getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(packageName, 0, 0)
            .map { ProcessExit(it.reason, it.timestamp, it.description.orEmpty()) }
    }.getOrDefault(emptyList())

    /**
     * ADR #160's credit-edge audit: from the ledger's own last credit per package to this
     * process start, everything is unaccounted time — death, stand-down, a pipeline still
     * heartbeating but no longer crediting, an OEM freezer; the interruption's shape does not
     * matter, only that the ledger stopped advancing. The platform's foreground record fills
     * the span ([UsageGapRepair.uncreditedWindow] bounds it: never before today's midnight or
     * this boot's origin, end-exclusive, floor-bounded).
     *
     * Supersedes the death-anchored repair (ADR #147/#156): its `latestExitAfter` anchor and
     * its observation clamp both pinned the window to journal evidence rows, and a heartbeat
     * row proves liveness, never a credit — the 2026-10-15 flagged era had a fresh heartbeat
     * baseline through it (repair credited 155.9 s of a 143.3 min platform-era) and an earlier
     * 4.4 h dead era never fired the repair at all (ADR #156 honest limit (c)). The
     * credit-edge anchor catches both by construction.
     *
     * The pause promise ("time limits stay paused until you re-enable") is enforced with the
     * same exit record [checkForUserStop] reads: no credit ever crosses a genuine user-stop
     * exit, and with the re-enable flag set and no stop record to bound the span, nothing is
     * credited at all — paused time can never re-enter the budget.
     *
     * Runs at every service start and at every pause release (the re-enable transition the
     * tick loop detects), on the same transactional writer every other credit uses, through
     * [UsageAccountingEngine.creditReconciledWindow]: platform-derived data is RECONCILIATION
     * (§8.9/R4), and its overlap check dedupes this path against the heartbeat-driven one and
     * against a re-run of itself. [endWallMs] is the moment live accounting resumes — the
     * process start at a service start, "now" at a pause release.
     */
    private suspend fun reconcileUncreditedSpan(endWallMs: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            if (!hasUsageAccess()) return
            val db = storeFactory.open() ?: return
            val tracked = db.restrictionRuleDao().getActiveRules()
                .filter { it.isEnabled && it.dailyLimitMs > 0L }
            if (tracked.isEmpty()) return
            val midnight = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val anchors = usageEngine.lastCreditWallPerPackage(
                tracked.map { it.blockedPackage },
                midnight,
            )
            // Binder work on the isolated tick pool: a usage-stats or appops call can wedge
            // indefinitely on this device (2026-08-30 finding, see [tickWithTimeout]), and this
            // is the one place outside the poll loop that makes such calls.
            val paused = status.requiresExplicitReEnable
            val probe = onBinderThread<ReconcileProbe?> {
                probeUncreditedWindows(tracked, anchors, midnight, paused, endWallMs)
            } ?: return
            if (probe.windows.isEmpty()) return
            val nowWall = System.currentTimeMillis()
            val nowElapsed = SystemClock.elapsedRealtime()
            // A real platform answer over the span (ADR #149's semantic, applied to this
            // path): whatever the span held was credited below or found empty — either way
            // the outage report must stop counting windows inside it.
            diagnostics.markResolvedThrough(nowWall)
            var repaired = 0
            for ((rule, window) in probe.windows) {
                val credit = UsageGapRepair.foregroundMsWithin(
                    probe.marks[rule.blockedPackage].orEmpty(),
                    window.first,
                    window.last + 1,
                )
                if (credit <= 0L) continue
                // §8.9/R4: platform-derived recovery is RECONCILIATION, never ACCOUNTING — and
                // its overlap check makes a re-run (or a second recovery path over the same
                // window, e.g. the heartbeat-driven one) credit nothing twice. Wall-to-elapsed
                // in one mapping, so the row's interval is the span the platform answered for.
                val credited = usageEngine.creditReconciledWindow(
                    rule.blockedPackage,
                    nowElapsed - (nowWall - window.first),
                    nowElapsed - (nowWall - (window.last + 1)),
                    credit,
                    UsageLedgerSource.MAINTENANCE,
                    // The row answers for the window, so its edge stamp is the window's end —
                    // never the write moment, which would advance the edge past a genuine stop
                    // still inside a flagged era (device leg 2, 2026-10-15).
                    answeredThroughWallMs = window.last + 1,
                )
                if (credited <= 0L) continue
                repaired++
                Log.i(
                    TAG,
                    "gap repair: credited ${credited}ms to ${rule.blockedPackage} " +
                        "over a ${window.last - window.first}ms uncredited window",
                )
            }
            if (repaired > 0) {
                // §14 / Decision 12: counts only — no package names or durations leave the device.
                crashReporting.nonFatal(
                    "accounting_gap_repaired",
                    cause = "uncredited_span",
                    "packages" to repaired.toString(),
                    "window_ms" to probe.widestMs.toString(),
                )
            }
        }.onFailure { Log.w(TAG, "gap repair skipped", it) }
    }

    /** The audit's per-package windows plus the one marks query that serves them all. */
    private data class ReconcileProbe(
        val windows: Map<RestrictionRule, LongRange>,
        val marks: Map<String, List<UsageMark>>,
        val widestMs: Long,
    )

    /** This process's wall-clock start — the end of the span a service-start audit repairs
     *  (live ticks can only ever cover time after the process existed). Fully qualified:
     *  a bare `Process` in this file resolves to java.lang.Process. */
    private fun processStartWall(): Long = System.currentTimeMillis() -
        (SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime())

    /**
     * The binder half of the audit. Per package the span runs from its credit edge to the
     * audit's end moment [endWallMs] — clipped to the latest genuine user-stop exit, never
     * past it, and skipped whole while the re-enable flag is set without a stop record to
     * bound the span. Null = nothing to repair.
     */
    private fun probeUncreditedWindows(
        tracked: List<RestrictionRule>,
        anchors: Map<String, Long>,
        midnightEpochMillis: Long,
        paused: Boolean,
        endWallMs: Long,
    ): ReconcileProbe? {
        val bootOriginWall = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        // Anchors are all ≥ midnight by their query's own floor, so midnight bounds the clip.
        val stopWall = UserStoppedDetector.latestUserStop(processExits(), midnightEpochMillis)
            ?.timestampEpochMillis
        if (paused && stopWall == null) return null
        val windows = tracked.mapNotNull { rule ->
            UsageGapRepair.uncreditedWindow(
                anchors[rule.blockedPackage],
                midnightEpochMillis,
                bootOriginWall,
                endWallMs,
                stopWallMs = stopWall,
                paused = paused,
            )?.let { rule to it }
        }.toMap()
        if (windows.isEmpty()) return null
        val marks = foregroundMarksByPackage(
            tracked.mapTo(HashSet()) { it.blockedPackage },
            windows.values.minOf { it.first },
            endWallMs,
        )
        return ReconcileProbe(windows, marks, windows.values.maxOf { it.last - it.first })
    }

    /** Runs [block] on the isolated tick pool under the tick's hard bound, so a wedged binder
     *  call abandons its thread instead of stalling the caller. Null = abandoned or failed;
     *  every caller treats that as "skip", never as a value. */
    private fun <T> onBinderThread(block: () -> T): T? = try {
        tickExecutor.submit(Callable { block() }).get(TICK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
    } catch (e: Exception) {
        Log.w(TAG, "binder call abandoned", e)
        null
    }

    /** Foreground marks per tracked package across [beginMillis, endMillis), one query for all
     *  of them; callers clip the sum to the window they are allowed to credit. */
    @SuppressLint("InlinedApi") // ACTIVITY_PAUSED is a compile-time int; ACTIVITY_RESUMED already used above
    private fun foregroundMarksByPackage(
        trackedPackages: Set<String>,
        beginMillis: Long,
        endMillis: Long,
    ): Map<String, List<UsageMark>> {
        if (beginMillis >= endMillis) return emptyMap()
        val events = runCatching { usageStats().queryEvents(beginMillis, endMillis) }.getOrNull()
            ?: return emptyMap()
        val marks = HashMap<String, MutableList<UsageMark>>()
        while (events.hasNextEvent()) {
            val event = UsageEvents.Event()
            if (!events.getNextEvent(event)) continue
            val resumed = when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> true
                UsageEvents.Event.ACTIVITY_PAUSED -> false
                else -> continue
            }
            val pkg = usageEventPackage(event) ?: continue
            if (pkg !in trackedPackages) continue
            marks.getOrPut(pkg) { mutableListOf() }.add(UsageMark(event.timeStamp, resumed))
        }
        return marks
    }

    /**
     * One §8.7 diagnostics cycle: the recorder judges the authoritative source's liveness
     * against the ledger, and when it finds a measurement gap this method does the one part
     * only the service can — ask the platform. Recovery is UsageStatsManager's own foreground
     * record, credited as RECONCILIATION (§8.9/R4); the corroboration outcome is fed back so
     * the prompt-eligibility flag is only ever set on a real platform answer.
     */
    private suspend fun diagnosticsPass() {
        val source = when (statusMonitor.authority.value) {
            ProtectionAuthority.FULL -> UsageLedgerSource.PATH_A
            ProtectionAuthority.DEGRADED_POLLING_ONLY -> UsageLedgerSource.PATH_B
            // No detection is not an outage to measure — nothing is expected to be accounting.
            else -> return
        }
        val db = storeFactory.open() ?: return
        // The recorder's gate (2026-10-03): a pipeline with no enabled daily-limit rule has
        // nothing to account for, so its silence must never be recorded — or surfaced — as an
        // outage. Computed here because only the service holds the store; a null request below
        // therefore guarantees this list is non-empty.
        val tracked = db.restrictionRuleDao().getActiveRules()
            .filter { it.isEnabled && it.dailyLimitMs > 0L }
        val hasAccess = hasUsageAccess()
        // ADR #149's one-time upgrade seed: with platform access present, the pre-fix backlog
        // stops being reported; each fresh gap is then resolved through recordRecovery below.
        if (hasAccess) diagnostics.seedResolvedThrough(System.currentTimeMillis())
        // The pass moment is also the repaired window's wall end — the credit row stamps its
        // edge at this value, never at the (later) write moment.
        val passWallMs = System.currentTimeMillis()
        val request = diagnostics.pass(
            source,
            systemBootCount(this),
            SystemClock.elapsedRealtime(),
            passWallMs,
            trackingActive = tracked.isNotEmpty(),
        ) ?: return
        if (!hasAccess) {
            // §8.9: without the permission there is nothing to query and nothing to conclude —
            // the gap stands recorded, and the window is never escalated as corroborated.
            crashReporting.nonFatal("accounting_gap_unrecoverable", cause = "no_permission")
            return
        }
        // ADR #160's pause guard: while the explicit-re-enable flag is set, the platform's
        // foreground record cannot tell a deliberately paused era from a false flag — and a
        // credit here (the 2026-10-15 shape: a death-sliver repair six seconds before the
        // re-enable) advances the credit edge past the whole flag-voided era, sealing usage
        // that belongs in the budget. No recovery may credit while paused; the gap stands
        // recorded, and the credit-edge audit closes the era when the flag clears.
        if (status.requiresExplicitReEnable) {
            Log.w(TAG, "gap recovery deferred: explicit re-enable required")
            crashReporting.nonFatal("accounting_gap_unrecoverable", cause = "reenable_required")
            return
        }
        val clipEnd = request.window.last + 1
        val marks = onBinderThread<Map<String, List<UsageMark>>> {
            foregroundMarksByPackage(
                tracked.mapTo(HashSet()) { it.blockedPackage },
                request.window.first,
                clipEnd,
            )
        } ?: return
        var recoverableMs = 0L
        var creditedMs = 0L
        var packages = 0
        for (rule in tracked) {
            val foreground = UsageGapRepair.foregroundMsWithin(
                marks[rule.blockedPackage].orEmpty(),
                request.window.first,
                clipEnd,
            )
            if (foreground <= 0L) continue
            recoverableMs += foreground
            creditedMs += usageEngine.creditReconciledWindow(
                rule.blockedPackage,
                request.elapsedFromMs,
                request.elapsedToMs,
                foreground,
                UsageLedgerSource.MAINTENANCE,
                answeredThroughWallMs = passWallMs,
            )
            packages++
        }
        diagnostics.recordRecovery(
            request.gapSpanMs,
            recoverableMs,
            System.currentTimeMillis(),
            bootBoundary = request.bootBoundary,
        )
        if (packages > 0) {
            // §14 / Decision 12: counts and durations only — no package names leave the device.
            crashReporting.nonFatal(
                "accounting_gap_reconciled",
                cause = "measurement_gap",
                "packages" to packages.toString(),
                "foreground_ms" to recoverableMs.toString(),
                "credited_ms" to creditedMs.toString(),
            )
        }
    }

    /**
     * D1's notification surface (§8.7): post the measured interruption while the exemption
     * offer is live, clear it otherwise. The offer's own gate is [AccountingOutageSource]'s —
     * this method only translates its answer into the notification's post/cancel.
     */
    private suspend fun syncOutageNotification() {
        val outage = outageSource.current()
        ProtectionNotifications.updateOutage(
            this,
            outage?.takeIf { it.promptRoute != null }?.summary?.largestWindowMs,
        )
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
        val reenableRequired = status.requiresExplicitReEnable
        // ADR #160's pause release: the flag's true→false edge is the user's explicit "count
        // again" — the flagged era ends at this moment, and the credit-edge audit closes it
        // with end = now. A genuine pause needs no special case: its stop clip makes the era
        // credit nothing (the audit's own guard covers the flag-outlives-a-tick race, and a
        // release missed here is still healed at the next service start).
        if (lastTickSawReenableRequired && !reenableRequired) {
            Log.i(TAG, "re-enable requirement cleared — running credit-edge audit")
            val releasedAtWall = System.currentTimeMillis()
            scope.launch {
                runCatching { reconcileUncreditedSpan(releasedAtWall) }
                    .onFailure { Log.w(TAG, "re-enable audit failed", it) }
            }
        }
        lastTickSawReenableRequired = reenableRequired
        // Usage access revoked means queryEvents is impossible; APP_STOPPED means the user
        // stopped the app and blocking stays off until they explicitly re-enable. Either way
        // the scan is skipped — the status itself is §8 Step 6's job (statusMonitor below).
        if (usageOk && !reenableRequired) {
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
            // Ledger gap diagnostics (§8.7) ride the same 30 s cadence: off the tick thread, so
            // the heartbeat's database work can never eat into the tick's hard bound. The
            // outage notification is synced right after — it is the same pass's outcome.
            scope.launch {
                runCatching { diagnosticsPass() }
                    .onFailure { Log.w(TAG, "diagnostics pass failed", it) }
                runCatching { syncOutageNotification() }
                    .onFailure { Log.w(TAG, "outage notification sync failed", it) }
                // Self-guarded to one run a day: this cycle is the trigger of record on
                // devices where the scheduled job never dispatches (ADR #27).
                runCatching { maintenancePass.runIfDue() }
                    .onFailure { Log.w(TAG, "daily maintenance pass failed", it) }
            }
        }
        return lastQueryEndMillis
    }

    private fun usageStats(): UsageStatsManager = getSystemService(UsageStatsManager::class.java)

    private fun launchBlocker(outcome: AppBlockDecision.Launch) {
        // Path B (and any launch without a preceding cover pass) has no other sync resolve:
        // seed the shared label cache here so the screen's title never flashes the raw
        // package identifier on a cold process (task #4). Runs on the tick worker, not main.
        seedAppLabel(outcome.packageName, appLabel(this, outcome.packageName))
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
        if (BuildConfig.DEBUG && latest != null) {
            Log.i(TAG, "poll foreground=$latest carried=$lastObservedForegroundPackage")
        }
        // ADR #107: mirror the poll's platform-foreground knowledge for Path A's foreign-close
        // confirmation — every tick re-stamps it, so a dead or wedged FGS reads as a stale
        // oracle and never blocks a close.
        notePlatformForeground(lastObservedForegroundPackage, SystemClock.elapsedRealtime())
        val wall = (usageOverlay.currentContent(Display.DEFAULT_DISPLAY) as? OverlayContent.DailyLimitBlocked)
            ?.packageName
        if (wall != null && shouldHideWall(wall, latest)) {
            mainHandler.post {
                // Re-checked at run time: a queued hide whose foreground returned to the
                // walled app must not lift a live wall (the re-arm gap).
                if (lastObservedForegroundPackage != wall) {
                    usageOverlay.hide(usageStateMachine.currentGeneration, Display.DEFAULT_DISPLAY)
                    killWalledProcess(wall)
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

    /** Path B's half of the user's "block the app and close it" ruling (2026-09-27): it holds
     *  no accessibility token, so there is no HOME eviction (Decision 16 — a background
     *  Activity launch is the reliability risk that decision exists to avoid). The app is
     *  killed once the user's own Home press has backgrounded it — `killBackgroundProcesses`
     *  is a no-op against a foreground app, and an app holding services survives it either
     *  way (the same ceiling ADR #93 records for Path A). */
    private fun killWalledProcess(packageName: String) {
        runCatching {
            (getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
                ?.killBackgroundProcesses(packageName)
            Log.i(TAG, "wall close pkg=$packageName")
        }.onFailure { Log.w(TAG, "wall close failed pkg=$packageName", it) }
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
        // Same mid-session re-check as Path A: an edit that removes the limit (or pauses the
        // rule) between session-open and tick must stop crediting — the engine treats a limit
        // of 0 as "already at limit" and would wall a rule that no longer has one.
        if (!isLimitTracked(tick.packageName)) return
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
                    tick.packageName,
                    tick.deltaMs,
                    SystemClock.elapsedRealtime(),
                    UsageLedgerSource.PATH_B,
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
                    OverlayContent.Warning(packageName, action.level, action.remainingMs),
                ) {
                    // Delivery confirmed only once the banner actually attached (§9: a warning
                    // that failed to attach stays PENDING_DELIVERY / Decision 14).
                    scope.launch { runCatching { usageEngine.markWarningDelivered(packageName, action.level) } }
                }
            }
            is EnforcementAction.Block -> {
                // The wall replaces the session: nothing accrues under a covered app. No
                // eviction here (Decision 16): Path B holds no accessibility token, so
                // GLOBAL_ACTION_HOME is unavailable and a background Activity launch is the
                // reliability risk that decision exists to avoid — the wall itself is the
                // block, FR2's non-evictable fallback. The blocked app is closed by [killWalledProcess]
                // once the user's own Home press backgrounds it and the wall lifts.
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

    /** A tracked package = an enabled rule with a real limit, on a day the rule covers —
     *  mirrors BlockDecisionEngine Rule 2's `dailyLimitMs > 0` gate plus the rule's day scope
     *  (a rule not active today never accrues, so it must not open a session). Read off the
     *  cached snapshot: no DB I/O on the poll path. */
    private fun isLimitTracked(packageName: String): Boolean {
        val rule = publisher.snapshot.appRules[packageName] ?: return false
        return rule.dailyLimitMs > 0L && dayScopeCoversToday(rule.activeDaysOfWeek, rule.timezone)
    }

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
        // ADR #107: a stopped service has no platform knowledge — clear the oracle so Path A
        // reads "stale", never a frozen keep.
        notePlatformForeground(null, SystemClock.elapsedRealtime())
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

        /** Path A's foreign-close oracle (ADR #107, 2026-09-28): the poll's last-known
         *  platform foreground plus the tick that observed it, read by
         *  [BlockerAccessibilityService] in the same process (no binder). Written on every
         *  poll tick, so a stopped service leaves a stale timestamp — never a stale keep.
         *  Read directly (companion property); write via [notePlatformForeground]. */
        @Volatile
        var platformForegroundPackage: String? = null
            private set

        @Volatile
        var platformForegroundObservedAtElapsedMs: Long = 0L
            private set

        fun notePlatformForeground(packageName: String?, nowElapsedMs: Long) {
            platformForegroundPackage = packageName
            platformForegroundObservedAtElapsedMs = nowElapsedMs
        }

        /** Package the blocker is currently covering; null when none. Cleared in
         *  BlockerActivity.onDestroy; the poll skips relaunching while it is set. */
        @Volatile
        var activeBlockTarget: String? = null

        fun start(context: Context) {
            val intent = Intent(context, EnforcementForegroundService::class.java)
            // A kill-woken process (WM tick, widget backstop) starts this from the background:
            // Android 12+ refuses that (ForegroundServiceStartNotAllowedException) unless the
            // app is battery-exempt or holds SYSTEM_ALERT_WINDOW — both true on the X6532. On
            // an OEM missing both, degrade honestly: the D1 surfaces report the authority,
            // this log names the refusal. Contained here once for every caller (ADR #152).
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "FGS start refused by the platform", e)
            }
        }

        /** ADR #152's process-start re-arm (called from MyExodusApp.onCreate on every cold
         *  start). The enablement gate reads its own [EnhancedModeSettings] — this runs
         *  before any service instance exists to carry the injected one. */
        fun ensureOnProcessStart(context: Context) {
            if (!EnhancedModeSettings(context).enhancedModeEnabled) return
            start(context)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, EnforcementForegroundService::class.java))
        }
    }
}
