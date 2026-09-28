package com.example.myexodus.blocking.usage

import com.example.myexodus.data.db.entity.UsageLedger
import com.example.myexodus.data.db.entity.UsageLedgerKind
import com.example.myexodus.data.db.entity.UsageLedgerSource
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Usage-ledger plan §8.2/§8.5/§8.6 — the mapper is pure arithmetic, so these are plain JVM
 * tests: validation refusals, deterministic identity, and the day-boundary split that
 * invariant test #9 (`two rows, no millisecond lost`) is written against.
 */
class UsageLedgerMapperTest {

    private val zone = ZoneId.of("Africa/Nairobi") // UTC+3, no DST — the device's real zone.
    private val noon = ZonedDateTime.of(
        LocalDateTime.of(2026, 9, 27, 12, 0),
        zone,
    ).toInstant().toEpochMilli()

    private fun draft(
        creditedMs: Long = 1_000L,
        elapsedStartMs: Long = 0L,
        elapsedEndMs: Long = 1_000L,
        kind: Int = UsageLedgerKind.ACCOUNTING,
        wallClockTsMs: Long = noon,
        bootCount: Int = 7,
        source: String = UsageLedgerSource.PATH_A,
        referencesEventId: String? = null,
        eventIdOverride: String? = null,
        packageName: String = "com.instagram.android",
    ) = LedgerEventDraft(
        kind = kind,
        packageName = packageName,
        creditedMs = creditedMs,
        elapsedStartMs = elapsedStartMs,
        elapsedEndMs = elapsedEndMs,
        bootCount = bootCount,
        source = source,
        wallClockTsMs = wallClockTsMs,
        referencesEventId = referencesEventId,
        eventIdOverride = eventIdOverride,
    )

    // --- §8.6 deterministic identity ---

    @Test
    fun eventId_isDeterministicAndSensitiveToEveryIdentityInput() {
        val base = UsageLedgerMapper.eventIdFor("PATH_A", "com.a", 10L, 20L, 3)
        assertEquals(base, UsageLedgerMapper.eventIdFor("PATH_A", "com.a", 10L, 20L, 3))
        assertEquals(32, base.length)

        assertNotEquals(base, UsageLedgerMapper.eventIdFor("PATH_B", "com.a", 10L, 20L, 3))
        assertNotEquals(base, UsageLedgerMapper.eventIdFor("PATH_A", "com.b", 10L, 20L, 3))
        assertNotEquals(base, UsageLedgerMapper.eventIdFor("PATH_A", "com.a", 11L, 20L, 3))
        assertNotEquals(base, UsageLedgerMapper.eventIdFor("PATH_A", "com.a", 10L, 21L, 3))
        // Boot identity is part of the id: the same elapsed numbers after a reboot are a
        // different observation, never deduped against the pre-reboot one (invariant #4).
        assertNotEquals(base, UsageLedgerMapper.eventIdFor("PATH_A", "com.a", 10L, 20L, 4))
    }

    @Test
    fun eventId_ignoresTheWallClock() {
        // A clock change must not make one observation look like two (§8.6).
        val id = UsageLedgerMapper.eventIdFor("PATH_A", "com.instagram.android", 0L, 1_000L, 7)
        val rowA = UsageLedgerMapper.toRow(draft(wallClockTsMs = noon), LedgerSlice("2026-09-27", 1_000L, 0L, 1_000L), "1.0")
        val rowB = UsageLedgerMapper.toRow(
            draft(wallClockTsMs = noon + 3_600_000L),
            LedgerSlice("2026-09-27", 1_000L, 0L, 1_000L),
            "1.0",
        )
        assertEquals(rowA.eventId, rowB.eventId)
        assertEquals(id, rowA.eventId)
    }

