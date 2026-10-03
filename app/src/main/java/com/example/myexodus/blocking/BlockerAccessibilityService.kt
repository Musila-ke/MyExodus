package com.example.myexodus.blocking

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.net.toUri
import com.example.myexodus.BuildConfig
import com.example.myexodus.MainActivity
import com.example.myexodus.blocking.recovery.RecoveryStateStore
import com.example.myexodus.blocking.usage.EnforcementAction
import com.example.myexodus.blocking.usage.UsageAccountingEngine
import com.example.myexodus.blocking.usage.UsageSessionMachine
import com.example.myexodus.data.db.entity.RecoverySession
import com.example.myexodus.data.db.entity.UsageLedgerSource
import com.example.myexodus.settings.EnhancedModeSettings
import com.example.myexodus.ui.appLabel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
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
 * Enhanced Mode blocking engine (spec §8 Steps 4–8, Data Flow §6): every event runs
 * emergency-exclusion first (fail-open), then normalizer + monotonic generation, then the
 * decision engine. Blocks show the per-display overlay; allows hide it. Content-changed events
 * on discovered browsers (toolbar view-ID probe, [BrowserUrlScanner.viewIdFor]) feed the
 * debounced URL scanner. Latency stages are recorded
 * per Architecture Decision 11. Null/empty packages are treated as unknown immediately and
 * hide the overlay — no retain window (stale-event protection is the generation check, §9).
 */
@AndroidEntryPoint
class BlockerAccessibilityService : AccessibilityService() {

    @Inject lateinit var emergency: EmergencyExclusionManager
    @Inject lateinit var engine: BlockDecisionEngine
    @Inject lateinit var profiler: LatencyProfiler
    @Inject lateinit var publisher: RulesSnapshotPublisher
    @Inject lateinit var settings: EnhancedModeSettings
    @Inject lateinit var monitor: EnhancedModeHealthMonitor
    @Inject lateinit var statusMonitor: ProtectionStatusMonitor
    @Inject lateinit var recoveryStore: RecoveryStateStore
    @Inject lateinit var attempts: AttemptRecorder
    @Inject lateinit var usageEngine: UsageAccountingEngine

    /** Per-bind foreground state + overlay (plan §12: recreated on re-bind; app-scoped
     *  singletons would carry stale generation/overlay state across a Hiber disconnect —
     *  caught 2026-08-28 review: the plan records service-scoped recreation, the DI graph
     *  provided @Singleton copies). */
    private val stateMachine = ForegroundStateMachine()
    internal lateinit var overlay: OverlayController

    /** Path A's usage session (enforcement plan v2 §8 Step 4): one tracked app at a time,
     *  fed by confirmed foreground transitions; the ticker below turns it into incremental
     *  deltas. Per-bind state, like the overlay. */
    private val usageSessions = UsageSessionMachine()
    private var usageTickJob: Job? = null
    private var pendingForeignCloseRecheck: Runnable? = null

