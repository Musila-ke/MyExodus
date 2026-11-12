package com.example.myexodus.blocking

import android.app.Application
import android.graphics.Rect
import android.view.accessibility.AccessibilityWindowInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Plain [Application], not MyExodusApp: the account-era startup graph constructs FirebaseAuth
 *  before Robolectric would have initialized FirebaseApp (the real app gets that from
 *  FirebaseInitProvider; Robolectric skips it). Nothing here needs the graph — construction
 *  happens before Hilt injection, and the rest are pure functions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BlockerAccessibilityServiceConstructionTest {

    @Test
    fun serviceConstructs_withoutReadingInjectedFieldsInInitializers() {
        // Regression (2026-08-26, on-device crash): the scanner's field initializer read
        // `this.engine` before Hilt's onCreate injection ran →
        // UninitializedPropertyAccessException on accessibility bind. Construction exercises
        // exactly the field-initializer path; injection happens later in onCreate (which this
        // deliberately does not call — it would need the Hilt graph).
        Robolectric.buildService(BlockerAccessibilityService::class.java).get()
    }

    @Test
    fun coverTarget_keepsAPaneWhileItsAppIsForeground() {
        val pane = Rect(0, 80, 480, 1520)
        val display = Rect(0, 80, 720, 1520)
        assertEquals(pane, BlockerAccessibilityService.coverTarget(true, pane, display))
    }

    @Test
    fun coverTarget_neverNarrowsToACenteredSubWindow() {
        // Owner-caught 2026-11-12: a cold AnkiDroid launch listed ONLY its 684x563 backup
        // dialog (XOS window-list lag), and the cover settled on that card with the
        // DeckPicker readable around it. A non-pane rect always covers the whole display.
        val display = Rect(0, 0, 720, 1520)
        val dialog = Rect(18, 518, 702, 1081)
        assertEquals(display, BlockerAccessibilityService.coverTarget(true, dialog, display))
    }

    @Test
    fun isSplitPane_recognizesBothSplitOrientationsAndRejectsDialogs() {
        val display = Rect(0, 0, 720, 1520)
        assertTrue("top/bottom split: full width", BlockerAccessibilityService.isSplitPane(Rect(0, 0, 720, 760), display))
        assertTrue("side-by-side split: full height", BlockerAccessibilityService.isSplitPane(Rect(0, 0, 360, 1520), display))
        assertTrue(
            "a full-screen window behind the bars still spans the display",
            BlockerAccessibilityService.isSplitPane(Rect(0, 0, 720, 1600), display),
        )
        assertFalse(
            "a centered dialog spans neither dimension",
            BlockerAccessibilityService.isSplitPane(Rect(18, 518, 702, 1081), display),
        )
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

    @Test
    fun ownBlockScreenIsFront_onlyTheDurableWallSuppressesEviction() {
        // Regression (emulator-5554, 2026-11-02): late duplicate WSCs for a blocked app
        // re-evicted while BlockerActivity was front; the global HOME pushed our own wall
        // to the back in the same gesture the user expected to show it.
        assertTrue(
            BlockerAccessibilityService.ownBlockScreenIsFront(
                "com.example.myexodus", AccessibilityWindowInfo.TYPE_APPLICATION, "com.example.myexodus",
            ),
        )
        // The focusable daily wall is our overlay, not our activity: the covered app behind
        // it still has to be evicted (2026-09-27 device-caught audio under the wall).
        assertFalse(
            BlockerAccessibilityService.ownBlockScreenIsFront(
                "com.example.myexodus", AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY, "com.example.myexodus",
            ),
        )
        assertFalse(
            BlockerAccessibilityService.ownBlockScreenIsFront(
                "com.google.android.youtube", AccessibilityWindowInfo.TYPE_APPLICATION, "com.example.myexodus",
            ),
        )
        assertFalse(
            BlockerAccessibilityService.ownBlockScreenIsFront(null, -1, "com.example.myexodus"),
        )
    }

    @Test
    fun wallLaunchInFlight_suppressesHomeOnlyForTheJustLaunchedWall() {
        // Regression (emulator-5554, 2026-11-02): the FGS launched BlockerActivity, then five
        // queued WSCs each fired a global HOME during its launch transition — root still read
        // the blocked app (then null), so the wall never came up ("flash and vanish").
        val launchedAt = 100_000L
        val photos = "com.google.android.apps.photos"
        assertTrue(
            "The wall's own launch window must suppress the eviction HOME",
            BlockerAccessibilityService.wallLaunchInFlight(launchedAt + 800, photos, launchedAt, photos),
        )
        assertFalse(
            "Past the launch window the normal eviction path resumes",
            BlockerAccessibilityService.wallLaunchInFlight(
                launchedAt + BlockerAccessibilityService.WALL_LAUNCH_SUPPRESS_MS + 1, photos, launchedAt, photos,
            ),
        )
        assertFalse(
            "Another package's launch must not shield this target",
            BlockerAccessibilityService.wallLaunchInFlight(
                launchedAt + 800, "com.android.chrome", launchedAt, photos,
            ),
        )
        assertFalse(
            "No wall launch latched: nothing to suppress",
            BlockerAccessibilityService.wallLaunchInFlight(launchedAt + 800, null, 0L, photos),
        )
    }
}