    @Test
    fun correctionEventId_isStablePerTargetAndInstant_soRepeatedCorrectionsLand() {
        val a = UsageLedgerMapper.correctionEventIdFor("evt-1", noon)
        assertEquals(a, UsageLedgerMapper.correctionEventIdFor("evt-1", noon))
        assertNotEquals(a, UsageLedgerMapper.correctionEventIdFor("evt-1", noon + 1))
        assertNotEquals(a, UsageLedgerMapper.correctionEventIdFor("evt-2", noon))
    }

    // --- §8.2 validation ---

    @Test
    fun validation_acceptsWellFormedDraftsOfEveryKnownKind() {
        UsageLedgerKind.CREDITING.forEach { kind ->
            assertNull(
                "kind ${UsageLedgerKind.nameOf(kind)}",
                UsageLedgerMapper.rejectionReason(
                    draft(
                        kind = kind,
                        referencesEventId = if (kind == UsageLedgerKind.CORRECTION) "evt-x" else null,
                        // A correction is an aggregate delta: no interval, no span bound.
                        elapsedStartMs = if (kind == UsageLedgerKind.CORRECTION) 5L else 0L,
                        elapsedEndMs = if (kind == UsageLedgerKind.CORRECTION) 5L else 1_000L,
                    ),
                ),
            )
        }
        listOf(UsageLedgerKind.HEARTBEAT, UsageLedgerKind.RULE_EVALUATION, UsageLedgerKind.GAP_DETECTED).forEach {
            assertNull(
                "kind ${UsageLedgerKind.nameOf(it)}",
                UsageLedgerMapper.rejectionReason(draft(kind = it, creditedMs = 0L, elapsedStartMs = 9L, elapsedEndMs = 9L)),
            )
        }
    }

    @Test
    fun validation_rejectsMalformedDrafts() {
        assertEquals("empty package_name", UsageLedgerMapper.rejectionReason(draft(packageName = "")))
        assertEquals(
            "package_name over 255",
            UsageLedgerMapper.rejectionReason(draft(packageName = "a".repeat(256))),
        )
        assertNull(UsageLedgerMapper.rejectionReason(draft(packageName = "a".repeat(255))))
        assertEquals("unknown kind 99", UsageLedgerMapper.rejectionReason(draft(kind = 99)))
        assertEquals("negative credited_ms", UsageLedgerMapper.rejectionReason(draft(creditedMs = -1L)))
        assertEquals(
            "elapsed_end before elapsed_start",
            UsageLedgerMapper.rejectionReason(draft(elapsedStartMs = 500L, elapsedEndMs = 400L)),
        )
        assertEquals(
            "credited_ms exceeds elapsed span",
            UsageLedgerMapper.rejectionReason(draft(creditedMs = 1_001L, elapsedStartMs = 0L, elapsedEndMs = 1_000L)),
        )
        assertEquals(
            "correction without references_event_id",
            UsageLedgerMapper.rejectionReason(
                draft(kind = UsageLedgerKind.CORRECTION, elapsedStartMs = 0L, elapsedEndMs = 0L),
            ),
        )
        assertEquals(
            "empty references_event_id",
            UsageLedgerMapper.rejectionReason(draft(referencesEventId = "")),
        )
        assertEquals("empty event_id", UsageLedgerMapper.rejectionReason(draft(eventIdOverride = "")))
    }

    @Test
    fun validation_zeroLengthCarryover_mayExceedItsOwnSpan() {
        // A mid-day reboot makes the platform total legitimately larger than any interval the
        // boot can explain; a zero-length carryover must stay insertable (the §8.2 exemption).
        assertNull(
            UsageLedgerMapper.rejectionReason(
                draft(
                    kind = UsageLedgerKind.MIGRATION_CARRYOVER,
                    creditedMs = 3_600_000L,
                    elapsedStartMs = 0L,
                    elapsedEndMs = 0L,
                ),
            ),
        )
    }

    // --- §8.5 day-boundary split (invariant #9) ---

