package com.example.myexodus.blocking

import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Enhanced Mode §12 multi-window test: with split-screen engaged (host script; `am start
 * --windowing-mode` is absent on this XOS build, so the gesture is manual), the overlay must
 * cover only the restricted pane's window on the correct displayId and nothing of the allowed
 * pane. Skips gracefully when split-screen isn't engaged.
 */
class EnhancedModeMultiWindowTest : EnhancedModeDeviceSuite() {

    @Test
    fun splitScreen_onlyRestrictedPaneOverlaid() = runTest {
        resetData()
        seedProfile()
        seedRule(victimPackage, "APP")
        launchVictim()
        waitForOverlay(true)

        val windows = uiAutomation.getWindows()
        val victimWindow = windows.firstOrNull {
            runCatching { it.root?.packageName?.toString() == victimPackage }.getOrDefault(false)
        }
        assertNotNull("victim window must be visible to the test connection", victimWindow)

        // Split engaged iff the victim occupies less than the full display.
        val display = context.getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = DisplayMetrics()
        display!!.getRealMetrics(metrics)
        val vb = Rect().also { victimWindow!!.getBoundsInScreen(it) }
        val isFullscreen =
            vb.width() >= metrics.widthPixels - 50 && vb.height() >= metrics.heightPixels - 50
        if (isFullscreen) {
            Log.i(TAG, "split-screen not engaged — skipping (manual XOS split gesture required)")
            return@runTest
        }

        val overlayWindow = windows.firstOrNull {
            it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
        }
        assertNotNull("overlay window must be visible to the test connection", overlayWindow)
        val ob = Rect().also { overlayWindow!!.getBoundsInScreen(it) }

        // Restricted pane: overlay covers the victim's window.
        assertTrue(
            "overlay $ob must cover the restricted pane $vb",
            ob.contains(vb.left + vb.width() / 2, vb.top + vb.height() / 2),
        )
        // Allowed pane: with a 50/50 split the display center belongs to the other pane.
        val cx = metrics.widthPixels / 2
        val cy = metrics.heightPixels / 2
        assertFalse(
            "overlay $ob must not cover the allowed pane (display center $cx,$cy)",
            ob.contains(cx, cy),
        )
        // Correct displayId.
        assertTrue(overlay.isShown(Display.DEFAULT_DISPLAY))
        Log.i(TAG, "multi-window: overlay bounds $ob restricted to victim pane $vb")
    }

    private companion object {
        const val TAG = "MyExodusMultiWindow"
    }
}
