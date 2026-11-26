package com.example.myexodus.accountability.serialization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OPD #48: the schedule codec carried inside the E2EE rule-change body and stored in the
 * journal/feed columns. Encode is a fixed-order single line; decode is strict about every
 * field it reads and never throws.
 */
class RuleScheduleTest {

    private val full = RuleSchedule(
        alwaysOn = false,
        days = "1111100",
        windowStartMinute = 540,
        windowEndMinute = 1020,
        dailyLimitMs = 2 * 60 * 60_000L,
        expiresAtMs = 1_800_000_000_000L,
    )

    @Test
    fun encode_decode_roundTripsEveryField() {
        assertEquals(full, RuleSchedule.decodeOrNull(full.encode()))
        assertEquals(
            RuleSchedule(alwaysOn = true, days = "0000011", windowStartMinute = null,
                windowEndMinute = null, dailyLimitMs = 0, expiresAtMs = 0),
            RuleSchedule.decodeOrNull(RuleSchedule(true, "0000011", null, null, 0, 0).encode()),
        )
    }

    @Test
    fun encode_isTheDeclaredOrderedFormat() {
        assertEquals("1|1|1111111|||0|0", RuleSchedule(true, "1111111", null, null, 0, 0).encode())
        assertEquals(
            "1|0|1111100|540|1020|7200000|1800000000000",
            full.encode(),
        )
    }

    @Test
    fun decode_rejectsEveryMalformedShape() {
        assertNull(RuleSchedule.decodeOrNull(""))
        assertNull("wrong version", RuleSchedule.decodeOrNull("2|1|1111111|||0|0"))
        assertNull("wrong field count", RuleSchedule.decodeOrNull("1|1|1111111|||0"))
        assertNull("not a day mask", RuleSchedule.decodeOrNull("1|1|11111|00|0|0|0"))
        assertNull("one-sided window", RuleSchedule.decodeOrNull("1|0|1111111|540||0|0"))
        assertNull("minute past end of day", RuleSchedule.decodeOrNull("1|0|1111111|1440|1440|0|0"))
        assertNull("negative limit", RuleSchedule.decodeOrNull("1|0|1111111|||-1|0"))
        assertNull("negative expiry", RuleSchedule.decodeOrNull("1|0|1111111|||0|-1"))
        assertNull("non-numeric limit", RuleSchedule.decodeOrNull("1|0|1111111|||two|0"))
        assertNull(
            "oversized input",
            RuleSchedule.decodeOrNull("1|0|" + "1".repeat(200) + "|||0|0"),
        )
    }

    @Test
    fun init_rejectsValuesTheDecodeWouldNeverProduce() {
        assertTrue(runCatching { RuleSchedule(true, "111", null, null, 0, 0) }.isFailure)
        assertTrue(runCatching { RuleSchedule(true, "1111111", 1440, 60, 0, 0) }.isFailure)
        assertTrue(runCatching { RuleSchedule(true, "1111111", -1, 60, 0, 0) }.isFailure)
        // An end before the start is the overnight window, not garbage.
        RuleSchedule(true, "1111111", 22 * 60, 7 * 60, 0, 0)
    }

    /** Scenario matrix (owner 2026-11-26): every logical combination of mechanism x days x
     *  window x limit x end round-trips through the wire form unchanged. */
    @Test
    fun encode_decode_survivesTheFullOptionMatrix() {
        val dayMasks = listOf("1111111", "1111100", "0000011", "0000000")
        val windows = listOf<Pair<Long?, Long?>>(null to null, 9 * 60L to 17 * 60L, 22 * 60L to 7 * 60L)
        var combinations = 0
        for (alwaysOn in listOf(true, false)) {
            for (days in dayMasks) {
                for ((start, end) in windows) {
                    for (limit in listOf(0L, 2 * 60 * 60_000L)) {
                        for (expires in listOf(0L, 1_800_000_000_000L)) {
                            val schedule = RuleSchedule(alwaysOn, days, start, end, limit, expires)
                            assertEquals(
                                "$schedule must survive its own wire form",
                                schedule,
                                RuleSchedule.decodeOrNull(schedule.encode()),
                            )
                            combinations++
                        }
                    }
                }
            }
        }
        assertEquals(96, combinations)
    }

    /** Input domain: no prefix throws, and anything the codec accepts is a complete schedule
     *  that survives its own re-encode (a shortened trailing number is still a whole read). */
    @Test
    fun decode_neverThrowsOnAnyPrefix_andAcceptsOnlyCompleteSchedules() {
        val valid = full.encode()
        (0 until valid.length).forEach { length ->
            val decoded = RuleSchedule.decodeOrNull(valid.substring(0, length))
            decoded?.let {
                assertEquals("prefix $length decoded to a closed form", it, RuleSchedule.decodeOrNull(it.encode()))
            }
        }
        listOf("|", "||", "invalid", valid + "|").forEach { assertNull(RuleSchedule.decodeOrNull(it)) }
    }

    /** Every subset of the four axes, toggled independently, reports exactly itself. */
    @Test
    fun changedAxes_reportsExactlyTheChangedSubset() {
        val toggles = mapOf(
            RuleSchedule.Axis.BLOCKING to { s: RuleSchedule -> s.copy(alwaysOn = true) },
            RuleSchedule.Axis.DAYS to { s: RuleSchedule -> s.copy(days = "0000011") },
            RuleSchedule.Axis.LIMIT to { s: RuleSchedule -> s.copy(dailyLimitMs = 3 * 60 * 60_000L) },
            RuleSchedule.Axis.ENDS to { s: RuleSchedule -> s.copy(expiresAtMs = 0) },
        )
        RuleSchedule.Axis.entries.toList().allSubsets().forEach { subset ->
            val after = subset.fold(full) { schedule, axis -> toggles.getValue(axis)(schedule) }
            assertEquals(subset.toSet(), after.changedAxesFrom(full))
        }
    }

    private fun <T> List<T>.allSubsets(): List<List<T>> = (0 until (1 shl size)).map { mask ->
        filterIndexed { index, _ -> mask and (1 shl index) != 0 }
    }

    @Test
    fun changedAxes_reportsEachAxisOnItsOwn() {
        assertEquals(setOf(RuleSchedule.Axis.LIMIT), full.changedAxesFrom(full.copy(dailyLimitMs = 0)))
        assertEquals(setOf(RuleSchedule.Axis.DAYS), full.changedAxesFrom(full.copy(days = "0000011")))
        assertEquals(setOf(RuleSchedule.Axis.ENDS), full.changedAxesFrom(full.copy(expiresAtMs = 0)))
        assertEquals(
            "always-on and the window are one blocking axis",
            setOf(RuleSchedule.Axis.BLOCKING),
            full.changedAxesFrom(full.copy(alwaysOn = true)),
        )
        assertTrue(full.changedAxesFrom(full).isEmpty())
        assertEquals(
            RuleSchedule.Axis.entries.toSet(),
            full.changedAxesFrom(
                RuleSchedule(alwaysOn = true, days = "0000011", windowStartMinute = 60,
                    windowEndMinute = 120, dailyLimitMs = 0, expiresAtMs = 0),
            ),
        )
    }
}
