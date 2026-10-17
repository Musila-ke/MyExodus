package com.example.myexodus.blocking

import android.app.Instrumentation
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myexodus.MainActivity
import com.example.myexodus.data.db.ActivityLogKeyManager
import com.example.myexodus.data.db.AndroidDatabaseKeyManager
import com.example.myexodus.data.db.DatabaseHealthStore
import com.example.myexodus.data.db.RecoveryManager
import com.example.myexodus.data.db.RulesStoreKeyManager
import com.example.myexodus.data.db.entity.RestrictionRule
import com.example.myexodus.data.db.entity.UserProfile
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before

/**
 * Shared scaffolding for the Enhanced Mode §12 device suite (implementation-plan §12). All
 * tests run against the real bound accessibility service on the physical device; every test
 * seeds its own rules from a clean database and observes the service's @Singleton instances
 * via [EnhancedModeTestEntryPoint] (same process, same instances).
 *
 * Host prerequisites (see scripts/enhanced-mode-device-test.sh): app installed, accessibility
 * service enabled and bound, XOS hiber whitelist active.
 */
abstract class EnhancedModeDeviceSuite {

    protected val context: Context = ApplicationProvider.getApplicationContext()
    protected val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()

    /** Flags 0: the harness only injects input events + runs shell commands, which ignore the
     *  flags. 0x8 was never a defined UiAutomation flag (AOSP-verified 2026-08-28 — 0x1
     *  DONT_SUPPRESS, 0x2 DONT_USE, 0x4 NOT_ACCESSIBILITY_TOOL are the only ones, and our
     *  window content comes from the service, not the UiAutomation); passing undefined bits
     *  risks them acquiring meaning in a future release. */
    protected val uiAutomation: UiAutomation = instrumentation.getUiAutomation(0)

