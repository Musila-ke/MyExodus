package com.example.myexodus.ui.partner

import com.example.myexodus.accountability.state.ChainHead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneOffset

/** Pins the dashboard's pure mapping (plan §7 item 12 / FR3 / §9 rows 457-458). */
class PartnerPresentationTest {

    private val now = 1_700_000_000_000L

    @Test
    fun freshness_neverWhenNoWatermark() {
        assertEquals(SyncFreshness.NEVER, syncFreshness(null, now))
    }

    @Test
    fun freshness_buckets() {
        assertEquals(SyncFreshness.FRESH, syncFreshness(now - 30 * 60_000L, now))
        assertEquals(SyncFreshness.AGING, syncFreshness(now - 3 * 3_600_000L, now))
        assertEquals(SyncFreshness.STALE, syncFreshness(now - 25 * 3_600_000L, now))
    }

    @Test
    fun freshness_clockSkewedFutureTimestamp_readsFreshNeverStale() {
        assertEquals(SyncFreshness.FRESH, syncFreshness(now + 3_600_000L, now))
    }

    @Test
    fun relativeTime_buckets() {
        assertEquals(RelativeTime.JustNow, relativeTime(now, now - 30_000L))
        assertEquals(RelativeTime.Minutes(5), relativeTime(now, now - 5 * 60_000L))
        assertEquals(RelativeTime.Hours(3), relativeTime(now, now - 3 * 3_600_000L))
        assertEquals(RelativeTime.Days(2), relativeTime(now, now - 2 * 86_400_000L))
    }

    @Test
    fun relativeTime_olderThanAWeek_keepsTheRealTimestamp() {
        val old = now - 8 * 86_400_000L
        assertEquals(RelativeTime.Older(old), relativeTime(now, old))
    }

    @Test
    fun chainStatus_maps_everyStoredRepairState() {
        assertEquals(PartnerChainStatus.HEALTHY, chainStatusFor(ChainHead.REPAIR_NONE, forked = false))
        assertEquals(PartnerChainStatus.CATCHING_UP, chainStatusFor(ChainHead.REPAIR_PENDING, forked = false))
        assertEquals(PartnerChainStatus.CATCHING_UP, chainStatusFor(ChainHead.REPAIR_RECOVERY_EXHAUSTED, forked = false))
        assertEquals(PartnerChainStatus.INCOMPLETE, chainStatusFor(ChainHead.REPAIR_CHAIN_INCOMPLETE, forked = false))
        assertEquals(PartnerChainStatus.UNVERIFIED, chainStatusFor(ChainHead.REPAIR_TAMPER_SUSPECTED, forked = false))
        assertEquals(PartnerChainStatus.UNVERIFIED, chainStatusFor(ChainHead.REPAIR_NONE, forked = true))
    }

    @Test
    fun activityText_parsesBothEventKinds() {
        assertEquals(PartnerAlert("com.x", true, 42L), parseActivityText("override:com.x", 42L))
        assertEquals(PartnerAlert("com.x", false, 42L), parseActivityText("blocked:com.x", 42L))
    }

    @Test
    fun activityText_nonActivityPayload_isNull_notPresented() {
        assertNull(parseActivityText("sync_request", 42L))
        assertNull(parseActivityText("", 42L))
        assertNull(parseActivityText("something-else", 42L))
    }

    @Test
    fun summarize_countsFromTheLocalDayBoundary_andCapsRecentAtFive() {
        val startOfToday = now - 3 * 3_600_000L
        val newestFirst = (0 until 7).map {
            PartnerAlert("com.x$it", false, startOfToday - it * 3_600_000L)
        }
        val summary = summarizeAlerts(newestFirst, startOfToday)
        assertEquals(7, summary.weekCount)
        assertEquals(1, summary.todayCount)
        assertEquals(5, summary.recent.size)
        assertEquals("com.x0", summary.recent.first().target)
    }

    @Test
    fun startOfLocalDay_isTheZoneBoundary() {
        // 2023-11-14T21:30Z → midnight UTC of 2023-11-14.
        val expected = 1_699_920_000_000L
        assertEquals(expected, startOfLocalDay(1_700_000_000_000L, ZoneOffset.UTC))
    }
}
