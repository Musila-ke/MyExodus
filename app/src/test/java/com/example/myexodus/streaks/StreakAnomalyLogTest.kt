package com.example.myexodus.streaks

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StreakAnomalyLogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val anomaly = StreakAnomaly(
        type = StreakAnomalyType.BACKWARD_CLOCK_AFTER_REBOOT,
        tWallMillis = 1_789_600_000_000L,
        rBootMillis = 1_234,
        bootCount = 42,
        deltaMs = -60_000,
        resolution = AnomalyResolution.REJECTED_NO_ANCHOR_UPDATE,
    )

    @Test
    fun append_writesOneLinePerEventWithAllSection14Fields() {
        val file = tmp.newFile("anomalies.log")
        val log = StreakAnomalyLog(file)

        log.append(anomaly)
        log.append(anomaly.copy(type = StreakAnomalyType.REBOOT_WALL_DELTA, deltaMs = 5_000))

        val lines = file.readLines()
        assertEquals(2, lines.size)
        assertEquals(
            "2026-09-16T23:06:40Z type=BACKWARD_CLOCK_AFTER_REBOOT bootCount=42 " +
                "tWall=1789600000000 rBoot=1234 deltaMs=-60000 resolution=REJECTED_NO_ANCHOR_UPDATE",
            lines[0],
        )
        assertTrue(lines[1].contains("type=REBOOT_WALL_DELTA"))
        assertTrue(lines[1].contains("deltaMs=5000"))
    }

    @Test
    fun rotation_keepsTheRunningFileBounded() {
        val file = tmp.newFile("anomalies.log")
        val log = StreakAnomalyLog(file, maxBytes = 1)

        log.append(anomaly)
        log.append(anomaly)

        assertEquals(1, file.readLines().size)
        assertEquals(1, File(tmp.root, "anomalies.log.1").readLines().size)
    }
}
