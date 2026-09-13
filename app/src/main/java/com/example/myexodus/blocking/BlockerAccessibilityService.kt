package com.example.myexodus.blocking

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.net.toUri
import com.example.myexodus.BuildConfig
import com.example.myexodus.MainActivity
import com.example.myexodus.blocking.recovery.RecoveryStateStore
import com.example.myexodus.settings.EnhancedModeSettings
import com.example.myexodus.ui.appLabel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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
    @Inject lateinit var recoveryStore: RecoveryStateStore
    @Inject lateinit var attempts: AttemptRecorder

    /** Per-bind foreground state + overlay (plan §12: recreated on re-bind; app-scoped
     *  singletons would carry stale generation/overlay state across a Hiber disconnect —
     *  caught 2026-08-28 review: the plan records service-scoped recreation, the DI graph
     *  provided @Singleton copies). */
    private val stateMachine = ForegroundStateMachine()
    internal lateinit var overlay: OverlayController

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

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected.set(true)
        instance = this
        emergency.refresh()
        // Plan §8 Step 12: AAPM check on connect (plus every health-check tick in the worker).
        monitor.refresh()
        overlay.prewarm()
        registerDialerReceiver()
        registerScreenOffReceiver()
        // Fast-path cache synchronously, authoritative load async (plan §8 Steps 2/9) — the
        // engine always decides against some snapshot, never against "no rules".
        publisher.loadFastPath()
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
            return
        }
        // Data Flow step 3: emergency exclusion first, fail-open on protected packages — but
        // gated on the active foreground window: a background flap from a protected package
        // (the dialer's stale null-window WSCs) must not tear down the overlay over a
        // still-restricted app (caught on-device 2026-08-28 — mid-tapjacking hide).
        if (emergency.isProtected(packageName, window) && isActiveForeground(packageName, window)) {
            overlay.hide(stateMachine.currentGeneration, displayId)
            return
        }
        // Step 4: unknown package → immediate hide, no retain window.
        if (packageName.isNullOrEmpty()) {
            overlay.hide(stateMachine.currentGeneration, displayId)
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
                // hides on our own overlay's content-changed events.
                if (packageName == this.packageName) return@onAccessibilityEvent
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
        mainHandler.removeCallbacksAndMessages(null)
        dialerReceiver?.let { runCatching { unregisterReceiver(it) } }
        dialerReceiver = null
        screenOffReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenOffReceiver = null
        scanner.cancelPending()
        overlay.detachAll()
        scope.cancel()
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
        when (decision) {
            is BlockDecision.Block -> {
                // Content is built only on a transition: an already-shown overlay's show()
                // is a no-op, and the label lookup (PackageManager round-trip) must not run
                // per WSC storm event. Display bounds, not the deciding window's: a cold
                // launch can fire the blocked app's sub-window (loading/transient dialog)
                // first, and the already-shown gate then froze the cover at that sub-window's
                // size — AnkiDroid rendered readable around a 684x563 card (2026-09-13). The
                // cover's job is the app's whole surface; split-screen pane coverage for
                // *other* restricted windows stays in blockOtherRestrictedWindows.
                if (!overlay.isShown(displayId)) {
                    overlay.show(generation, displayId, displayBounds(displayId), appBlockedContent(packageName))
                    // Per-window root walks only on a new block transition, not on every
                    // steady-state WSC while the overlay is already up (2026-08-28 review).
                    blockOtherRestrictedWindows(generation)
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
                val barId = if (overlay.currentContent(displayId) is OverlayContent.UrlBlocked) {
                    scanner.viewIdFor(packageName) { rootInActiveWindow }
                } else {
                    null
                }
                if (barId != null) {
                    scanGeneration = generation
                    scanDisplayId = displayId
                    scanner.onContentChanged(packageName, barId, { rootInActiveWindow }, generation)
                } else {
                    overlay.hide(generation, displayId)
                }
            }
        }
    }

    /** Deferred re-check for a WSC whose window was absent from the live list at arrival: the
     *  window either appears moments later (XOS window-list lag — decide it, advancing the
     *  generation) or never does (keyguard removal, background notification flapping — drop
     *  it, as before). Decides only when the window is the active foreground, so a delayed
     *  entry for a background window cannot hide an overlay over the real foreground app. */
    private fun deferredWsc(packageName: String, windowId: Int, detectionNanos: Long) {
        val window = windowFor(windowId) ?: return
        if (rootInActiveWindow?.packageName?.toString() != packageName) return
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
        )

    /** Multi-window (plan §8 Step 5 / Decision 10): every other window resolving to a restricted
     *  package gets its display's overlay, bounded to that window's bounds. Includes our own
     *  overlay window (decides Allow) and the current one (already shown). */
    private fun blockOtherRestrictedWindows(generation: Long) {
        allWindows().forEach { w ->
            val root = runCatching { w.root }.getOrNull() ?: return@forEach
            val pkg = root.packageName?.toString() ?: return@forEach
            if (emergency.isProtected(pkg, w)) return@forEach
            if (engine.decideApp(pkg, System.currentTimeMillis()) is BlockDecision.Block) {
                val displayId = w.displayIdOrDefault()
                overlay.show(
                    generation,
                    displayId,
                    w.boundsInScreenOrNull ?: displayBounds(displayId),
                    appBlockedContent(pkg),
                )
            }
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
                    displayBounds(displayId),
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
            is BlockDecision.Allow -> overlay.hide(generation, displayId)
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

    @Suppress("DEPRECATION") // getRealMetrics: deprecated in API 34, but the only non-cutout-aware path below API 30 (minSdk 24).
    private fun displayBounds(displayId: Int): Rect {
        val display = getSystemService(DisplayManager::class.java).getDisplay(displayId) ?: return Rect()
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)
        val full = Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return full
        // Stop the card at the system bars: a full-display overlay draws above the
        // navigation bar and swallows its touches, so Home/Back stop working (device-caught
        // 2026-09-13 — the card's own hint promised they always work). The bar-free area
        // keeps them visible and touchable around the card.
        val bars = createDisplayContext(display)
            .getSystemService(WindowManager::class.java)
            ?.currentWindowMetrics?.windowInsets
            ?.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
            ?: return full
        return Rect(
            full.left + bars.left,
            full.top + bars.top,
            full.right - bars.right,
            full.bottom - bars.bottom,
        )
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
            override fun onReceive(context: Context?, intent: Intent?) =
                overlay.hideAll(stateMachine.currentGeneration)
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

        /** True between onServiceConnected and onDestroy; the device suite waits on it because
         *  the accessibility re-bind after a process restart lags on XOS. */
        val connected = java.util.concurrent.atomic.AtomicBoolean(false)

        /** Non-null between onServiceConnected and onDestroy; the debug verification
         *  activity reads the service's window view through this. */
        @Volatile
        var instance: BlockerAccessibilityService? = null
    }
}
