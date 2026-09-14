package com.example.myexodus.blocking

import android.app.Application
import android.app.Dialog
import android.graphics.Rect
import android.os.DeadObjectException
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Overlay lifecycle, generation gating, and pure decision logic (plan §8 Step 5).
 *  Touch consumption and FrameMetrics are exercised on-device (§12 suite). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OverlayControllerTest {

    private lateinit var controller: OverlayController
    private lateinit var stateMachine: ForegroundStateMachine

    @Before
    fun setUp() {
        stateMachine = ForegroundStateMachine()
        controller = OverlayController(
            appContext = ApplicationProvider.getApplicationContext(),
            stateMachine = stateMachine,
            profiler = LatencyProfiler(Application(), sampleDenominator = 1),
            onOpenRecovery = {},
            onDifferentSite = {},
        )
    }

    private fun advance(): Long = stateMachine.advance("com.instagram.android", 1, detectionTimeNanos = 1L)

    private fun cover() = OverlayContent.AppBlocked("com.instagram.android", "r-instagram")

    // --- generation gating ---

    @Test
    fun staleGeneration_showIsANoOp() {
        val stale = stateMachine.advance("com.instagram.android", 1, detectionTimeNanos = 1L)
        stateMachine.advance("com.tiktok.android", 2, detectionTimeNanos = 2L) // real transition
        controller.show(stale, displayId = 0, bounds = fullScreen(), content = cover())
        assertFalse(controller.isShown(0))
    }

    @Test
    fun staleGeneration_hideIsANoOp() {
        val g1 = stateMachine.advance("com.instagram.android", 1, detectionTimeNanos = 1L)
        controller.show(g1, displayId = 0, bounds = fullScreen(), content = cover())
        assertTrue(controller.isShown(0))
        val g2 = stateMachine.advance("com.tiktok.android", 2, detectionTimeNanos = 2L) // real transition
        controller.hide(g1, displayId = 0) // stale hide must not dismiss a newer block
        assertTrue(controller.isShown(0))
        controller.hide(g2, displayId = 0)
        assertFalse(controller.isShown(0))
    }

    // --- show/hide lifecycle ---

    @Test
    fun showThenHide_tracksShownState() {
        val gen = advance()
        assertFalse(controller.isShown(0))
        controller.show(gen, displayId = 0, bounds = fullScreen(), content = cover())
        assertTrue(controller.isShown(0))
        controller.hide(gen, displayId = 0)
        assertFalse(controller.isShown(0))
    }

    @Test
    fun secondShowOnSameDisplay_reBoundsInsteadOfSkipping() {
        val gen = advance()
        controller.show(gen, displayId = 0, bounds = fullScreen(), content = cover())
        // The service follows its display-bounds show with the restricted window's bounds
        // (multi-window pass); the already-shown cover must follow, or it freezes at
        // whole-display size and swallows the allowed pane (emulator-5554 split, 2026-09-14).
        controller.show(gen, displayId = 0, bounds = Rect(0, 1000, 1080, 2400), content = cover())
        assertTrue(controller.isShown(0))
        val attributes = dialog(0).window!!.attributes
        assertEquals(1080, attributes.width)
        assertEquals(1400, attributes.height)
        assertEquals(1000, attributes.y)
        controller.hide(gen, displayId = 0)
        assertFalse(controller.isShown(0))
    }

    @Suppress("UNCHECKED_CAST")
    private fun dialog(displayId: Int): Dialog {
        val dialogs = ReflectionHelpers.getField<Any>(controller, "dialogs") as Map<Int, Dialog>
        return dialogs[displayId]!!
    }

    @Test
    fun currentContent_tracksTheVisibleCard() {
        val gen = advance()
        assertNull(controller.currentContent(0))
        val card = OverlayContent.UrlBlocked("example.com", "r1", "com.android.chrome")
        controller.show(gen, displayId = 0, bounds = fullScreen(), content = card)
        assertEquals(card, controller.currentContent(0))
        controller.hide(gen, displayId = 0)
        // The service's browser-WSC gate reads this: a stale card type would re-scan for a
        // lifted app block (or hide a live URL card).
        assertNull(controller.currentContent(0))
    }

    @Test
    fun detachAll_hidesEverything() {
        val gen = advance()
        controller.show(gen, displayId = 0, bounds = fullScreen(), content = cover())
        assertTrue(controller.isShown(0))
        controller.detachAll()
        assertFalse(controller.isShown(0))
    }

    private fun fullScreen() = Rect(0, 0, 1080, 2400)

    // --- failure categorization (pure) ---

    @Test
    fun failureCategories_areExactPerException() {
        assertEquals("bad-token", OverlayController.categorizeShowFailure(WindowManager.BadTokenException()))
        assertEquals("revoked-permission", OverlayController.categorizeShowFailure(SecurityException()))
        assertEquals("already-attached", OverlayController.categorizeShowFailure(IllegalStateException()))
        assertEquals("dead-system-server", OverlayController.categorizeShowFailure(DeadObjectException()))
        assertEquals("unknown(ArithmeticException)", OverlayController.categorizeShowFailure(ArithmeticException()))
    }

    // --- OEM touch-passthrough exception (pure) ---

    @Test
    fun takeFocus_matchesManufacturerAndSdk() {
        val overrides = setOf("samsung" to 33, "xiaomi" to null)
        assertTrue(OverlayController.shouldTakeFocus("SAMSUNG", 33, overrides))
        assertTrue(OverlayController.shouldTakeFocus("xiaomi", 35, overrides)) // any sdk
        assertFalse(OverlayController.shouldTakeFocus("samsung", 34, overrides))
        assertFalse(OverlayController.shouldTakeFocus("google", 33, overrides))
        assertFalse(OverlayController.shouldTakeFocus(null, 33, overrides))
        assertFalse(OverlayController.shouldTakeFocus("samsung", 33, emptySet()))
    }
}
