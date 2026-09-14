package com.example.myexodus.blocking

import android.app.Activity
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Bundle
import androidx.core.net.toUri
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityWindowInfo
import com.example.myexodus.data.db.entity.RestrictionRule
import com.example.myexodus.data.db.entity.UserProfile
import dagger.hilt.android.EntryPointAccessors
import java.util.TimeZone
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * §12 device verification WITHOUT instrumentation (plan §12, harness note 2026-08-27).
 *
 * Why this exists: `Context.startInstrumentation` from an app process is denied on this XOS
 * user build (AOSP security check: caller must be root/shell/system or the OS build
 * debuggable, or the process already under an instrumentation), and an instrumented process
 * never binds the accessibility service on XOS — so the androidTest suites cannot run against
 * the bound service on THIS device. This debug-only activity runs the same scenarios
 * in-process against the real bound service; the host script (`scripts/enhanced-mode-device-test.sh`)
 * drives the parts that need shell powers (key presses, touch injection) by reacting to
 * MARKER log lines, and asserts the tapjacking result from the victim's logcat lines.
 *
 * Host usage: am start -n <app>/.blocking.DeviceVerificationActivity --es suite <functional|latency|multiwindow|browser|fgs|fgs-down|sync|seed-ui|clear-ui> [--ez requireService false] [--ez preSplit true (multiwindow only: the suite seeds, logs MARKER_MULTIWINDOW_SEEDED, and waits for the host to form the split — host launches the victim fullscreen, then Recents → Split screen → pick a partner app; the suite observes, never launches)]
 *
 * `seed-ui`/`clear-ui` seed and remove four rules (one per live-state branch of the home
 * dashboard) for UI screenshot verification; they keep existing data — no DB reset.
 */
class DeviceVerificationActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val suite = intent.getStringExtra(EXTRA_SUITE)
        if (suite == null) {
            Log.e(TAG, "no '$EXTRA_SUITE' extra; usage: --es $EXTRA_SUITE functional|latency|multiwindow|browser|fgs|fgs-down|sync|seed-ui|clear-ui")
            finish()
            return
        }
        runInBackground {
            val result = try {
                if (intent.getBooleanExtra(EXTRA_REQUIRE_SERVICE, true)) {
                    waitForService()
                }
                runBlocking { runSuite(suite) }
                "PASS"
            } catch (t: Throwable) {
                "FAIL ${t.message ?: t.javaClass.simpleName}"
            }
            Log.i(TAG, "RESULT: $suite=$result")
            finish()
        }
    }

    private fun runInBackground(block: () -> Unit) = Thread(block).also { it.start() }

    private fun waitForService() {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < deadline && !BlockerAccessibilityService.connected.get()) {
            SystemClock.sleep(200)
        }
        check(BlockerAccessibilityService.connected.get()) {
            "BlockerAccessibilityService not connected (host must enable+bind before launching)"
        }
        Log.i(TAG, "service connected")
    }

    private suspend fun runSuite(suite: String) {
        when (suite) {
            "functional" -> functionalSuite()
            "latency" -> latencySuite()
            "multiwindow" -> multiWindowSuite()
            "browser" -> browserUrlScenario()
            "fgs" -> fgsSuite()
            "fgs-down" -> fgsDownSuite()
            "sync" -> syncSuite()
            "seed-ui" -> seedUiRules()
            "clear-ui" -> clearUiRules()
            else -> error("unknown suite '$suite'")
        }
    }

    // --- UI-state seeding for the home dashboard (host: seed-ui → screenshot → clear-ui) ---

    /**
     * Leaves four rules covering every live-state branch of the home block list: always-on
     * (Blocked now), an inactive window (Not active now), an expired rule (Expired), and a
     * daily limit (honest no-pill — no live usage feed). Targets are not installed, so the
     * screenshot also exercises the app-label fallback. Keeps existing data: no DB reset.
     */
    private suspend fun seedUiRules() {
        val store = entryPoint.appRulesStoreFactory().open() ?: error("store unavailable")
        val profile = store.userProfileDao().observeActive().first() ?: PROFILE.also {
            store.userProfileDao().insert(it)
        }
        val tz = TimeZone.getDefault().id
        val now = System.currentTimeMillis()
        listOf(
            RestrictionRule(
                ruleId = "$UI_SEED_PREFIX-alwayson", profileId = profile.userId,
                blockedPackage = "$packageName.uiseed.social", targetType = "APP",
                isAlwaysOn = true, dailyLimitMs = 0L, timeRangeStart = null, timeRangeEnd = null,
                activeDaysOfWeek = "1111111", timezone = tz, blockExpiresAt = 0L, severityLevel = "HIGH",
            ),
            RestrictionRule(
                ruleId = "$UI_SEED_PREFIX-window", profileId = profile.userId,
                blockedPackage = "$packageName.uiseed.games", targetType = "APP",
                isAlwaysOn = false, dailyLimitMs = 0L, timeRangeStart = 3 * 60L, timeRangeEnd = 4 * 60L,
                activeDaysOfWeek = "1111111", timezone = tz, blockExpiresAt = 0L, severityLevel = "MEDIUM",
            ),
            RestrictionRule(
                ruleId = "$UI_SEED_PREFIX-expired", profileId = profile.userId,
                blockedPackage = "$packageName.uiseed.news", targetType = "APP",
                isAlwaysOn = true, dailyLimitMs = 0L, timeRangeStart = null, timeRangeEnd = null,
                activeDaysOfWeek = "1111111", timezone = tz, blockExpiresAt = now - 1L, severityLevel = "HIGH",
            ),
            RestrictionRule(
                ruleId = "$UI_SEED_PREFIX-limit", profileId = profile.userId,
                blockedPackage = "uiseed.example.com", targetType = "WEBSITE",
                isAlwaysOn = false, dailyLimitMs = 90 * 60_000L, timeRangeStart = null, timeRangeEnd = null,
                activeDaysOfWeek = "1111111", timezone = tz, blockExpiresAt = 0L, severityLevel = "LOW",
            ),
        ).forEach { entryPoint.rulesRepository().insertRule(it) }
    }

    private suspend fun clearUiRules() {
        val store = entryPoint.appRulesStoreFactory().open() ?: error("store unavailable")
        store.restrictionRuleDao().getActiveRules()
            .filter { it.ruleId.startsWith(UI_SEED_PREFIX) }
            .forEach { entryPoint.rulesRepository().deleteRule(it) }
    }

    // --- functional suite (life-safety x3, tapjacking, browser URL) ---------------------

    private suspend fun functionalSuite() {
        lifecycleScenario_lockedDeviceAndKeyguard()
        tapjackingScenario()
        browserUrlScenario()
        // Phone-app scenarios last: once the dialer/in-call UI has taken the foreground, the
        // suite (backgrounded behind it) loses background-activity-start privileges and its
        // next victim launch is silently blocked — no WSC, no decide, overlay never shows
        // (caught on-device 2026-08-31, twice). Nothing launches after these run.
        lifecycleScenario_dialerAndEmergencyHolders()
        lifecycleScenario_callSurfaces()
    }

    private suspend fun lifecycleScenario_dialerAndEmergencyHolders() {
        seed("APP")
        launchVictim()
        waitForOverlay(true)

        // App-context RoleManager returns empty on XOS (role visibility gate, observed
        // 2026-08-27 — shell `cmd role get-role-holders` shows com.sh.smart.caller). Plan
        // §8 Step 9's OEM fallback list applies here exactly as designed: used only when
        // the live query comes back empty.
        val dialers = roleHolders("android.app.role.DIALER")
            .ifEmpty { EmergencyExclusionManager.FALLBACK_DIALER_PACKAGES.toList() }
        check(dialers.isNotEmpty()) { "device must have a default dialer" }
        dialers.forEach { pkg ->
            if (launchPackage(pkg)) {
                Log.i(TAG, "life-safety: launched default dialer $pkg")
                waitForOverlay(false)
            }
        }
        val emergency = roleHolders("android.app.role.EMERGENCY")
        if (emergency.isEmpty()) {
            Log.w(TAG, "life-safety: ROLE_EMERGENCY holders invisible to app context on this device — skipped (coverage gap on XOS; plan §8 Step 9 has no emergency fallback)")
        }
        emergency.forEach { pkg ->
            if (launchPackage(pkg)) {
                Log.i(TAG, "life-safety: launched ROLE_EMERGENCY holder $pkg")
                waitForOverlay(false)
            }
        }
    }

    private suspend fun lifecycleScenario_callSurfaces() {
        seed("APP")
        launchVictim()
        waitForOverlay(true)

        launchDialPad()
        waitForOverlay(false)
        launchDialPad("112")
        waitForOverlay(false)

        val call = Intent(Intent.ACTION_CALL, "tel:12345".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { startActivity(call) }.isSuccess) {
            Log.i(TAG, "life-safety: outgoing call in progress (in-call UI)")
            waitForOverlay(false)
        } else {
            Log.i(TAG, "life-safety: ACTION_CALL denied by OS — in-call UI scenario skipped")
        }
    }

    private suspend fun lifecycleScenario_lockedDeviceAndKeyguard() {
        seed("APP")
        launchVictim()
        waitForOverlay(true)

        // Host reacts: MARKER LOCK -> power (device sleeps); MARKER WAKEUP -> wake the
        // screen (keyguard stays up); MARKER UNLOCK -> HOST PROMPTS THE USER, who unlocks
        // manually — a secure keyguard (PIN/password) cannot be dismissed from shell by
        // design (device-verified 2026-08-27). Plan §12 life-safety: the overlay must not
        // render while the keyguard is up — including after wake, before dismissal. After
        // unlock the victim is re-launched (the user may have landed anywhere) and must be
        // re-blocked: that is the engine resuming, asserted below, not a violation.
        val keyguard = getSystemService(KeyguardManager::class.java)
        Log.i(TAG, "MARKER: LOCK")
        waitFor(15_000) { keyguard.isKeyguardLocked.takeIf { it } }
        // Hide is driven by ACTION_SCREEN_OFF (deterministic) plus the lagging keyguard WSC
        // (up to ~6s late on XOS, 2026-08-28); waiting on the overlay instead of a single-shot
        // check removes the harness from that race.
        waitForOverlay(false, timeoutMs = 15_000)
        Log.i(TAG, "MARKER: WAKEUP")
        waitFor(15_000) { keyguard.isKeyguardLocked }
        waitForOverlay(false, timeoutMs = 15_000)
        Log.i(TAG, "MARKER: UNLOCK")
        waitFor(120_000) { !keyguard.isKeyguardLocked }
        launchVictim()
        waitForOverlay(true, timeoutMs = 15_000)
        Log.i(TAG, "life-safety: keyguard scenario passed")
    }

    private suspend fun tapjackingScenario() {
        seed("APP")
        launchVictim()
        waitForOverlay(true)

        // Host reacts: on TAP_READY it reads the victim's "VictimBounds" logcat line, taps
        // the window center with `input tap`, then asserts no "VictimTouch" line appears.
        Log.i(TAG, "MARKER: TAP_READY")
        SystemClock.sleep(6_000)
    }

    private suspend fun browserUrlScenario() {
        seed("WEBSITE", "http://example.com")
        launchBrowser("http://example.com")
        waitForOverlay(true, timeoutMs = 15_000)
        launchBrowser("http://example.org")
        waitForOverlay(false, timeoutMs = 15_000)
        Log.i(TAG, "browser URL block/hide cycle passed on com.android.chrome")
    }

    // --- latency suite (p50/p95/p99 vs §2 targets) --------------------------------------

    private suspend fun latencySuite() {
        seed("APP")
        // Percentiles must cover only this suite's samples (caught 2026-08-28: the suite
        // was reading functional-suite entries from the shared rolling buffer).
        entryPoint.latencyProfiler().reset()
        // 30 cycles: p99 of 10 samples is just the max (one janky frame fails the check).
        val cycles = 30
        repeat(cycles) { i ->
            launchVictim()
            waitForOverlay(true, timeoutMs = 8_000)
            launchAllowedApp()
            waitForOverlay(false, timeoutMs = 5_000)
            Log.i(TAG, "cycle ${i + 1}/$cycles complete")
        }

        val (detection, submission, presentation) = entryPoint.latencyProfiler().percentilesMillis()
        Log.i(
            TAG,
            "latency ms detection(p50/p95/p99)=${detection.contentToString()} " +
                "submission=${submission.contentToString()} presentation=${presentation.contentToString()}",
        )
        check(detection[2] in 0 until 1_000) { "detection p99 ${detection[2]}ms exceeds the <1000ms §2 target" }
        check(presentation[2] in 0 until 100) { "presentation p99 ${presentation[2]}ms exceeds the <100ms §2 target" }
    }

    // --- multi-window suite (split-screen; skips when not engaged) ----------------------

    private suspend fun multiWindowSuite() {
        seed("APP")
        if (intent.getBooleanExtra(EXTRA_PRE_SPLIT, false)) {
            // Host-driven (emulator-5554, 2026-09-14): any programmatic bring-to-front of a
            // pane task dismantles the split on this WM, so the suite stays passive — the
            // host launches the victim fullscreen, then forms the split from Recents
            // (victim = top pane). Engagement is detected on the victim's a11y window
            // shrinking below the display, NOT on the overlay: the fullscreen victim blocks
            // the moment it launches, crashing through waitForOverlay before any split
            // exists (three runs skipped as fullscreen, 2026-09-14).
            Log.i(TAG, "MARKER_MULTIWINDOW_SEEDED — host: form the split (victim = top pane)")
            waitForSplitEngaged(timeoutMs = 180_000)
            waitForOverlay(true, timeoutMs = 60_000)
        } else {
            launchVictim()
            waitForOverlay(true)
        }

        val (victimWindow, overlayWindow) = awaitMultiWindowWindows()

        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = DisplayMetrics()
        checkNotNull(display).getRealMetrics(metrics)
        val vb = Rect().also { victimWindow.getBoundsInScreen(it) }
        val isFullscreen =
            vb.width() >= metrics.widthPixels - 50 && vb.height() >= metrics.heightPixels - 50
        if (isFullscreen) {
            Log.i(TAG, "split-screen not engaged — skipping (manual XOS split gesture required)")
            return
        }

        val ob = Rect().also { overlayWindow.getBoundsInScreen(it) }

        check(ob.contains(vb.left + vb.width() / 2, vb.top + vb.height() / 2)) {
            "overlay $ob must cover the restricted pane $vb"
        }
        val cx = metrics.widthPixels / 2
        val cy = metrics.heightPixels / 2
        check(!ob.contains(cx, cy)) { "overlay $ob must not cover the allowed pane (display center $cx,$cy)" }
        check(overlay.isShown(Display.DEFAULT_DISPLAY))
        Log.i(TAG, "multi-window: overlay bounds $ob restricted to victim pane $vb")
    }

    /** The a11y window list lags the overlay's shown-state by a frame (emulator race,
     *  2026-09-14): both windows must be present before the assertions read bounds. */
    private fun awaitMultiWindowWindows(): Pair<AccessibilityWindowInfo, AccessibilityWindowInfo> {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (true) {
            val windows = BlockerAccessibilityService.instance?.visibleWindows().orEmpty()
            val victim = windows.firstOrNull {
                runCatching { it.root?.packageName?.toString() == victimPackage }.getOrDefault(false)
            }
            val overlayWindow = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
            if (victim != null && overlayWindow != null) return victim to overlayWindow
            if (SystemClock.elapsedRealtime() > deadline) {
                checkNotNull(victim) { "victim window must be visible to the service connection" }
                error("overlay window must be visible to the service connection")
            }
            SystemClock.sleep(200)
        }
    }

    // --- shared scaffolding (ported from EnhancedModeDeviceSuite) -----------------------

    private val entryPoint: EnhancedModeTestEntryPoint by lazy {
        EntryPointAccessors.fromApplication(applicationContext, EnhancedModeTestEntryPoint::class.java)
    }

    // OverlayController is per-bind service state, not a DI singleton (2026-08-28 review) —
    // reach it through the live service instance.
    private val overlay get() = BlockerAccessibilityService.instance?.overlay
        ?: error("BlockerAccessibilityService not bound (host must enable+bind before launching)")
    // Lazy: property initializers run in the constructor, before the Context attaches —
    // reading packageName eagerly NPEs (caught on-device 2026-08-27).
    private val victimPackage: String by lazy { "$packageName.test" }
    private val victimComponent: ComponentName by lazy {
        ComponentName(victimPackage, "$packageName.blocking.TouchRecordingActivity")
    }

    private suspend fun seed(targetType: String, blockedTarget: String = victimPackage) {
        // Live-DAO wipe via the cached store, not resetAndReopen: the harness runs with
        // MainActivity alive, and closing the connection under its dashboard collectors
        // crashes them ("attempt to re-open an already-closed object: myexodus_rules.db",
        // emulator-5554 2026-09-14). Keeping the same store also keeps the blocking
        // engine's publisher snapshot live — resetAndReopen swapped the store out from
        // under it (same crash class as the 2026-08-27 profile/UNIQUE finding below).
        val store = entryPoint.appRulesStoreFactory().open() ?: error("rules store unavailable")
        store.restrictionRuleDao().getActiveRules()
            .forEach { entryPoint.rulesRepository().deleteRule(it) }
        val profileId = store.userProfileDao().observeActive().first()?.userId ?: PROFILE.also {
            store.userProfileDao().insert(it)
        }.userId
        entryPoint.rulesRepository().insertRule(
                RestrictionRule(
                    ruleId = "rule-${System.nanoTime()}",
                    profileId = profileId,
                    // For WEBSITE rules the pattern text lives in blockedPackage (that is
                    // what UrlPattern.from parses); the APP seed uses the victim package.
                    blockedPackage = blockedTarget,
                    targetType = targetType,
                    isAlwaysOn = true,
                    dailyLimitMs = 0L,
                    timeRangeStart = null,
                    timeRangeEnd = null,
                    activeDaysOfWeek = "0,1,2,3,4,5,6",
                    blockExpiresAt = Long.MAX_VALUE,
                    severityLevel = "HIGH",
                ),
        )
    }

    private fun launchVictim() {
        // CLEAR_TASK: reusing a live victim window from a previous suite run fires no
        // WINDOW_STATE_CHANGED, so the engine never decides (caught on-device 2026-08-27).
        startActivity(
            Intent().setComponent(victimComponent)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
    }

    /**
     * §8.1/§8.2 FGS verification (Slice 8): seed a rule and launch the victim; the
     * ForegroundBlockerService (not the overlay) must drive the block. The host drives
     * recovery/override/unlock and asserts BlockerActivity via dumpsys — this activity is
     * covered the whole time. Pass --ez requireService false for a pure V1 (no-overlay)
     * deployment. No FGS restart needed: RulesRepository.insertRule republishes the
     * snapshot, which the poll loop reads live on the next tick.
     */
    private suspend fun fgsSuite() {
        seed("APP")
        Log.i(TAG, "MARKER_FGS_RULE_SEEDED")
        launchVictim()
        Log.i(TAG, "MARKER_FGS_VICTIM_LAUNCHED")
        SystemClock.sleep(20_000)
    }

    /**
     * Persistent FGS-down wall (ADR #32): `am stopservice` is refused (the service is
     * exported="false"), so the host stages the state here and then drives the scenario:
     * launch a restricted app → the a11y cover alone must wall it (assert NO
     * `START u0.*BlockerActivity` in the logcat window) → tap the cover's recovery button →
     * MainActivity resume restarts the FGS.
     *
     * Leave via the HOME intent, not finish() / finishAndRemoveTask(): this activity shares
     * the app task with MainActivity (non-root, so finishAndRemoveTask cannot remove the
     * task), and ANY exit that leaves MainActivity the task's top resumes it — its onResume
     * restarts the FGS (device-caught twice, 2026-09-13: restarts landed ~34ms after finish()
     * and again after finishAndRemoveTask). Bringing the launcher forward first keeps the app
     * task backgrounded, so nothing resumes and the FGS stays down until the host taps the
     * cover's recovery button — whose MainActivity start is the restore path under test.
     */
    private fun fgsDownSuite() {
        ForegroundBlockerService.stop(this)
        Log.i(TAG, "MARKER: FGS_STOPPED")
        runOnUiThread {
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            finish()
        }
    }

    /**
     * §12 outbox/idempotency + two-device round trip (one-device two-role through the REAL
     * Firestore relay — plan line 278 dedup by event ID): the coordinator's reconcile
     * turns the unlinked attempt into a chain event (needs the warm biometric window), the
     * drain delivers it to the pair's mailbox, the pull receives it back. Run 2 must
     * deliver nothing new — a duplicate partner notification is the failure this asserts.
     */
    private suspend fun syncSuite() {
        // On-device probe (2026-08-31): coordinator's explicit AndroidKeyStore getInstance
        // failed with NoSuchAlgorithmException on XOS while the harness's default-chain call
        // worked — establish the facts before changing production code.
        runCatching {
            val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            Log.i(TAG, "providers=${java.security.Security.getProviders().joinToString { it.name }}")
            Log.i(TAG, "signingAliasExists=${ks.containsAlias(com.example.myexodus.accountability.crypto.AccountabilityKeyStore.SIGNING_ALIAS)}")
        }
        val explicit = runCatching { java.security.Signature.getInstance("SHA256withECDSA", "AndroidKeyStore") }
        Log.i(TAG, "explicitGetInstance success=${explicit.isSuccess} err=${explicit.exceptionOrNull()?.message}")
        val def = runCatching { java.security.Signature.getInstance("SHA256withECDSA") }
        Log.i(TAG, "defaultGetInstance success=${def.isSuccess} err=${def.exceptionOrNull()?.message}")
        val coordinator = entryPoint.partnerSyncCoordinator()
        Log.i(TAG, "MARKER_SYNC_START")
        val run1 = coordinator.syncOnce()
        Log.i(TAG, "sync run 1: $run1")
        check(run1.enrolled && run1.uidsResolved) { "run 1 not enrolled/resolved: $run1" }
        check(run1.eventsLinked == 1) { "run 1 eventsLinked=${run1.eventsLinked} != 1: $run1" }
        check(run1.drained && run1.drainedCount == 1) { "run 1 drain != 1 delivered: $run1" }
        val run2 = coordinator.syncOnce()
        Log.i(TAG, "sync run 2 (idempotency): $run2")
        check(run2.eventsLinked == 0 && run2.drainedCount == 0) {
            "run 2 duplicated delivery: $run2"
        }
        Log.i(TAG, "sync round trip + idempotency passed (run1 pulled=${run1.pulledCount})")
    }

    private fun launchAllowedApp() {
        // Self-launch from the background is exempt from the background-activity-start
        // restriction, and bringing the suite back to the front refreshes the app's
        // background-start token — so the next victim launch always happens from a
        // foreground app. Launching a second app instead left the suite long-backgrounded,
        // and XOS intermittently blocked the following victim launch (no WSC, overlay never
        // shows — caught on-device 2026-08-31, twice in the latency suite).
        startActivity(
            Intent(this, DeviceVerificationActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun launchDialPad(number: String = "12345") {
        startActivity(Intent(Intent.ACTION_DIAL, "tel:$number".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun launchBrowser(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun launchPackage(pkg: String): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    /** RoleManager.getRoleHolders is trimmed from the compileSdk 37 stubs; reflective call. */
    private fun roleHolders(role: String): List<String> {
        @Suppress("InlinedApi") // ROLE_SERVICE is API 29; getSystemService(unknown) → null below it.
        val rm = getSystemService(Context.ROLE_SERVICE) ?: return emptyList()
        return runCatching {
            @Suppress("UNCHECKED_CAST")
            rm.javaClass.getMethod("getRoleHolders", String::class.java).invoke(rm, role) as List<String>
        }.getOrDefault(emptyList())
    }

    /** True once the victim sits in a pane: its a11y window is smaller than the display.
     *  Real metrics, same 50px slack as the isFullscreen skip-check below. */
    private fun waitForSplitEngaged(timeoutMs: Long) {
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = DisplayMetrics()
        checkNotNull(display).getRealMetrics(metrics)
        waitFor(timeoutMs) {
            val victim = BlockerAccessibilityService.instance?.visibleWindows()?.firstOrNull {
                runCatching { it.root?.packageName?.toString() == victimPackage }.getOrDefault(false)
            } ?: return@waitFor false
            val bounds = Rect().also { victim.getBoundsInScreen(it) }
            bounds.width() < metrics.widthPixels - 50 || bounds.height() < metrics.heightPixels - 50
        }
    }

    private fun waitForOverlay(shown: Boolean, timeoutMs: Long = 8_000) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (overlay.isShown(Display.DEFAULT_DISPLAY) == shown) return
            SystemClock.sleep(100)
        }
        error("overlay shown=${overlay.isShown(Display.DEFAULT_DISPLAY)} after ${timeoutMs}ms (wanted $shown)")
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean?) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition() == true) return
            SystemClock.sleep(100)
        }
        error("condition not met within ${timeoutMs}ms")
    }

    private companion object {
        const val TAG = "MyExodusVerification"
        const val EXTRA_SUITE = "suite"
        const val EXTRA_REQUIRE_SERVICE = "requireService"
        const val EXTRA_PRE_SPLIT = "preSplit"
        const val PROFILE_ID = "device-test-profile"
        const val UI_SEED_PREFIX = "ui-seed"
        val PROFILE = UserProfile(
            userId = PROFILE_ID,
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
    }
}