    protected val entryPoint: EnhancedModeTestEntryPoint by lazy {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            EnhancedModeTestEntryPoint::class.java,
        )
    }

    // OverlayController is per-bind service state, not a DI singleton (2026-08-28 review) —
    // reach it through the live service instance.
    protected val overlay get() = checkNotNull(BlockerAccessibilityService.instance?.overlay) {
        "BlockerAccessibilityService not bound"
    }

    protected val appPackage: String = context.packageName

    /** The test APK shares the app's UID/process under the .test applicationId, so a rule
     *  against it passes the self-block CHECK while giving the suite a controllable victim. */
    protected val victimPackage: String = "$appPackage.test"

    protected val victimComponent: ComponentName =
        ComponentName(victimPackage, "$appPackage.blocking.TouchRecordingActivity")

    @Before
    fun waitForServiceConnection() {
        // XOS quirk (device-verified 2026-08-26): `am instrument` force-stops the app and
        // starts the process fresh, and a fresh process never binds the accessibility service
        // on this device — the reliable trigger is a CHANGE to enabled_accessibility_services
        // while the process is alive (we are: the suite runs in the app's own process).
        // Cycling dummy->real guarantees a change event even when the setting already holds
        // the real value; accessibility_enabled=1 also self-heals the Hiber-cleared case.
        // Every readback is logged to logcat (tag $TAG) so host-side debugging can see
        // whether the executeShellCommand writes actually land.
        val svc = "$appPackage/$appPackage.blocking.BlockerAccessibilityService"
        val dummy = "$appPackage/$appPackage.blocking.NonexistentService"
        val deadline = SystemClock.elapsedRealtime() + 60_000
        var lastCycleAt = Long.MIN_VALUE
        var cycles = 0
        var lastGet = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            if (BlockerAccessibilityService.connected.get()) {
                Log.i(TAG, "service connected after ${cycles} cycles (last readback [$lastGet])")
                return
            }
            if (SystemClock.elapsedRealtime() >= lastCycleAt + 10_000) {
                lastGet = shell("settings get secure enabled_accessibility_services").trim()
                val out = shell(
                    "settings put secure accessibility_enabled 1; " +
                        "settings put secure enabled_accessibility_services $dummy; " +
                        "settings put secure enabled_accessibility_services $svc",
                ).trim()
                cycles++
                Log.i(TAG, "bind cycle #${cycles}: readback-before=[$lastGet] write-out=[$out]")
                lastCycleAt = SystemClock.elapsedRealtime()
            }
            SystemClock.sleep(500)
        }
        fail(
            "BlockerAccessibilityService never connected after ${cycles} cycles " +
                "(settings readback [$lastGet])",
        )
    }

    // --- database seeding -------------------------------------------------------------

    protected fun resetData() {
        // Wipes the LIVE store on whatever device this runs on — intended on the physical
        // device (the suite must seed from a clean store), cost-stated in ADR #122 honest
        // limit (b). Never run this suite on a handset whose rules/ledger/pairing-era data
        // matters without the owner knowing; the throwaway-store change is a product call.
        val healthStore = DatabaseHealthStore()
        val recovery = RecoveryManager(
            context,
            AndroidDatabaseKeyManager(context),
            ActivityLogKeyManager(context),
            RulesStoreKeyManager(context),
            healthStore,
        )
        recovery.resetAllData()
    }

    protected suspend fun seedProfile() {
        entryPoint.appRulesStoreFactory().open()!!.userProfileDao().insert(
            UserProfile(
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
            ),
        )
    }

    /** Inserting via the repository also republishes the singleton snapshot the engine reads. */
    protected suspend fun seedRule(blocked: String, targetType: String) {
        entryPoint.rulesRepository().insertRule(
            RestrictionRule(
                ruleId = "rule-${System.nanoTime()}",
                profileId = PROFILE_ID,
                blockedPackage = blocked,
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

    // --- UI driving -------------------------------------------------------------------

    protected fun launchVictim() {
        context.startActivity(
            Intent().setComponent(victimComponent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    protected fun launchAllowedApp() {
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    protected fun launchDialPad(number: String = "12345") {
        context.startActivity(
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    protected fun launchBrowser(url: String) {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Launches the package's main activity; returns false when it has no launchable entry. */
    protected fun launchPackage(pkg: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    /** RoleManager.getRoleHolders is trimmed from the compileSdk 37 stubs; reflective call. */
    protected fun roleHolders(role: String): List<String> {
        val rm = context.getSystemService("role") ?: return emptyList()
        return runCatching {
            @Suppress("UNCHECKED_CAST")
            rm.javaClass.getMethod("getRoleHolders", String::class.java).invoke(rm, role) as List<String>
        }.getOrDefault(emptyList())
    }

    protected fun injectTap(x: Int, y: Int) {
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(
            downTime, downTime, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), 0,
        )
        assertTrue("touch dispatch failed", uiAutomation.injectInputEvent(down, true))
        val up = MotionEvent.obtain(
            downTime, downTime + 50, MotionEvent.ACTION_UP, x.toFloat(), y.toFloat(), 0,
        )
        assertTrue("touch dispatch failed", uiAutomation.injectInputEvent(up, true))
    }

    // --- waiting ----------------------------------------------------------------------

    protected fun waitForOverlay(shown: Boolean, timeoutMs: Long = 8_000) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (overlay.isShown(Display.DEFAULT_DISPLAY) == shown) return
            SystemClock.sleep(100)
        }
        fail(
            "overlay shown=${overlay.isShown(Display.DEFAULT_DISPLAY)} after ${timeoutMs}ms " +
                "(wanted $shown)",
        )
    }

    protected fun <T> waitFor(timeoutMs: Long = 8_000, get: () -> T?): T {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            get()?.let { return it }
            SystemClock.sleep(100)
        }
        fail("condition not met within ${timeoutMs}ms")
    }

    protected fun shell(cmd: String): String {
        val pfd = instrumentation.uiAutomation.executeShellCommand(cmd) ?: return ""
        return pfd.use { p ->
            p.fileDescriptor.let { fd ->
                if (fd.valid()) java.io.FileInputStream(fd).readBytes().decodeToString() else ""
            }
        }
    }

    /** kotlin.test isn't on the androidTest classpath; JUnit4's fail returns void. */
    protected fun fail(message: String): Nothing = throw AssertionError(message)

    protected companion object {
        const val PROFILE_ID = "device-test-profile"
        const val TAG = "MyExodusSuite"
    }
}
