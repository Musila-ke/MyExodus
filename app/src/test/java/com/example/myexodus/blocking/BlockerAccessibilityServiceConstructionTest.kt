package com.example.myexodus.blocking

import android.graphics.Rect
import com.example.myexodus.MyExodusApp
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = MyExodusApp::class)
class BlockerAccessibilityServiceConstructionTest {

    @Test
    fun serviceConstructs_withoutReadingInjectedFieldsInInitializers() {
        // Regression (2026-08-26, on-device crash): the scanner's field initializer read
        // `this.engine` before Hilt's onCreate injection ran →
        // UninitializedPropertyAccessException on accessibility bind. Construction exercises
        // exactly the field-initializer path; injection happens later in onCreate.
        Robolectric.buildService(BlockerAccessibilityService::class.java).create()
    }

    @Test
    fun coverTarget_keepsAPaneWhileItsAppIsForeground() {
        val pane = Rect(0, 80, 480, 1520)
        val display = Rect(0, 80, 720, 1520)
        assertEquals(pane, BlockerAccessibilityService.coverTarget(true, pane, display))
    }

    @Test
    fun coverTarget_ignoresTheExitAnimationRectOnceTheAppIsEvicted() {
        // Regression (X6532, 2026-10-18): the evicted app's window stayed listed at its
        // 480x1440 exit-animation rect; the settle pass froze the card there and it
        // persisted over the launcher. A non-foreground window rect is never the target.
        val animationRect = Rect(0, 80, 480, 1520)
        val display = Rect(0, 80, 720, 1520)
        assertEquals(display, BlockerAccessibilityService.coverTarget(false, animationRect, display))
    }

    @Test
    fun coverTarget_fallsBackToTheDisplayWhenTheWindowListIsMomentarilyEmpty() {
        val display = Rect(0, 80, 720, 1520)
        assertEquals(display, BlockerAccessibilityService.coverTarget(true, null, display))
        assertEquals(display, BlockerAccessibilityService.coverTarget(false, null, display))
    }
}
