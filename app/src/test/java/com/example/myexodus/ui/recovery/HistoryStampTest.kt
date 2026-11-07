package com.example.myexodus.ui.recovery

import com.example.myexodus.data.db.entity.RecoverySession
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The day heading's one claim is which day a row belongs to, and its whole point is that the
 * boundary — midnight, and the year changing — is checked without a clock. The screen only
 * renders what these return; the zone is passed in so the assertions hold in any test machine's
 * timezone.
 */
class HistoryStampTest {

    private val utc = ZoneId.of("UTC")

    private fun day(
        thenIso: String,
        nowIso: String,
        zone: ZoneId = utc,
    ): String = historyDayLabel(
        epochMs = Instant.parse(thenIso).toEpochMilli(),
        nowMs = Instant.parse(nowIso).toEpochMilli(),
        zone = zone,
        today = "Today",
        yesterday = "Yesterday",
        locale = Locale.US,
    )

    // CLDR writes "3:42 PM" with a narrow no-break space before the meridiem (Java 20+);
    // normalised so the expectations below are readable and CLDR-version-proof. The claim
    // under test is the value, not the space character.
    private fun time(thenIso: String, zone: ZoneId = utc): String = historyTimeLabel(
        epochMs = Instant.parse(thenIso).toEpochMilli(),
        zone = zone,
        locale = Locale.US,
    ).replace(' ', ' ')

    private fun row(id: String, thenIso: String) = RecoverySession(
        id = id,
        technique = RecoverySession.TECHNIQUE_NONE,
        state = RecoverySession.STATE_COMPLETED,
        startedAtEpochMs = Instant.parse(thenIso).toEpochMilli(),
        zoneId = "UTC",
        entry = RecoverySession.ENTRY_HOME,
    )

    @Test
    fun `same day reads today`() {
        assertEquals("Today", day("2026-09-27T15:42:00Z", "2026-09-27T20:05:00Z"))
    }

    @Test
    fun `the day before reads yesterday`() {
        assertEquals("Yesterday", day("2026-09-26T15:42:00Z", "2026-09-27T08:00:00Z"))
    }

    @Test
    fun `a minute before midnight is yesterday, not a date`() {
        assertEquals("Yesterday", day("2026-09-26T23:59:00Z", "2026-09-27T00:01:00Z"))
    }

    @Test
    fun `two days back reads the month and day`() {
        assertEquals("Sep 25", day("2026-09-25T15:42:00Z", "2026-09-27T08:00:00Z"))
    }

    @Test
    fun `another year adds the year`() {
        assertEquals("Dec 30, 2025", day("2025-12-30T15:42:00Z", "2026-01-01T08:00:00Z"))
    }

    @Test
    fun `new year's eve is still yesterday on new year's day`() {
        assertEquals("Yesterday", day("2025-12-31T15:42:00Z", "2026-01-01T08:00:00Z"))
    }

    @Test
    fun `the day is counted in the zone passed, not the stamps' own`() {
        // 22:30 UTC is already 00:30 the next day at UTC+02:00, so both ends land on the same
        // local date only when the zone is honoured.
        assertEquals(
            "Today",
            day("2026-09-26T22:30:00Z", "2026-09-27T00:40:00Z", ZoneId.of("UTC+02:00")),
        )
    }

    @Test
    fun `the row time is the local time in the given zone`() {
        assertEquals("3:42 PM", time("2026-09-27T15:42:00Z"))
        assertEquals("12:30 AM", time("2026-09-26T22:30:00Z", ZoneId.of("UTC+02:00")))
    }

    @Test
    fun `rows on the same local day make one group`() {
        val groups = groupHistoryByDay(
            rows = listOf(
                row("b", "2026-09-27T15:42:00Z"),
                row("a", "2026-09-27T09:10:00Z"),
            ),
            nowMs = Instant.parse("2026-09-27T20:05:00Z").toEpochMilli(),
            zone = utc,
            today = "Today",
            yesterday = "Yesterday",
            locale = Locale.US,
        )
        assertEquals(listOf("Today"), groups.map { it.label })
        assertEquals(listOf("b", "a"), groups.single().rows.map { it.id })
    }

    @Test
    fun `midnight splits the groups and keeps newest first`() {
        val groups = groupHistoryByDay(
            rows = listOf(
                row("b", "2026-09-27T00:05:00Z"),
                row("a", "2026-09-26T23:59:00Z"),
            ),
            nowMs = Instant.parse("2026-09-27T08:00:00Z").toEpochMilli(),
            zone = utc,
            today = "Today",
            yesterday = "Yesterday",
            locale = Locale.US,
        )
        assertEquals(listOf("Today", "Yesterday"), groups.map { it.label })
        assertEquals(listOf("b"), groups[0].rows.map { it.id })
        assertEquals(listOf("a"), groups[1].rows.map { it.id })
    }
}