    private val normalizer = AccessibilityEventNormalizer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var dialerReceiver: BroadcastReceiver? = null
    private var screenOffReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        overlay = OverlayController(this, stateMachine, profiler, ::openRecovery, ::openDifferentSite)
    }

    /** Display of the most recent scan request, consumed by [handleUrlResult]. Main-thread
     *  only (accessibility dispatch and the scanner's handler post there), and the scanner
     *  holds one pending task at a time — a newer request is either a newer generation
     *  (older result discarded) or supersedes the older task — so one slot is enough. The
     *  map it replaces leaked an entry per superseded scan. */
    private var scanGeneration = -1L
    private var scanDisplayId = Display.DEFAULT_DISPLAY

    // Lazy: Hilt injects @Inject fields in onCreate, but field initializers run in the
    // constructor — a direct read here crashes service creation (caught on-device 2026-08-26).
    private val scanner by lazy {
        BrowserUrlScanner(
            engine = this.engine,
            stateMachine = this.stateMachine,
            onResult = ::handleUrlResult,
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (BuildConfig.DEBUG) {
            Log.i(TAG, "config change: newWidthDp=${newConfig.screenWidthDp} serviceWidthDp=${resources.configuration.screenWidthDp}")
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected.set(true)
        instance = this
        emergency.refresh()
        // Plan §8 Step 12: AAPM check on connect (plus every health-check tick in the worker).
        monitor.refresh()
        // §8 Step 6: connect/disconnect flips the accounting authority — publish immediately.
        statusMonitor.refresh()
        overlay.prewarm()
        registerDialerReceiver()
        registerScreenOffReceiver()
        // Fast-path cache synchronously, authoritative load async (plan §8 Steps 2/9) — the
        // engine always decides against some snapshot, never against "no rules".
        publisher.loadFastPath()
        // §8 Step 4 connect-time usage re-acquire (see the function doc).
        reacquireUsageSessionOnConnect()
        scope.launch { publisher.loadFromDb() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val detectionNanos = SystemClock.elapsedRealtimeNanos()
        val packageName = event.packageName?.toString()
        // getWindows() is a binder round-trip per call — only WSC decisions need the window;
        // content-changed events resolve their display from the event itself (API 24+).
        // XOS WSC storms and high-frequency content-changed events otherwise hammer the
        // window list on every event (review finding, fixed in this pass).
        val window = if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            windowFor(event.windowId)
        } else {
            null
        }
        val displayId = if (window != null) {
            window.displayIdOrDefault()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // AccessibilityRecord.getDisplayId is API 33+; below that non-WSC events
            // resolve to the default display (the event's own API 24 claim was wrong —
            // lint caught it).
            runCatching { event.displayId }.getOrDefault(Display.DEFAULT_DISPLAY)
        } else {
            Display.DEFAULT_DISPLAY
        }
        // The service's own overlay windows emit window-state-changed events back to the
        // service; deciding them (Allow — our package is never a restricted target) would
        // hide the overlay it just showed (caught on-device 2026-08-27). On stock the
        // overlay window is TYPE_ACCESSIBILITY_OVERLAY; XOS misreports it as TYPE_SYSTEM
        // (observed 2026-08-27), so the guard also skips any own-package window that is not
        // an application window — our activities are the only TYPE_APPLICATION windows we
        // own. (An OEM misreporting the overlay as TYPE_APPLICATION would regress this;
        // the §12 device matrix covers that.)
        if (window?.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) return
        if (packageName == this.packageName && window?.type != null &&
            window.type != AccessibilityWindowInfo.TYPE_APPLICATION
        ) {
            return
        }
        // Debug-only event log: also the device harness's readiness probe (XOS can leave a
        // bound service with dead event routing; the script recycles until events flow).
        if (BuildConfig.DEBUG) {
            Log.i(TAG, "event pkg=${packageName ?: "null"} type=${event.eventType} winId=${event.windowId} display=$displayId winType=${window?.type ?: -1}")
        }

        // App-side master switch (plan §8 Step 11): off → the pipeline does nothing at all.
        // Warm SharedPreferences read; no database I/O on the hot path (§11).
        if (!settings.enhancedModeEnabled) {
            overlay.hide(stateMachine.currentGeneration, displayId)
            // Deliberate uncredited drop (enforcement plan v2 §8 Step 4): never an over-count.
            usageSessions.reset()
            usageTickJob?.cancel()
            usageTickJob = null
            return
        }
        // Data Flow step 3: emergency exclusion first, fail-open on protected packages — but
        // gated on the active foreground window: a background flap from a protected package
        // (the dialer's stale null-window WSCs) must not tear down the overlay over a
        // still-restricted app (caught on-device 2026-08-28 — mid-tapjacking hide).
        if (emergency.isProtected(packageName, window) && isActiveForeground(packageName, window)) {
            overlay.hide(stateMachine.currentGeneration, displayId)
            // A protected package (dialer) is untracked: the session for the app it replaced
            // closes with its final tick (enforcement plan v2 §6 step 4). Same foreign-close
            // confirmation as Path A's decisions (ADR #107): XOS reports the notification
            // shade as a TYPE_SYSTEM window of com.android.systemui, so this branch used to
            // end a live session on every shade pull even though the platform never left the
            // tracked app (device-caught 2026-09-28 on the X6532 — the 07:44 gap and the
            // "7 interruptions" report; the emulator's shade never reproduced it because
            // AOSP reports a non-system window type and took the deferred decision path).
            // A surface that really took over (dialer, call UI) stops confirming within one
            // 1.5 s poll and closes on the re-check.
            val active = usageSessions.activePackage
            val oracleAgeMs = SystemClock.elapsedRealtime() -
                EnforcementForegroundService.platformForegroundObservedAtElapsedMs
            if (packageName != null && active != null && active != packageName && shouldDeferForeignClose(
                    activePackage = active,
                    incomingPackage = packageName,
                    oraclePackage = EnforcementForegroundService.platformForegroundPackage,
                    oracleAgeMs = oracleAgeMs,
                )
            ) {
                if (BuildConfig.DEBUG) {
                    Log.i(TAG, "foreign close deferred active=$active incoming=$packageName")
                }
                scheduleForeignCloseRecheck(active, packageName, displayId, scheduleBlocked = false)
            } else {
                closeUsageSession()
            }
            return
        }
        // Step 4: unknown package → immediate hide, no retain window.
        if (packageName.isNullOrEmpty()) {
            overlay.hide(stateMachine.currentGeneration, displayId)
            return
        }

        // The scene's target re-foregrounding under a live persistent scene goes straight
        // back out: covering is not stopping (device-caught 2026-09-27 — Instagram came back
        // RESUMED and audible under the wall; the app-block card has the same exposure now
        // that it persists). This must run before the window-list machinery below: while the
        // scene covers the app, XOS reports the transition with a window it no longer lists,
        // so the deferred re-check dropped the event and no decide ever ran (device-caught,
        // same session). The scene's own existence is the evidence — it is only up while its
        // package was pushed out — so no window validation is needed, or possible, here.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            reevictIfScene(packageName, displayId)
        ) {
            return
        }

        val normalized = normalizer.normalize(event)

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (window == null) {
                    // WSC whose window is not in the live list: either a stale/invisible
                    // event (keyguard removal after dismissal, background notification
                    // windows flapping — Zoom observed 2026-08-27 — none ever reappear) or
                    // a delayed entry (XOS window-list lag — the window appears moments
                    // later). Neither decided nor a transition at arrival: deciding would
                    // hide an overlay covering a still-restricted foreground app or fail
                    // to show one over the new one, and advancing the generation would
                    // discard an in-flight URL scan (both caught on-device 2026-08-27).
                    // Re-check the list once after a short delay and decide only if the
                    // window really appeared and is the active foreground.
                    mainHandler.postDelayed(
                        { deferredWsc(packageName, event.windowId, detectionNanos) },
                        DEFERRED_WSC_MS,
                    )
                    return
                }
                // Only the active foreground window may drive a decision. Background
                // interactive windows (IME, bubbles, PiP) emit WSC too; deciding Allow
                // for them would hide the overlay over a still-restricted foreground app
                // (bypass, caught 2026-08-28 review). isActive is per-display (multi-window
                // correct); rootInActiveWindow covers XOS lag. Non-foreground events get
                // the same deferred re-check as null-window events, which drops them when
                // they never become active.
                if (!isActiveForeground(packageName, window)) {
                    mainHandler.postDelayed(
                        { deferredWsc(packageName, event.windowId, detectionNanos) },
                        DEFERRED_WSC_MS,
                    )
                    return
                }
                val generation = stateMachine.advance(packageName, normalized.windowId, detectionNanos)
                decideApp(packageName, generation, detectionNanos, window)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Own-package content changes never decide (our app is never a restricted
                // target or a browser) and the window list isn't consulted for
                // non-WSC events — skip outright instead of risking the master-switch/emergency
                // hides on our own overlay's content-changed events. One exception: our own
                // ACTIVITY window drawing while a card is up means the durable block screen
                // (or a recovery screen) is now the foreground — the bridge's job is done.
                // Needed because the activity's WSC can turn out to be the starting window's
                // only (dead id; emulator 2026-09-13: BlockerActivity's real window arrived as
                // content-changed alone, so nothing ever lifted the cover off it). The
                // overlay's own churn cannot match: its window is never TYPE_APPLICATION
                // (same invariant as the WSC own-cover guard above).
                if (packageName == this.packageName) {
                    if (overlay.isShown(displayId) || usageSessions.activePackage != null) {
                        val own = windowFor(event.windowId)
                        if (own?.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                            isActiveForeground(packageName, own)
                        ) {
                            overlay.hide(stateMachine.currentGeneration, displayId)
                            // Our own UI in front means the tracked app is gone: close the
                            // session (the tracked app behind a lifted card accrues nothing).
                            closeUsageSession()
                        }
                    }
                    return@onAccessibilityEvent
                }
                // App-level restriction already covers this window: a URL-scan Allow would
                // hide the app-level overlay over the still-restricted browser (bypass,
                // caught 2026-08-28 review). URL rules govern only while the app itself
                // is not blocked.
                if (engine.decideApp(packageName, System.currentTimeMillis()) is BlockDecision.Block) {
                    return@onAccessibilityEvent
                }
                // Browser discovery replaces the package allowlist (user-raised 2026-09-13:
                // real devices carry arbitrary browsers): a known toolbar view-ID in this
                // package's own tree marks it a browser. Non-browsers bail before the
                // generation advance — their content churn must not discard in-flight scans.
                // Provider lambda: on memo hits no tree is read (binder round-trip per event).
                val viewId = scanner.viewIdFor(packageName) { rootInActiveWindow }
                    ?: return@onAccessibilityEvent
                val generation = stateMachine.advance(packageName, normalized.windowId, detectionNanos)
                scanGeneration = generation
                scanDisplayId = displayId
                // Root resolved at scan fire time: the event's source node is the page
                // subtree, which never contains the toolbar's address bar (caught
                // on-device 2026-08-27 — scans came back empty until resolved at fire time).
                scanner.onContentChanged(packageName, viewId, { rootInActiveWindow }, generation)
            }
            // All other event types are ignored by the engine (typed fall-through).
            else -> Unit
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        connected.set(false)
        instance = null
        // §8 Step 6: authority flips to Path B (or to no-detection) the moment this service
        // goes away — publish before anything else can read a stale FULL.
        runCatching { statusMonitor.refresh() }
        usageTickJob?.cancel()
        usageTickJob = null
        // Handoff A→B (device QA 2026-09-15): the ticker dies with this service, and Path B
        // anchors its re-acquired session at its own poll-now — it can never credit the
        // [last tick -> destroy] tail, so the final credit must land here. Blocking write:
        // scope.cancel() below would otherwise cancel an async one mid-flight.
        val finalTick = usageSessions.close(SystemClock.elapsedRealtime())
        mainHandler.removeCallbacksAndMessages(null)
        dialerReceiver?.let { runCatching { unregisterReceiver(it) } }
        dialerReceiver = null
        screenOffReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenOffReceiver = null
        scanner.cancelPending()
        overlay.detachAll()
        scope.cancel()
        if (finalTick != null) {
            runBlocking {
                runCatching {
                    usageEngine.processUsageObservation(
                        finalTick.packageName,
                        finalTick.deltaMs,
                        SystemClock.elapsedRealtime(),
                        UsageLedgerSource.PATH_A,
                    )
                }.onFailure { Log.w(TAG, "handoff final credit failed pkg=${finalTick.packageName}", it) }
            }
        }
        super.onDestroy()
    }

    private fun decideApp(
        packageName: String,
        generation: Long,
        detectionNanos: Long,
        window: AccessibilityWindowInfo?,
    ) {
        val displayId = window.displayIdOrDefault()
        val decisionTimeNanos = SystemClock.elapsedRealtimeNanos()
        profiler.recordDecision(generation, detectionNanos, decisionTimeNanos)
        val decision = engine.decideApp(packageName, System.currentTimeMillis())
        if (BuildConfig.DEBUG) {
            Log.i(TAG, "decide $packageName gen=$generation -> ${decision.javaClass.simpleName}")
        }
        // Every confirmed foreground transition feeds the usage session machine — also the
        // Block case, where a session opened for the walled app must close with its final
        // tick (§6 step 4) and no session may exist under a wall.
        syncUsageSession(packageName, displayId, scheduleBlocked = decision is BlockDecision.Block)
        when (decision) {
            is BlockDecision.Block -> {
                // Content is built only on a transition: an already-shown overlay's show()
                // only re-bounds, and the label lookup (PackageManager round-trip) must not
                // run per WSC storm event. Display bounds, not the deciding window's: a cold
                // launch can fire the blocked app's sub-window (loading/transient dialog)
                // first, and the then-frozen cover left the cover at that sub-window's size —
                // AnkiDroid rendered readable around a 684x563 card (2026-09-13). The cover
                // starts at the app's whole surface; split-screen pane coverage is the
                // largest-window re-bound in blockOtherRestrictedWindows.
                if (!overlay.isShown(displayId)) {
                    coverBlockedApp(generation, displayId, packageName)
                    // XOS measures the *animating* task for a moment after an app switch, so a
                    // cover shown mid-animation lands at the animation's rect with the app's
                    // keys tappable around it (device-caught 2026-09-16: 559x1242 at (80,179)
                    // when Calculator came to the front — the wall reported `shown` and stayed
                    // short). A single fixed-delay re-run assumed that "a moment" has one length;
                    // the animation can outlast it. Re-sample until two consecutive samples agree
                    // on the target rect, capped, skipped whenever the cover is gone.
                    settleCover(generation, displayId, packageName, previous = null, attempt = 1)
                }
            }
            is BlockDecision.Allow -> {
                // A browser WSC (page load, tab switch) carries no URL evidence — hiding a
                // URL card on it lifted the block with the blocked page still on screen
                // (device-reproduced 8/8, 2026-09-13). Re-ask the address bar instead: the
                // scan is the single decider for URL cards, so evidence decides, not arrival
                // order. Other cards (app block lifted) still hide here. The browser check
                // sits behind the content check so the common case — no card, plain app
                // switch — never probes; with a card up, switching to an unclassified
                // package costs one probe (memoized; failed probes retried every 10 s).
                val current = overlay.currentContent(displayId)
                val barId = if (current is OverlayContent.UrlBlocked) {
                    scanner.viewIdFor(packageName) { rootInActiveWindow }
                } else {
                    null
                }
                // A daily-limit wall is persistent (user ruling 2026-09-27): no foreground
                // change lifts it — not the walled app's own WSC churn, and not the
                // launcher coming forward after the wall's eviction. Only its Close button
                // (the Accessibility host's, the one that can evict) dismisses it. If the
                // walled app itself re-foregrounds under a live wall — notification tap,
                // recents — re-run the eviction: covering is not stopping (device-caught
                // 2026-09-27: Instagram stayed RESUMED and audible under the wall).
                val wallScene = current as? OverlayContent.DailyLimitBlocked
                // The app-block card persists too (owner ruling 2026-10-03, same as the
                // wall): this is what used to make the on-schedule block screen a split-
                // second flash — the eviction's own HOME fired, the launcher Allow arrived,
                // and this branch hid the card. A foreign foreground (launcher, other apps)
                // must never lift it now.
                val appBlockScene = current as? OverlayContent.AppBlocked
                // A live warning banner owns its 5s window: the banner's own appearance
                // makes the app below re-emit window-state changes, and deciding Allow on
                // them hid the banner ~5ms after attach — the user saw a flash, yet
                // attach-confirm had already marked the warning delivered, so it never
                // retried (device-caught 2026-09-27 on the X6532). A different package's
                // Allow still yields it (the banner follows the foreground).
                val warningHere =
                    (current as? OverlayContent.Warning)?.packageName == packageName
                if (barId != null) {
                    scanGeneration = generation
                    scanDisplayId = displayId
                    scanner.onContentChanged(packageName, barId, { rootInActiveWindow }, generation)
                } else if (wallScene != null) {
                    if (wallScene.packageName == packageName) closeBlockedApp(packageName)
                } else if (appBlockScene != null) {
                    if (packageName == this.packageName) {
                        // Our own screen (the durable BlockerActivity landing, the recovery
                        // flow) takes over the display — the bridge's job is done.
                        overlay.hide(generation, displayId)
                    } else if (appBlockScene.packageName == packageName) {
                        // The card's target re-foregrounding under it goes straight back
                        // out (only reachable via the deferred re-check; the direct WSC
                        // path re-evicted pre-decide).
                        closeBlockedApp(packageName)
                    }
                } else if (warningHere) {
                    Unit
                } else if (!overlay.isShown(displayId) || !restrictedPaneBeside(displayId, window)) {
                    overlay.hide(generation, displayId)
                }
            }
        }
    }

    // --- Daily-usage sessions (enforcement plan v2 §8 Step 4, §6 steps 2–4) ----------------

    /** Connect-time usage re-acquire (user-ruled 2026-09-15, Open Product Decisions #10; beyond
     *  the plan's §8 Step 4 text, which opens sessions only on window-state transitions). A
     *  bind while a tracked app is already foreground emits no event for that app, so without
     *  this the session opens only at the next transition — the [bind → next event] span goes
     *  uncredited under FULL authority. Mirrors Path B's authority-regained lookback: the
     *  session anchors at connect-now, never at an earlier observation time — the pre-connect
     *  span belongs to whichever path held authority before, and double-crediting is worse
     *  than a visible under-count. rootInActiveWindow is the decisive signal (same rationale
     *  as [deferredWsc] — XOS window-list lag); bounded retries cover a just-connected
     *  service whose window list is not populated yet. */
    private fun reacquireUsageSessionOnConnect(attempt: Int = 0) {
        if (!settings.enhancedModeEnabled) return
        val packageName = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
        if (packageName.isNullOrEmpty() || packageName == this.packageName) {
            if (attempt < MAX_CONNECT_REACQUIRE_ATTEMPTS) {
                mainHandler.postDelayed(
                    { reacquireUsageSessionOnConnect(attempt + 1) },
                    DEFERRED_WSC_MS,
                )
            }
            return
        }
        if (usageSessions.activePackage == packageName) return
        if (emergency.isProtected(packageName, null)) return
        if (!isLimitTracked(packageName)) return
        val displayId = allWindows().firstOrNull { runCatching { it.isActive }.getOrDefault(false) }
            ?.displayIdOrDefault() ?: Display.DEFAULT_DISPLAY
        val scheduleBlocked =
            engine.decideApp(packageName, System.currentTimeMillis()) is BlockDecision.Block
        if (BuildConfig.DEBUG) {
            Log.i(TAG, "connect re-acquire pkg=$packageName scheduleBlocked=$scheduleBlocked")
        }
        syncUsageSession(packageName, displayId, scheduleBlocked)
    }

    /** Feeds one confirmed foreground transition into the session machine and mirrors the
     *  result into the accounting engine. [scheduleBlocked] = this decide ruled the app walled
     *  by its schedule; a limit card already up for the same package walls it too — neither
     *  may have a session (covered time must not accrue as usage). */
    private fun syncUsageSession(packageName: String, displayId: Int, scheduleBlocked: Boolean) {
        // Foreign-transition confirmation (ADR #107, 2026-09-28): a transient surface (shade,
        // heads-up, system window) that never pauses the tracked app still emits a WSC that
        // used to close the session with no reopen until the app's next real WSC — 97 s of
        // platform-credited time lost in 2026-09-28's 07:34:35-08:09:29 interval (6 gaps of
        // 7-34 s inside one continuous USM interval). When Path B's fresh poll still reports
        // the tracked app as the platform foreground, defer the close and re-verify below.
        val active = usageSessions.activePackage
        val oraclePackage = EnforcementForegroundService.platformForegroundPackage
        val oracleAgeMs = SystemClock.elapsedRealtime() -
            EnforcementForegroundService.platformForegroundObservedAtElapsedMs
        if (BuildConfig.DEBUG && active != null && active != packageName) {
            Log.i(
                TAG,
                "foreign transition active=$active incoming=$packageName " +
                    "oracle=$oraclePackage ageMs=$oracleAgeMs",
            )
        }
        if (active != null && shouldDeferForeignClose(
                activePackage = active,
                incomingPackage = packageName,
                oraclePackage = oraclePackage,
                oracleAgeMs = oracleAgeMs,
            )
        ) {
            if (BuildConfig.DEBUG) {
                Log.i(TAG, "foreign close deferred active=$active incoming=$packageName")
            }
            scheduleForeignCloseRecheck(active, packageName, displayId, scheduleBlocked)
            return
        }
        val limitWalled = dailyWallPackage(displayId) == packageName
        val tracked = !scheduleBlocked && !limitWalled && isLimitTracked(packageName)
        val observed = usageSessions.observeForeground(
            packageName, tracked, displayId, SystemClock.elapsedRealtime(), systemBootCount(this),
        )
        observed.closedTick?.let { accountUsage(it) }
        // Instant zero-delta evaluation on open: re-arms the block within ms of re-entry
        // (§8 Step 4 re-arm) and makes §9's non-positive-limit Block immediate instead of
        // waiting for the first 10s tick.
        if (observed.opened) accountUsage(UsageSessionMachine.Tick(packageName, 0L, displayId))
        syncUsageTicker()
    }

    /** Closes the open session with its final tick (§6 step 4) — idempotent. */
    private fun closeUsageSession() {
        usageSessions.close(SystemClock.elapsedRealtime())?.let { accountUsage(it) }
        syncUsageTicker()
    }

    /** Re-verifies a deferred foreign close (ADR #107) one poll after the suspicious
     *  transition: the platform foreground moved on → the leave was real, close with the
     *  final tick and hand the session to the incoming package (the original WSC is the
     *  only transition the app will emit until it is touched again); still the tracked app
     *  → the WSC was a transient surface and the session simply continues. The over-count
     *  a lost race can produce is bounded by this delay (~2 s), inside the plan's accepted
     *  sub-poll sojourn tolerance. One pending re-check at a time — the latest transition
     *  wins. */
    private fun scheduleForeignCloseRecheck(
        trackedPackage: String,
        incomingPackage: String,
        displayId: Int,
        scheduleBlocked: Boolean,
    ) {
        pendingForeignCloseRecheck?.let { mainHandler.removeCallbacks(it) }
        val recheck = Runnable {
            pendingForeignCloseRecheck = null
            if (usageSessions.activePackage != trackedPackage) return@Runnable
            val confirms = platformForegroundConfirms(
                trackedPackage,
                EnforcementForegroundService.platformForegroundPackage,
                SystemClock.elapsedRealtime() -
                    EnforcementForegroundService.platformForegroundObservedAtElapsedMs,
            )
            if (confirms) return@Runnable
            if (BuildConfig.DEBUG) {
                Log.i(TAG, "deferred foreign close confirmed pkg=$trackedPackage incoming=$incomingPackage")
            }
            closeUsageSession()
            syncUsageSession(incomingPackage, displayId, scheduleBlocked)
        }
        pendingForeignCloseRecheck = recheck
        mainHandler.postDelayed(recheck, FOREIGN_CLOSE_RECHECK_MS)
    }

    /** The 10s ticker (§8 Step 4): one tick per open session, incremental delta, anchor reset
     *  each tick. Cancelled whenever no session is open. */
    private fun syncUsageTicker() {
        if (usageSessions.activePackage == null) {
            usageTickJob?.cancel()
            usageTickJob = null
            return
        }
        if (usageTickJob?.isActive == true) return
        usageTickJob = scope.launch {
            while (isActive) {
                delay(USAGE_TICK_MS)
                usageSessions.tick(SystemClock.elapsedRealtime(), systemBootCount(this@BlockerAccessibilityService))
                    ?.let { accountUsage(it) }
            }
        }
    }

    /** One accounting write + render. §8 Step 4's authority gate ("only when
     *  ProtectionStatusMonitor reports Path A holds accounting authority") is trivially
     *  satisfied until Step 5/6 exist: Path B has no process yet and this ticker running is
     *  Path A's health signal — Step 6 replaces this seam with the monitor check. The engine
     *  transaction runs off the main thread (NFR4: failures are logged, never crash-loops). */
    private fun accountUsage(tick: UsageSessionMachine.Tick) {
        // The rule may have lost its limit (or been paused/expired) mid-session: sessions open
        // only for tracked rules, but an edit can land between open and tick. Re-checked here,
        // off the transactionally-republished snapshot, because the engine's contract is
        // "callers never send untracked rules" — a limit of 0 reaching it means Block (§9).
        if (!isLimitTracked(tick.packageName)) return
        scope.launch {
            runCatching {
                val action = usageEngine.processUsageObservation(
                    tick.packageName,
                    tick.deltaMs,
                    SystemClock.elapsedRealtime(),
                    UsageLedgerSource.PATH_A,
                )
                mainHandler.post { renderUsageAction(tick.packageName, tick.displayId, action) }
            }.onFailure { Log.w(TAG, "usage accounting failed pkg=${tick.packageName}", it) }
        }
    }

    /** Renders the engine's verdict on the main thread (§8 Steps 4/10). A verdict rendered
     *  after its app already left the foreground touches nothing — the pending warning state
     *  survives and retries on the app's next observation (Decision 14), and the block
     *  re-arms on the next foreground enter. */
    private fun renderUsageAction(packageName: String, displayId: Int, action: EnforcementAction) {
        if (!isActiveForeground(packageName, null)) return
        when (action) {
            is EnforcementAction.NoAction -> Unit
            is EnforcementAction.ShowWarning -> {
                // Never over a wall: a banner for an app whose card is already up is noise
                // (and its delivery confirm would be a lie — the state stays PENDING).
                val current = overlay.currentContent(displayId)
                if (current is OverlayContent.AppBlocked || current is OverlayContent.UrlBlocked ||
                    current is OverlayContent.DailyLimitBlocked
                ) {
                    return
                }
                overlay.show(
                    stateMachine.currentGeneration,
                    displayId,
                    overlay.warningBounds(displayId),
                    OverlayContent.Warning(packageName, action.level, action.remainingMs),
                ) {
                    // Delivery confirmed only once the banner actually attached (§9: a
                    // warning that failed to attach stays PENDING_DELIVERY / Decision 14).
                    scope.launch { runCatching { usageEngine.markWarningDelivered(packageName, action.level) } }
                }
            }
            is EnforcementAction.Block -> {
                // The wall replaces the session: nothing accrues under a covered app.
                if (usageSessions.activePackage == packageName) closeUsageSession()
                val current = overlay.currentContent(displayId)
                if (current !is OverlayContent.DailyLimitBlocked) {
                    overlay.show(
                        stateMachine.currentGeneration,
                        displayId,
                        overlay.displayBounds(displayId),
                        OverlayContent.DailyLimitBlocked(packageName),
                    ) {
                        // §8 Step 4 eviction, attach-confirmed: covering is not stopping. Left
                        // resumed under the wall, the app kept playing audio and Digital
                        // Wellbeing kept counting it as foreground (device-caught 2026-09-27
                        // on the X6532 — Instagram held the audio-focus stack behind the
                        // wall). Home backgrounds it; no timed hide follows (user ruling
                        // 2026-09-27: the wall stays until the user dismisses it via Close).
                        closeBlockedApp(packageName)
                    }
                }
            }
        }
    }

    private fun dailyWallPackage(displayId: Int): String? =
        (overlay.currentContent(displayId) as? OverlayContent.DailyLimitBlocked)?.packageName

    /** §8 Step 4 block eviction (FR2: "the app is blocked and the user is returned to the home
     *  screen"), completed by the user ruling 2026-09-27 — "logically just block the app and
     *  close it". Eviction alone returned the app to the *background*, where it kept running
     *  and still played audio under the wall (device-caught: Instagram audible while covered;
     *  covering is not stopping). [ActivityManager.killBackgroundProcesses] is the strongest
     *  close a third-party app can perform, and it only applies once the target is genuinely
     *  backgrounded — hence the retry after the (asynchronous) HOME transition. The wall itself
     *  is untouched: it keeps its own lifetime (the user's Close tap). */
    private fun closeBlockedApp(packageName: String) {
        val evicted = performGlobalAction(GLOBAL_ACTION_HOME)
        killBlockedProcess(packageName)
        mainHandler.postDelayed({ killBlockedProcess(packageName) }, BLOCK_CLOSE_RETRY_MS)
        Log.i(TAG, "block close pkg=$packageName evicted=$evicted")
    }

    private fun killBlockedProcess(packageName: String) {
        runCatching {
            (getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
                ?.killBackgroundProcesses(packageName)
        }.onFailure { Log.w(TAG, "block close failed pkg=$packageName", it) }
    }

    /** True when [packageName] is a live persistent block scene's own target — the daily wall
     *  or the app-block card (both stay up until the user dismisses them; owner rulings
     *  2026-09-27 / 2026-10-03) — the re-entry signal that re-runs the eviction. Only a WSC
     *  for the scene's package counts: any other package coming forward (the launcher, after
     *  the eviction) must leave the scene standing. */
    private fun reevictIfScene(packageName: String?, displayId: Int): Boolean {
        val scene = overlay.currentContent(displayId)
        val target = when (scene) {
            is OverlayContent.DailyLimitBlocked -> scene.packageName
            is OverlayContent.AppBlocked -> scene.packageName
            else -> null
        } ?: return false
        if (target != packageName) return false
        closeBlockedApp(target)
        return true
    }

    /** A tracked package = an enabled rule with a real limit, on a day the rule covers —
     *  mirrors BlockDecisionEngine Rule 2's `dailyLimitMs > 0` gate plus the rule's day scope
     *  (a rule not active today never accrues, so it must not open a session). Read off the
     *  cached snapshot: no DB I/O on the event path. */
    private fun isLimitTracked(packageName: String): Boolean {
        val rule = publisher.snapshot.appRules[packageName] ?: return false
        return rule.dailyLimitMs > 0L && dayScopeCoversToday(rule.activeDaysOfWeek, rule.timezone)
    }

    /** Multi-window (plan §8 Step 5 / Decision 10): an Allow for one window must not lift
     *  the cover off a restricted pane beside it — scrolling the allowed pane's Settings
     *  hid the cover off the still-blocked victim pane (emulator-5554, 2026-09-14). True
     *  when a restricted window on this display is DISJOINT from the window being allowed:
     *  a partition partner. Overlap disqualifies by design — a restricted fullscreen
     *  window still fading out behind a new foreground app and a blocked PiP sliver both
     *  overlap the allowed window, and keeping their cover alive would strand it over the
     *  launcher (the "Home always works" promise). No visibility test: the android-37
     *  stubs drop AccessibilityWindowInfo.isVisible, and overlap already separates
     *  occluded windows from partitions. Costs a window-list walk, so callers
     *  short-circuit on a not-shown overlay. */
    private fun restrictedPaneBeside(displayId: Int, allowed: AccessibilityWindowInfo?): Boolean {
        val allowedBounds = allowed?.boundsInScreenOrNull ?: return false
        allWindows().forEach { w ->
            if (w.displayIdOrDefault() != displayId) return@forEach
            val bounds = w.boundsInScreenOrNull ?: return@forEach
            if (Rect.intersects(bounds, allowedBounds)) return@forEach
            val pkg = runCatching { w.root?.packageName?.toString() }.getOrNull() ?: return@forEach
            if (emergency.isProtected(pkg, w)) return@forEach
            if (engine.decideApp(pkg, System.currentTimeMillis()) is BlockDecision.Block) return true
        }
        return false
    }

    /** Deferred re-check for a WSC whose window was absent from the live list at arrival: the
     *  window either appears moments later (XOS window-list lag — decide it, advancing the
     *  generation) or never does (keyguard removal, background notification flapping — drop
     *  it, as before). Decides only when the window is the active foreground, so a delayed
     *  entry for a background window cannot hide an overlay over the real foreground app. */
    private fun deferredWsc(packageName: String, windowId: Int, detectionNanos: Long, attempt: Int = 0) {
        val window = windowFor(windowId)
        // Same own-cover guard as the direct path: the overlay's own window events must never
        // decide (Allow → self-hide; caught on-device 2026-08-27).
        if (window?.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) return
        // The active root is the decisive signal; the window-list entry is a bonus (a
        // recreated window can leave the event's id permanently stale — emulator-caught
        // 2026-09-13: the one re-check fired mid-transition and dropped, so the cover stayed
        // above BlockerActivity with the activity's WSC never re-deciding).
        if (rootInActiveWindow?.packageName?.toString() != packageName) {
            if (attempt < MAX_DEFERRED_WSC_ATTEMPTS) {
                mainHandler.postDelayed(
                    { deferredWsc(packageName, windowId, detectionNanos, attempt + 1) },
                    DEFERRED_WSC_MS,
                )
            } else if (BuildConfig.DEBUG) {
                Log.i(
                    TAG,
                    "deferred WSC dropped pkg=$packageName winId=$windowId window=${window != null}",
                )
            }
            return
        }
        val generation = stateMachine.advance(packageName, windowId, detectionNanos)
        decideApp(packageName, generation, detectionNanos, window)
    }

    /** The card for an app block: the deciding package named as the app block screen names it,
     *  with the rule its escape action would start a session for (absent only in the
     *  decide/show race with a deletion). Built on block transitions only. */
    private fun appBlockedContent(packageName: String): OverlayContent.AppBlocked =
        OverlayContent.AppBlocked(
            target = appLabel(this, packageName),
            ruleId = publisher.snapshot.appRules[packageName]?.ruleId,
            packageName = packageName,
        )

    /** Multi-window (plan §8 Step 5 / Decision 10): each display showing a restricted package
     *  gets its overlay bounded to that package's LARGEST window — its main surface, so a
     *  transient sub-window can't shrink the cover (AnkiDroid's 684x563 loading card,
     *  2026-09-13). The trigger's show passed display bounds; this pass is what re-bounds the
     *  already-shown cover to the restricted pane (split-screen, emulator-5554 2026-09-14).
     *  Clamped to the bar-free display: panes extend behind the nav bar, and covering it
     *  swallows Home/Back (2026-09-13). */
    /** Whole-display cover, then the largest-window re-bound. The per-window root walk runs on
     *  a block transition (and its bounded settle re-runs), never per steady-state WSC
     *  (2026-08-28 review). */
    private fun coverBlockedApp(generation: Long, displayId: Int, packageName: String) {
        // Attach-confirmed block close (§8 Step 4 / FR2, user ruling 2026-09-27): the cover
        // being on screen is what licenses closing the app — never the add/show call.
        overlay.show(
            generation,
            displayId,
            overlay.displayBounds(displayId),
            appBlockedContent(packageName),
        ) { closeBlockedApp(packageName) }
        blockOtherRestrictedWindows(generation)
    }

    /** Settle re-run: re-cover until the deciding package's target rect is stable across two
     *  consecutive samples (or [COVER_SETTLE_MAX_ATTEMPTS] is hit). [previous] is the last sample;
     *  null on entry so the first re-run always happens, matching the pre-loop behavior. A sample
     *  that reads no window is "not yet stable" — the window list can be momentarily empty
     *  mid-switch, and treating that as settled would freeze the cover at the animation rect. */
    private fun settleCover(
        generation: Long,
        displayId: Int,
        packageName: String,
        previous: Rect?,
        attempt: Int,
    ) {
        if (attempt > COVER_SETTLE_MAX_ATTEMPTS) return
        mainHandler.postDelayed(
            {
                if (generation != stateMachine.currentGeneration || !overlay.isShown(displayId)) {
                    return@postDelayed
                }
                val current = restrictedWindowBounds(displayId, packageName)
                if (current != null && current == previous) return@postDelayed
                coverBlockedApp(generation, displayId, packageName)
                settleCover(generation, displayId, packageName, current, attempt + 1)
            },
            COVER_SETTLE_INTERVAL_MS,
        )
    }

    /** The rect the cover would land on for [packageName]: its largest window on [displayId],
     *  clamped to the bar-free display — the same target [blockOtherRestrictedWindows] covers. */
    private fun restrictedWindowBounds(displayId: Int, packageName: String): Rect? {
        var best: Rect? = null
        var bestArea = 0L
        allWindows().forEach { w ->
            if (w.displayIdOrDefault() != displayId) return@forEach
            val root = runCatching { w.root }.getOrNull() ?: return@forEach
            if (root.packageName?.toString() != packageName) return@forEach
            val bounds = w.boundsInScreenOrNull ?: return@forEach
            if (bounds.area() > bestArea) {
                best = Rect(bounds)
                bestArea = bounds.area()
            }
        }
        val target = best ?: return null
        return if (target.intersect(overlay.displayBounds(displayId))) target else null
    }

    private fun blockOtherRestrictedWindows(generation: Long) {
        val candidate = HashMap<Int, Pair<String, Rect>>()
        allWindows().forEach { w ->
            val root = runCatching { w.root }.getOrNull() ?: return@forEach
            val pkg = root.packageName?.toString() ?: return@forEach
            if (emergency.isProtected(pkg, w)) return@forEach
            if (engine.decideApp(pkg, System.currentTimeMillis()) !is BlockDecision.Block) return@forEach
            val bounds = w.boundsInScreenOrNull ?: return@forEach
            val displayId = w.displayIdOrDefault()
            val incumbent = candidate[displayId]
            if (incumbent == null || bounds.area() > incumbent.second.area()) {
                candidate[displayId] = pkg to bounds
            }
        }
        candidate.forEach { (displayId, entry) ->
            val (pkg, windowBounds) = entry
            val target = Rect(windowBounds)
            if (!target.intersect(overlay.displayBounds(displayId))) return@forEach
            overlay.show(generation, displayId, target, appBlockedContent(pkg))
        }
    }

    private fun handleUrlResult(
        generation: Long,
        decision: BlockDecision,
        canonical: CanonicalUrl?,
        browserPackage: String,
    ) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "url result gen=$generation -> ${decision.javaClass.simpleName}")
        }
        val displayId = if (generation == scanGeneration) scanDisplayId else Display.DEFAULT_DISPLAY
        when (decision) {
            is BlockDecision.Block -> {
                // The scanner Blocks only a parseable address (a null canonical is a scheme
                // navigation, always Allow), so this is unreachable in practice.
                val url = canonical ?: return
                val rule = engine.matchingUrlRule(url)
                val firstShow = !overlay.isShown(displayId)
                overlay.show(
                    generation,
                    displayId,
                    overlay.displayBounds(displayId),
                    if (rule != null) {
                        OverlayContent.UrlBlocked(url.hostUnicode, rule.ruleId, browserPackage)
                    } else {
                        // A rule deleted between scan and result leaves the block stated
                        // without an action (the scan only Blocks a matching rule, so this
                        // is a vanishing race, not a state).
                        OverlayContent.AppBlocked(target = url.hostUnicode, ruleId = null)
                    },
                )
                // State 2: "It increments access_attempts_count" — once per block transition,
                // matching the FGS app path (a repeated scan of the same blocked page is not
                // a new attempt).
                if (firstShow && rule != null) {
                    scope.launch { runCatching { attempts.recordBlocked(rule.ruleId, url.hostUnicode) } }
                }
            }
            is BlockDecision.Allow -> {
                // URL evidence is not a foreground transition: it must never lift a usage
                // surface — the daily-limit wall (§8 Step 4 re-arm — the wall is lifted only
                // by the usage engine or by the foreground moving to a different app) or the
                // browser's own warning banner (device-caught 2026-09-27: Chrome's omnibox
                // content-change Allowed and hid a level-1 banner 270 ms after it was shown).
                // Both are engine-owned; the banner still dismisses on its 5 s timer or via
                // decideApp when the foreground changes.
                val current = overlay.currentContent(displayId)
                if (current is OverlayContent.DailyLimitBlocked) return
                if ((current as? OverlayContent.Warning)?.packageName == browserPackage) return
                overlay.hide(generation, displayId)
            }
        }
    }

    /** Interception Escape Hatch (V1 State 2 Path A): start the same dual-clock friction
     *  session the app block screen starts, then land the user on the Recovery dashboard —
     *  one tap from the block surface (NFR: max 1 tap to recovery resources). */
    private fun openRecovery(ruleId: String) {
        scope.launch { runCatching { recoveryStore.startRecovery(ruleId) } }
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_START_TAB, MainActivity.TAB_RECOVERY)
                .putExtra(MainActivity.EXTRA_RECOVERY_ENTRY, RecoverySession.ENTRY_BLOCK_SCREEN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
    }

    /** "Go to a different site": load a blank page in the same browser. The blocked tab stays
     *  behind it — revisiting it re-scans and re-blocks like any other blocked URL. */
    private fun openDifferentSite(browserPackage: String) {
        runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW, "about:blank".toUri())
                    .setPackage(browserPackage)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private fun registerDialerReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = emergency.refresh()
        }
        // TelephonyManager.ACTION_DEFAULT_DIALER_CHANGED is removed from the compileSdk 37
        // stubs; the constant's value is stable across all supported API levels. NOT_EXPORTED
        // (API 33+): the dialer-change broadcast is sent by the system, which exempts it from
        // the same-app-only restriction; non-exported also blocks third-party spoofs of the action.
        val filter = IntentFilter("android.telephony.action.DEFAULT_DIALER_CHANGED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            // RECEIVER_EXPORTED is a 33+ constant; its value (2) is ignored by the 26-32
            // registerReceiver (flags were reserved until 33) but keeps every path flagged.
            @Suppress("InlinedApi")
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        }
        dialerReceiver = receiver
    }

    /** Deterministic hide on screen-off: the keyguard WSC that would hide the overlay arrives
     *  up to ~6s late on XOS (window-list lag, caught on-device 2026-08-28 — the §12 functional
     *  suite's locked-state check raced it and lost). Hiding at screen-off removes the overlay
     *  before the screen ever shows it again; the normal pipeline re-shows on the next
     *  foreground WSC after unlock, and emergency exclusion covers the keyguard itself.
     *  ACTION_SCREEN_OFF is only deliverable to runtime-registered receivers. */
    private fun registerScreenOffReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                overlay.hideAll(stateMachine.currentGeneration)
                // Screen off = nothing visible: the session's final tick credits real usage up
                // to the moment the screen went dark, then stops (no phantom accrual while
                // off). Re-opens at the next foreground WSC, same as the overlay re-arm.
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

    /** AccessibilityEvent.getWindow() is removed from the compileSdk 37 stubs; resolve the
     *  window from the (flagRetrieveInteractiveWindows) window list instead. */
    private fun windowFor(event: AccessibilityEvent): AccessibilityWindowInfo? = windowFor(event.windowId)

    private fun windowFor(windowId: Int): AccessibilityWindowInfo? {
        val windows = runCatching { getWindows() }.getOrNull() ?: return null
        return windows.firstOrNull { it.id == windowId }
    }

    /** getWindowsOnAllDisplays() is a SparseArray keyed by displayId; flatten it. */
    private fun allWindows(): List<AccessibilityWindowInfo> = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val sparse = windowsOnAllDisplays
            // Lint suggests a KTX SparseArray.size property that core-ktx 1.19 doesn't ship.
            @Suppress("UseKtx")
            (0 until sparse.size()).flatMap { sparse.valueAt(it) }
        } else {
            getWindows()
        }
    }.getOrNull() ?: emptyList()

    /** Same view the device suite's UiAutomation used; exposed for the debug verification
     *  activity (no instrumentation connection exists on this device — see §12 harness note). */
    fun visibleWindows(): List<AccessibilityWindowInfo> = allWindows()

    /** Recycled windows throw IllegalStateException from their getters; accessibility dispatch
     *  swallows those throws, killing the event mid-pipeline ("show vanish": decide logged, show
     *  never runs, caught 2026-08-28 latency suite). These never throw; callers fall back to
     *  display 0 / full-screen bounds. */
    private fun AccessibilityWindowInfo?.displayIdOrDefault(): Int =
        if (this != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { getDisplayId() }.getOrDefault(Display.DEFAULT_DISPLAY)
        } else {
            Display.DEFAULT_DISPLAY
        }

    private val AccessibilityWindowInfo.boundsInScreenOrNull: Rect?
        get() = runCatching { Rect().also { getBoundsInScreen(it) } }.getOrNull()

    private fun Rect.area(): Long = width().toLong() * height().toLong()

    /** True when the window is the active foreground (per-display isActive, root fallback for
     *  XOS window-list lag); safe on recycled windows. Both the WSC decide gate and the
     *  emergency-exclusion gate key off it. */
    private fun isActiveForeground(packageName: String?, window: AccessibilityWindowInfo?): Boolean =
        runCatching { window?.isActive == true }.getOrDefault(false) ||
            runCatching { rootInActiveWindow?.packageName?.toString() == packageName }.getOrDefault(false)

    companion object {
        const val TAG = "MyExodusBlocker"

        /** How long to wait after a null-window WSC before re-checking the live window list. */
        private const val DEFERRED_WSC_MS = 400L

        /** Bounded re-checks for a not-yet-active WSC: transitions can take seconds on a
         *  loaded device (emulator: BlockerActivity displayed ~4s after start; a single
         *  re-check missed it and the cover above it never hid). ~2.4s worst case, then the
         *  event is dropped for good — a genuinely stale event must not decide later. */
        private const val MAX_DEFERRED_WSC_ATTEMPTS = 6

        /** Path A's session ticker cadence (enforcement plan v2 §8 Step 4). */
        private const val USAGE_TICK_MS = 10_000L

        /** Bounded re-checks for the connect-time usage re-acquire while the active root is
         *  still resolving (~1.2s worst case — mirrors the deferred-WSC lag handling). */
        private const val MAX_CONNECT_REACQUIRE_ATTEMPTS = 3

        /** Delay before the block close's second kill attempt: performGlobalAction is
         *  asynchronous, and killBackgroundProcesses only lands once the target is genuinely
         *  backgrounded — the first attempt covers the common case, this one the lag. */
        private const val BLOCK_CLOSE_RETRY_MS = 700L

        /** Beat between settle-loop samples (device-tuned on XOS 2026-09-16: an OEM task-switch
         *  animation is still moving at ~700ms, measures settled by the following sample). */
        private const val COVER_SETTLE_INTERVAL_MS = 700L

        /** Cap on settle re-runs: with the two-consecutive-equal-samples stop this bounds a
         *  pathological window-list (~4.2s worst case), matching DEFERRED_WSC's bounded-retry
         *  posture — a genuinely stale block must not re-cover forever. */
        private const val COVER_SETTLE_MAX_ATTEMPTS = 6

        /** How long Path B's poll oracle may go unrefreshed and still overrule a foreground
         *  transition (ADR #107): the poll re-stamps it every ≤1.5 s, so 5 s spans several
         *  ticks — anything older means the FGS is stopped or wedged, and its silence must
         *  read as "no evidence", never as a frozen keep. */
        private const val FOREIGN_CLOSE_ORACLE_FRESH_MS = 5_000L

        /** Re-verification delay after a deferred foreign close (ADR #107): one poll interval
         *  plus margin, so the next Path B tick has landed. Bounds a lost race's over-count. */
        private const val FOREIGN_CLOSE_RECHECK_MS = 2_000L

        /** Does a fresh-enough oracle still report [packageName] as the platform foreground?
         *  Pure for direct testing. */
        fun platformForegroundConfirms(
            packageName: String,
            oraclePackage: String?,
            oracleAgeMs: Long,
        ): Boolean = oraclePackage == packageName && oracleAgeMs in 0..FOREIGN_CLOSE_ORACLE_FRESH_MS

        /** ADR #107's deferral rule: a foreign-package transition must not close the active
         *  session while a fresh oracle still reports the active package as the platform
         *  foreground. Pure for direct testing. */
        fun shouldDeferForeignClose(
            activePackage: String,
            incomingPackage: String,
            oraclePackage: String?,
            oracleAgeMs: Long,
        ): Boolean = activePackage != incomingPackage &&
            platformForegroundConfirms(activePackage, oraclePackage, oracleAgeMs)

        /** True between onServiceConnected and onDestroy; the device suite waits on it because
         *  the accessibility re-bind after a process restart lags on XOS. */
        val connected = java.util.concurrent.atomic.AtomicBoolean(false)

        /** Non-null between onServiceConnected and onDestroy; the debug verification
         *  activity reads the service's window view through this. */
        @Volatile
        var instance: BlockerAccessibilityService? = null
    }
}
