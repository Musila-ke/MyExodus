package com.example.myexodus.blocking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Path B's pure decision logic (enforcement plan v2 §8 Step 5): the adaptive accounting
 * cadence and the wall's foreground-following lifecycle. Pure JVM — the poll loop, the
 * session feed, and the overlay host are device-verified in the §12 suite.
 */
class EnforcementForegroundServiceTest {

    // --- adaptive cadence (§8 Step 5: 30s baseline, 5s under 3 minutes remaining) ---

    @Test
    fun cadence_isThirtySecondsAtOrAboveThreeMinutes() {
        assertEquals(
            EnforcementForegroundService.USAGE_TICK_DEFAULT_MS,
            EnforcementForegroundService.usageTickIntervalMs(180_000L),
        )
    }

    @Test
    fun cadence_tightensJustUnderThreeMinutes() {
        assertEquals(
            EnforcementForegroundService.USAGE_TICK_TIGHT_MS,
            EnforcementForegroundService.usageTickIntervalMs(179_999L),
        )
    }

    @Test
    fun cadence_isTightAtZeroRemaining() {
        assertEquals(
            EnforcementForegroundService.USAGE_TICK_TIGHT_MS,
            EnforcementForegroundService.usageTickIntervalMs(0L),
        )
    }

    @Test
    fun cadence_unknownBudgetFallsBackToBaseline() {
        assertEquals(
            EnforcementForegroundService.USAGE_TICK_DEFAULT_MS,
            EnforcementForegroundService.usageTickIntervalMs(null),
        )
    }

    // --- wall lifecycle (§8 Step 5: the wall follows the foreground) ---

    @Test
    fun hideWall_differentForegroundPackage() {
        assertTrue(EnforcementForegroundService.shouldHideWall(APP, OTHER))
    }

    @Test
    fun hideWall_homeLauncherCountsAsADifferentPackage() {
        assertTrue(EnforcementForegroundService.shouldHideWall(APP, "com.android.launcher3"))
    }

    @Test
    fun keepWall_samePackageReForeground() {
        // The walled app's own churn (dialog, in-app hop) must not lift the wall.
        assertFalse(EnforcementForegroundService.shouldHideWall(APP, APP))
    }

    @Test
    fun keepWall_noForegroundEvidence() {
        // Null latest = no evidence of a change (failed query, empty window) — never a hide.
        assertFalse(EnforcementForegroundService.shouldHideWall(APP, null))
        assertFalse(EnforcementForegroundService.shouldHideWall(null, APP))
        assertFalse(EnforcementForegroundService.shouldHideWall(null, null))
    }

    // --- foreground observation carry-forward (device-caught 2026-09-15) ---

    @Test
    fun foregroundObservation_newEventReplacesIt() {
        assertEquals(OTHER, EnforcementForegroundService.currentForegroundAfterPoll(APP, OTHER))
    }

    @Test
    fun foregroundObservation_eventlessPollCarriesItForward() {
        // Every poll after the transition window has no new ACTIVITY_RESUMED — nulling the
        // observation here dropped every tick-originated warning/block before it could render
        // (X6532, 2026-09-15: the engine decided repeatedly, the overlay never showed).
        assertEquals(APP, EnforcementForegroundService.currentForegroundAfterPoll(APP, null))
    }

    @Test
    fun foregroundObservation_staysNullUntilFirstEvent() {
        assertNull(EnforcementForegroundService.currentForegroundAfterPoll(null, null))
    }

    private companion object {
        const val APP = "com.instagram.android"
        const val OTHER = "com.tiktok.android"
    }
}
