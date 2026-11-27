package com.example.myexodus.ui.settings

import com.example.myexodus.update.DownloadSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateProgressTest {

    @Test
    fun unknownTotalNeverProducesADeterminatePercent() {
        assertNull(downloadPercent(null))
        assertNull(downloadPercent(DownloadSnapshot.Running(0L, -1L)))
        assertNull(downloadPercent(DownloadSnapshot.Running(2048L, 0L)))
        assertNull(downloadPercent(DownloadSnapshot.Paused(downloadedBytes = 1024L)))
    }

    @Test
    fun knownTotalProducesAClampedPercent() {
        assertEquals(0, downloadPercent(DownloadSnapshot.Running(0L, 2048L)))
        assertEquals(50, downloadPercent(DownloadSnapshot.Running(1024L, 2048L)))
        assertEquals(100, downloadPercent(DownloadSnapshot.Running(2048L, 2048L)))
        assertEquals(100, downloadPercent(DownloadSnapshot.Running(4096L, 2048L)))
    }

    @Test
    fun stallFlagsOnlyAfterTheByteCountStopsMoving() {
        val tracker = DownloadStallTracker(stallMs = 180_000L)
        assertFalse(tracker.observe("attempt", 0L, nowMs = 0L))
        assertFalse(tracker.observe("attempt", 1024L, nowMs = 60_000L))
        assertFalse(tracker.observe("attempt", 1024L, nowMs = 239_999L))
        assertTrue(tracker.observe("attempt", 1024L, nowMs = 240_000L))
        assertTrue(tracker.observe("attempt", 1024L, nowMs = 300_000L))
    }

    @Test
    fun byteProgressClearsTheStall() {
        val tracker = DownloadStallTracker(stallMs = 180_000L)
        tracker.observe("attempt", 0L, nowMs = 0L)
        assertTrue(tracker.observe("attempt", 0L, nowMs = 180_000L))
        assertFalse(tracker.observe("attempt", 4096L, nowMs = 180_001L))
    }

    @Test
    fun pausedOrNewAttemptResetsTheTracker() {
        val tracker = DownloadStallTracker(stallMs = 180_000L)
        tracker.observe("attempt", 0L, nowMs = 0L)
        assertTrue(tracker.observe("attempt", 0L, nowMs = 180_000L))
        assertFalse(tracker.observe(null, null, nowMs = 180_001L))
        assertFalse(tracker.observe("attempt", 0L, nowMs = 400_000L))
        tracker.observe("attempt", 0L, nowMs = 500_000L)
        assertTrue(tracker.observe("attempt", 0L, nowMs = 680_000L))
        assertFalse(tracker.observe("other", 0L, nowMs = 900_000L))
    }
}
