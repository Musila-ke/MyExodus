package com.example.myexodus.ui.recovery

import com.example.myexodus.data.db.entity.RestrictionRule
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Session liveness for the Recovery tab: a stale session (paused, expired, window over,
 *  rule gone) must read as idle instead of showing a countdown for a block that is not live. */
class RecoverySessionTest {

    private val noonUtc: Long = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        set(2026, Calendar.SEPTEMBER, 13, 12, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private val alwaysOn = RestrictionRule(
        ruleId = "r-1",
        profileId = "p-1",
        blockedPackage = "com.instagram.android",
        targetType = "APP",
        isAlwaysOn = true,
        dailyLimitMs = 0L,
        timeRangeStart = null,
        timeRangeEnd = null,
        activeDaysOfWeek = "1111111",
        timezone = "UTC",
        blockExpiresAt = 0L,
        severityLevel = "HIGH",
    )

    @Test
    fun liveSessionRule_keepsEnabledBlockingRule() {
        assertNotNull(liveSessionRule(alwaysOn, noonUtc))
    }

    @Test
    fun liveSessionRule_dropsPausedExpiredAndDeleted() {
        assertNull(liveSessionRule(null, noonUtc))
        assertNull(liveSessionRule(alwaysOn.copy(isEnabled = false), noonUtc))
        assertNull(liveSessionRule(alwaysOn.copy(blockExpiresAt = noonUtc - 1_000), noonUtc))
    }

    @Test
    fun liveSessionRule_followsWindowAndTimezoneNotTheDeviceClock() {
        // Overnight window 22:00-07:00 UTC: noon is outside it, so the session is over.
        val overnight = alwaysOn.copy(
            isAlwaysOn = false,
            timeRangeStart = 22 * 60,
            timeRangeEnd = 7 * 60,
        )
        assertNull(liveSessionRule(overnight, noonUtc))
        // 12:00-14:00 UTC covers noon; a device default zone must not change the verdict.
        val midday = overnight.copy(timeRangeStart = 12 * 60, timeRangeEnd = 14 * 60)
        assertEquals("r-1", liveSessionRule(midday, noonUtc)?.ruleId)
    }
}
