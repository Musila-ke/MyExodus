package com.example.myexodus.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The 5-minute manual throttle applies only when the stored success is in the past. */
class UpdateManualThrottleTest {

    private val now = 10_000_000L

    @Test
    fun aRecentPastSuccessThrottlesAManualCheck() {
        assertTrue(manualCheckThrottled(true, CheckStatus.SUCCESS, now - 1_000L, now))
    }

    @Test
    fun theThrottleEndsAfterFiveMinutes() {
        val ending = now - UpdateConfig.MANUAL_THROTTLE_MS
        assertFalse(manualCheckThrottled(true, CheckStatus.SUCCESS, ending, now))
    }

    @Test
    fun aBackwardClockChangeLeavesTheStoredSuccessInTheFuture() {
        assertFalse(manualCheckThrottled(true, CheckStatus.SUCCESS, now + 60_000L, now))
    }

    @Test
    fun nonManualChecksAndFailedStatusesAreNeverThrottled() {
        assertFalse(manualCheckThrottled(false, CheckStatus.SUCCESS, now - 1_000L, now))
        assertFalse(manualCheckThrottled(true, CheckStatus.FAILED, now - 1_000L, now))
        assertFalse(manualCheckThrottled(true, CheckStatus.IDLE, now - 1_000L, now))
    }
}
