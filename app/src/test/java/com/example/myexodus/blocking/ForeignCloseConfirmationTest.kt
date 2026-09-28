package com.example.myexodus.blocking

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Path A's foreign-close confirmation (ADR #107, 2026-09-28): a foreign-package WSC may only
 * close the active usage session when Path B's poll oracle is fresh and disagrees — a
 * transient surface (shade, heads-up) that never pauses the tracked app must not end the
 * session. Pure decision logic; the defer/recheck flow is device-verified on the X6532.
 */
class ForeignCloseConfirmationTest {

    @Test
    fun defer_whenFreshOracleStillReportsTheActivePackage() {
        assertTrue(
            BlockerAccessibilityService.shouldDeferForeignClose(
                activePackage = APP,
                incomingPackage = SHADE,
                oraclePackage = APP,
                oracleAgeMs = 1_500L,
            ),
        )
    }

    @Test
    fun noDefer_whenOracleSaysAnotherPackageTookOver() {
        assertFalse(
            BlockerAccessibilityService.shouldDeferForeignClose(
                activePackage = APP,
                incomingPackage = OTHER,
                oraclePackage = OTHER,
                oracleAgeMs = 1_500L,
            ),
        )
    }

    @Test
    fun noDefer_whenOracleIsStale() {
        // A stopped or wedged FGS freezes its timestamp — silence is no evidence, and the
        // transition must close the session as it always did.
        assertFalse(
            BlockerAccessibilityService.shouldDeferForeignClose(
                activePackage = APP,
                incomingPackage = SHADE,
                oraclePackage = APP,
                oracleAgeMs = 30_000L,
            ),
        )
    }

    @Test
    fun noDefer_whenNoOracleHasEverReported() {
        assertFalse(
            BlockerAccessibilityService.shouldDeferForeignClose(
                activePackage = APP,
                incomingPackage = SHADE,
                oraclePackage = null,
                oracleAgeMs = 1_000L,
            ),
        )
    }

    @Test
    fun noDefer_whenIncomingIsTheActivePackage() {
        // Same-package churn never closes the session; the rule must not be consulted for it.
        assertFalse(
            BlockerAccessibilityService.shouldDeferForeignClose(
                activePackage = APP,
                incomingPackage = APP,
                oraclePackage = APP,
                oracleAgeMs = 1_500L,
            ),
        )
    }

    @Test
    fun noDefer_whenAgeIsNegative() {
        // Clock oddities must never read as "fresh enough".
        assertFalse(
            BlockerAccessibilityService.shouldDeferForeignClose(
                activePackage = APP,
                incomingPackage = SHADE,
                oraclePackage = APP,
                oracleAgeMs = -1L,
            ),
        )
    }

    @Test
    fun confirms_onlyForTheSamePackageWithinFreshness() {
        assertTrue(BlockerAccessibilityService.platformForegroundConfirms(APP, APP, 4_999L))
        assertFalse(BlockerAccessibilityService.platformForegroundConfirms(APP, APP, 5_001L))
        assertFalse(BlockerAccessibilityService.platformForegroundConfirms(APP, OTHER, 0L))
        assertFalse(BlockerAccessibilityService.platformForegroundConfirms(APP, null, 0L))
    }

    private companion object {
        const val APP = "com.instagram.android"
        const val OTHER = "com.tiktok.android"
        const val SHADE = "com.android.systemui"
    }
}