    @Test
    fun split_sameDay_isOneSliceCarryingEveryMillisecond() {
        val slices = UsageLedgerMapper.splitAtDayBoundary(draft(creditedMs = 5_000L), zone)
        assertEquals(1, slices.size)
        assertEquals("2026-09-27", slices.single().dayKey)
        assertEquals(5_000L, slices.single().creditedMs)
        // The slice carries the observation's own interval — credit inside it is separate.
        assertEquals(1_000L, slices.single().elapsedEndMs - slices.single().elapsedStartMs)
    }

    @Test
    fun split_crossingMidnight_isExactlyTwoSlicesWhoseCreditsSumExactly() {
        // 20 minutes closing at 00:10 local: 10 minutes on each side of the boundary.
        val close = ZonedDateTime.of(LocalDateTime.of(2026, 9, 28, 0, 10), zone)
        val d = draft(
            creditedMs = 1_200_000L,
            elapsedStartMs = 10_000L,
            elapsedEndMs = 1_210_000L,
            wallClockTsMs = close.toInstant().toEpochMilli(),
        )
        val slices = UsageLedgerMapper.splitAtDayBoundary(d, zone)
        assertEquals(2, slices.size)
        assertEquals("2026-09-27", slices[0].dayKey)
        assertEquals("2026-09-28", slices[1].dayKey)
        assertEquals(1_200_000L, slices.sumOf { it.creditedMs })
        assertEquals(600_000L, slices[0].creditedMs)
        assertEquals(600_000L, slices[1].creditedMs)
        // The intervals are contiguous and cover the original span exactly.
        assertEquals(d.elapsedStartMs, slices[0].elapsedStartMs)
        assertEquals(slices[0].elapsedEndMs, slices[1].elapsedStartMs)
        assertEquals(d.elapsedEndMs, slices[1].elapsedEndMs)
    }

    @Test
    fun split_remainderGoesToTheClosingSlice_soOddMillisecondsAreNeverLost() {
        // 3 ms credited over a 2 ms boundary-adjacent span cannot halve evenly.
        val close = ZonedDateTime.of(LocalDateTime.of(2026, 9, 28, 0, 0, 0), zone)
        val boundary = close.toInstant().toEpochMilli()
        val d = draft(
            creditedMs = 3L,
            elapsedStartMs = 1_000L,
            elapsedEndMs = 1_003L,
            // Window starts 2 ms before the boundary → 2/3 of the span is yesterday.
            wallClockTsMs = boundary + 1,
        )
        val slices = UsageLedgerMapper.splitAtDayBoundary(d, zone)
        assertEquals(2, slices.size)
        assertEquals(3L, slices.sumOf { it.creditedMs })
        assertEquals(2L, slices[0].creditedMs)
        assertEquals(1L, slices[1].creditedMs)
    }

    @Test
    fun split_zeroOrNegativeSpan_isAttributedToTheDayItClosedOn() {
        val close = ZonedDateTime.of(LocalDateTime.of(2026, 9, 28, 0, 5), zone)
        val d = draft(creditedMs = 0L, elapsedStartMs = 42L, elapsedEndMs = 42L, wallClockTsMs = close.toInstant().toEpochMilli())
        val slices = UsageLedgerMapper.splitAtDayBoundary(d, zone)
        assertEquals(1, slices.size)
        assertEquals("2026-09-28", slices.single().dayKey)
        assertEquals(42L, slices.single().elapsedStartMs)
    }

