package com.example.myexodus.blocking

import android.app.KeyguardManager
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.Display
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Enhanced Mode §12 device matrix (implementation-plan §12): life-safety, tapjacking,
 * and browser-URL scenarios against the real bound service on the physical device.
 * Freezer survival and multi-window are separate classes driven by the host script.
 */
class EnhancedModeDeviceTest : EnhancedModeDeviceSuite() {

    @Test
    fun lifeSafety_defaultDialerAndEmergencyHolders_neverOverlaid() = runTest {
        resetData()
        seedProfile()
        seedRule(victimPackage, "APP")

        // Pipeline sanity first: the victim must be blocked, or a green life-safety result
        // proves nothing.
        launchVictim()
        waitForOverlay(true)

        // App-context RoleManager can return empty on some OEMs (role visibility gate,
        // observed on XOS 2026-08-27); the service-side fallback list covers those.
        val dialers = roleHolders("android.app.role.DIALER")
            .ifEmpty { EmergencyExclusionManager.FALLBACK_DIALER_PACKAGES.toList() }
        assertFalse("device must have a default dialer", dialers.isEmpty())
        dialers.forEach { pkg ->
            if (launchPackage(pkg)) {
                Log.i(TAG, "life-safety: launched default dialer $pkg")
                waitForOverlay(false)
            }
        }

        roleHolders("android.app.role.EMERGENCY").forEach { pkg ->
            if (launchPackage(pkg)) {
                Log.i(TAG, "life-safety: launched ROLE_EMERGENCY holder $pkg")
                waitForOverlay(false)
            }
        }
    }

    @Test
    fun lifeSafety_callSurfacesAndTransitions_hideOverlay() = runTest {
        resetData()
        seedProfile()
        seedRule(victimPackage, "APP")
        launchVictim()
        waitForOverlay(true)

        // Ordinary dial pad while the overlay is already visible: must hide.
        launchDialPad()
        waitForOverlay(false)

        // Emergency dial pad (112 prefilled, no call placed).
        launchDialPad("112")
        waitForOverlay(false)

        // Outgoing call: needs CALL_PHONE; if the OS denies it, record a skip, not a failure.
        val call = Intent(Intent.ACTION_CALL, android.net.Uri.parse("tel:12345"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(call) }.isSuccess) {
            Log.i(TAG, "life-safety: outgoing call in progress (in-call UI)")
            waitForOverlay(false)
        } else {
            Log.i(TAG, "life-safety: ACTION_CALL denied by OS — in-call UI scenario skipped")
        }
    }

    @Test
    fun lifeSafety_lockedDeviceAndKeyguard_neverOverlaid() = runTest {
        resetData()
        seedProfile()
        seedRule(victimPackage, "APP")
        launchVictim()
        waitForOverlay(true)

        val device = UiDevice.getInstance(instrumentation)
        device.sleep()
        SystemClock.sleep(2_000)
        device.wakeUp()
        SystemClock.sleep(2_000)

        val keyguard = context.getSystemService(KeyguardManager::class.java)
        assertFalse(
            "overlay must never be shown over the keyguard/lock screen",
            overlay.isShown(Display.DEFAULT_DISPLAY),
        )
        Log.i(TAG, "life-safety: keyguard locked during assertion=${keyguard.isKeyguardLocked}")

        shell("wm dismiss-keyguard")
        SystemClock.sleep(2_000)
        assertFalse(overlay.isShown(Display.DEFAULT_DISPLAY))
    }

    @Test
    fun tapjacking_overlayConsumesTouches_beneathGetsZero() = runTest {
        resetData()
        seedProfile()
        seedRule(victimPackage, "APP")
        TouchRecordingActivity.touches.set(0)

        launchVictim()
        waitForOverlay(true)
        val b = waitFor { TouchRecordingActivity.screenBounds }

        // Center of the victim window is beneath the overlay by construction
        // (decideApp overlays the window's boundsInScreen).
        injectTap(b[0] + b[2] / 2, b[1] + b[3] / 2)
        SystemClock.sleep(800)

        assertEquals(
            "touches must be consumed by the overlay; if >0 the OEM routes touches through " +
                "a non-focusable overlay and the §8 Step 5 OEM exception applies",
            0,
            TouchRecordingActivity.touches.get(),
        )
    }

    @Test
    fun browserUrl_matchedUrlBlocks_allowedUrlHides() = runTest {
        resetData()
        seedProfile()
        seedUrlRule()

        launchBrowser("http://example.com")
        waitForOverlay(true, timeoutMs = 15_000)

        launchBrowser("http://example.org")
        waitForOverlay(false, timeoutMs = 15_000)

        Log.i(TAG, "browser URL block/hide cycle passed on com.android.chrome")
    }

    private suspend fun seedUrlRule() = seedRule("example.com", "WEBSITE")

    private companion object {
        const val TAG = "MyExodusDeviceTest"
    }
}
