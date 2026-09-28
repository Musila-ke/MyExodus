package com.example.myexodus.ui.recovery

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The trailing day stamp's one claim is which day a row belongs to, and its whole point is that
 * the boundary — midnight, and the year changing — is checked without a clock. The screen only
 * renders what this returns; the zone is passed in so the assertions hold in any test machine's
 * timezone.
 */
class HistoryStampTest {

    private val utc = ZoneId.of("UTC")

    private fun label(
        thenIso: String,
        nowIso: String,
        zone: ZoneId = utc,
    ): String = historyTimestampLabel(
        epochMs = Instant.parse(thenIso).toEpochMilli(),
        nowMs = Instant.parse(nowIso).toEpochMilli(),
        zone = zone,
        today = "Today",
        yesterday = "Yesterday",
        locale = Locale.US,
        // CLDR writes "11:59 PM" with a narrow no-break space before the meridiem (Java 20+);
        // normalised so the expectations below are readable and CLDR-version-proof. The claim
        // under test is the day, not the space character.
    ).replace('\u202F', ' ')

    @Test
    fun `same day reads today with the time`() {
        assertEquals(
            "Today · 3:42 PM",
            label("2026-09-27T15:42:00Z", "2026-09-27T20:05:00Z"),
        )
    }

    @Test
    fun `the day before reads yesterday`() {
        assertEquals(
            "Yesterday · 3:42 PM",
            label("2026-09-26T15:42:00Z", "2026-09-27T08:00:00Z"),
        )
    }

    @Test
    fun `a minute before midnight is yesterday, not a date`() {
        assertEquals(
            "Yesterday · 11:59 PM",
            label("2026-09-26T23:59:00Z", "2026-09-27T00:01:00Z"),
        )
    }

    @Test
    fun `two days back reads the month and day`() {
        assertEquals(
            "Sep 25 · 3:42 PM",
            label("2026-09-25T15:42:00Z", "2026-09-27T08:00:00Z"),
        )
    }

    @Test
    fun `another year adds the year`() {
        assertEquals(
            "Dec 30, 2025 · 3:42 PM",
            label("2025-12-30T15:42:00Z", "2026-01-01T08:00:00Z"),
        )
    }

    @Test
    fun `new year's eve is still yesterday on new year's day`() {
        assertEquals(
            "Yesterday · 3:42 PM",
            label("2025-12-31T15:42:00Z", "2026-01-01T08:00:00Z"),
        )
    }

    @Test
    fun `the day is counted in the zone passed, not the stamps' own`() {
        // 22:30 UTC is already 00:30 the next day at UTC+02:00, so both ends land on the same
        // local date only when the zone is honoured.
        assertEquals(
            "Today · 12:30 AM",
            label("2026-09-26T22:30:00Z", "2026-09-27T00:40:00Z", ZoneId.of("UTC+02:00")),
        )
    }
}