    @Test
    fun split_clockStepInsideTheWindow_neverInventsAThirdDay() {
        // The span implies a start two days before the closing instant — impossible for a
        // live interval, but a device clock step can report exactly this.
        val close = ZonedDateTime.of(LocalDateTime.of(2026, 9, 28, 12, 0), zone)
        val d = draft(
            creditedMs = 60_000L,
            elapsedStartMs = 0L,
            elapsedEndMs = 60_000L,
            wallClockTsMs = close.toInstant().toEpochMilli(),
        )
        val stepped = LedgerEventDraft(
            kind = d.kind, packageName = d.packageName, creditedMs = d.creditedMs,
            // A 2-day monotonic span against a wall clock that only moved 60 s.
            elapsedEndMs = 2L * 24 * 3_600_000L + 60_000L,
            elapsedStartMs = 2L * 24 * 3_600_000L,
            bootCount = d.bootCount, source = d.source, wallClockTsMs = d.wallClockTsMs,
        )
        val slices = UsageLedgerMapper.splitAtDayBoundary(stepped, zone)
        assertEquals(1, slices.size)
        assertEquals("2026-09-28", slices.single().dayKey)
        assertEquals(60_000L, slices.single().creditedMs)
    }

    @Test
    fun split_dstSpringForward_conservesEveryMillisecond() {
        // Europe/London 2026-03-29: clocks jump 01:00 → 02:00. A span that straddles the gap
        // must still split by epoch arithmetic, not by wall-clock arithmetic.
        val london = ZoneId.of("Europe/London")
        val close = ZonedDateTime.of(LocalDateTime.of(2026, 3, 29, 2, 30), london)
        val d = draft(
            creditedMs = 3_600_000L,
            elapsedStartMs = 0L,
            elapsedEndMs = 3_600_000L,
            wallClockTsMs = close.toInstant().toEpochMilli(),
        )
        val slices = UsageLedgerMapper.splitAtDayBoundary(d, london)
        assertEquals(3_600_000L, slices.sumOf { it.creditedMs })
        assertTrue(slices.all { it.creditedMs >= 0 })
    }

    @Test
    fun split_dstFallBack_conservesEveryMillisecond() {
        // Europe/London 2026-10-25: clocks repeat 01:00–02:00. Crossing local midnight into
        // the repeated hour must behave like any other midnight.
        val london = ZoneId.of("Europe/London")
        val close = ZonedDateTime.of(LocalDateTime.of(2026, 10, 25, 0, 30), london)
        val d = draft(
            creditedMs = 3_600_000L,
            elapsedStartMs = 0L,
            elapsedEndMs = 3_600_000L,
            wallClockTsMs = close.toInstant().toEpochMilli(),
        )
        val slices = UsageLedgerMapper.splitAtDayBoundary(d, london)
        assertEquals(2, slices.size)
        assertEquals(3_600_000L, slices.sumOf { it.creditedMs })
        assertEquals("2026-10-24", slices[0].dayKey)
        assertEquals("2026-10-25", slices[1].dayKey)
    }

    // --- §8.6 duplicate detection ---

    @Test
    fun samePayload_isTrueForARetryAndFalseForACollision() {
        val row = UsageLedgerMapper.toRow(draft(), LedgerSlice("2026-09-27", 1_000L, 0L, 1_000L), "1.0")
        val retry = UsageLedgerMapper.toRow(draft(), LedgerSlice("2026-09-27", 1_000L, 0L, 1_000L), "1.0")
        assertTrue(UsageLedgerMapper.samePayload(row, retry))
        val differing = row.copy(creditedMs = 999L)
        assertFalse(UsageLedgerMapper.samePayload(row, differing))
        assertFalse(UsageLedgerMapper.samePayload(row, row.copy(kind = UsageLedgerKind.RECONCILIATION)))
        assertFalse(UsageLedgerMapper.samePayload(row, row.copy(elapsedEndMs = 1_001L)))
    }

    @Test
    fun toRow_usesTheOverrideWhenGiven_andRecordsSourceVersion() {
        val seeded = UsageLedgerMapper.toRow(
            draft(eventIdOverride = "migration_seed:com.a", kind = UsageLedgerKind.MIGRATION_CARRYOVER),
            LedgerSlice("2026-09-27", 500L, 0L, 0L),
            "9.9.9",
        )
        assertEquals("migration_seed:com.a", seeded.eventId)
        assertEquals("9.9.9", seeded.sourceVersion)
        assertEquals("2026-09-27", seeded.dayKey)
    }
}
