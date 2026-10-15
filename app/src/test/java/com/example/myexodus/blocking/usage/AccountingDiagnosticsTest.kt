package com.example.myexodus.blocking.usage

import com.example.myexodus.data.db.entity.UsageLedger
import com.example.myexodus.data.db.entity.UsageLedgerKind
import com.example.myexodus.data.db.entity.UsageLedgerSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Usage-ledger plan §8.7's arithmetic — pure JVM, every clock an argument: the heartbeat
 * cadence and its bucket identity, the measurement-gap threshold (cadence + 3× tolerance), the
 * §8.5 boot-mismatch rule, and the escalation rule that pairs a long gap with the absence of
 * platform-recoverable activity. AD-13 is structural here: nothing in these labels can name a
 * cause, only the fact and the size of the interruption.
 */
class AccountingDiagnosticsTest {

    private val source = UsageLedgerSource.PATH_A

    // --- §8.7 cadence and threshold ---

    @Test
    fun gapThreshold_isCadencePlusThreeTimesTolerance() {
        assertEquals(30_000L, AccountingDiagnostics.HEARTBEAT_INTERVAL_MS)
        assertEquals(
            AccountingDiagnostics.HEARTBEAT_INTERVAL_MS * 4,
            AccountingDiagnostics.MEASUREMENT_GAP_THRESHOLD_MS,
        )
    }

    @Test
    fun heartbeatBucket_quantizesToTheInterval() {
        assertEquals(0L, AccountingDiagnostics.heartbeatBucketMs(0L))
        assertEquals(0L, AccountingDiagnostics.heartbeatBucketMs(29_999L))
        assertEquals(30_000L, AccountingDiagnostics.heartbeatBucketMs(30_000L))
        assertEquals(90_000L, AccountingDiagnostics.heartbeatBucketMs(119_999L))
    }

    @Test
    fun heartbeatDraft_isSentinelCreditedNothing_andBucketedSoRetriesDedupe() {
        val a = AccountingDiagnostics.heartbeatDraft(source, bootCount = 7, wallClockMs = 1_000L, elapsedNowMs = 59_999L)
        val b = AccountingDiagnostics.heartbeatDraft(source, bootCount = 7, wallClockMs = 2_000L, elapsedNowMs = 60_001L)

        assertEquals(UsageLedgerKind.HEARTBEAT, a.kind)
        assertEquals(AccountingDiagnostics.SENTINEL_PACKAGE, a.packageName)
        assertEquals(0L, a.creditedMs)
        assertEquals(a.elapsedStartMs, a.elapsedEndMs)
        // The same bucket across two passes: identical elapsed bounds (hence the same derived
        // event id, hence a duplicate insert) even though the wall clock moved.
        assertEquals(60_000L, b.elapsedStartMs)
        assertEquals(60_000L, b.elapsedEndMs)
    }

    // --- §8.7 gap detection rules ---

    @Test
    fun gapSpan_silenceInsideTheThreshold_isNoGap() {
        assertNull(
            AccountingDiagnostics.gapSpan(7, 7, 10_000L, 10_000L + AccountingDiagnostics.MEASUREMENT_GAP_THRESHOLD_MS),
        )
    }

    @Test
    fun gapSpan_pastTheThreshold_isTheSilenceSpan() {
        val span = AccountingDiagnostics.gapSpan(
            7, 7, 10_000L,
            10_000L + AccountingDiagnostics.MEASUREMENT_GAP_THRESHOLD_MS + 1,
        )
        assertEquals(AccountingDiagnostics.MEASUREMENT_GAP_THRESHOLD_MS + 1, span)
    }

    @Test
    fun gapSpan_neverSubtractsAcrossABoot() {
        // §8.5: elapsed clocks are not comparable across boots — a reboot is boot
        // reconciliation's domain, never a measurement gap.
        assertNull(AccountingDiagnostics.gapSpan(6, 7, 0L, 10 * 60 * 60 * 1000L))
    }

    @Test
    fun gapSpan_negativeDeltaIsAClockAnomalyNotAGap() {
        assertNull(AccountingDiagnostics.gapSpan(7, 7, 10_000_000L, 5_000L))
    }

    @Test
    fun gapDraft_spansTheDeadWindow_andCreditsNothing() {
        val draft = AccountingDiagnostics.gapDraft(source, bootCount = 7, wallClockMs = 1_000L, fromElapsedMs = 100L, toElapsedMs = 500L)

        assertEquals(UsageLedgerKind.GAP_DETECTED, draft.kind)
        assertEquals(AccountingDiagnostics.SENTINEL_PACKAGE, draft.packageName)
        assertEquals(0L, draft.creditedMs)
        assertEquals(100L, draft.elapsedStartMs)
        assertEquals(500L, draft.elapsedEndMs)
        // §8.2: a diagnostic draft must still pass the mapper's own validation.
        assertNull(UsageLedgerMapper.rejectionReason(draft))
    }

    // --- §8.5 reboot boundary ---

    @Test
    fun rebootGapDraft_isZeroLength_passesValidation_andIsNotAMeasuredOutage() {
        val draft = AccountingDiagnostics.rebootGapDraft(source, bootCount = 7, wallClockMs = 1_000L, elapsedNowMs = 500L)

        assertEquals(UsageLedgerKind.GAP_DETECTED, draft.kind)
        assertEquals(AccountingDiagnostics.SENTINEL_PACKAGE, draft.packageName)
        assertEquals(0L, draft.creditedMs)
        assertEquals(500L, draft.elapsedStartMs)
        assertEquals(500L, draft.elapsedEndMs)
        assertNull(UsageLedgerMapper.rejectionReason(draft))
    }

    @Test
    fun isMeasuredOutage_separatesASilenceFromARebootBoundary() {
        assertTrue(AccountingDiagnostics.isMeasuredOutage(row(UsageLedgerKind.GAP_DETECTED, elapsedStartMs = 100L, elapsedEndMs = 500L)))
        assertFalse(AccountingDiagnostics.isMeasuredOutage(row(UsageLedgerKind.GAP_DETECTED, elapsedStartMs = 500L, elapsedEndMs = 500L)))
        // Only GAP_DETECTED rows are outage evidence at all — liveness rows are not gaps.
        assertFalse(AccountingDiagnostics.isMeasuredOutage(row(UsageLedgerKind.HEARTBEAT, elapsedStartMs = 0L, elapsedEndMs = 0L)))
    }

    // --- §8.7 liveness ---

    @Test
    fun heartbeatDue_trueWithoutBaseline_andAfterTheInterval() {
        assertTrue(AccountingDiagnostics.heartbeatDue(null, latestBootCountMatches = false, wallClockMs = 1_000L))
        assertFalse(AccountingDiagnostics.heartbeatDue(1_000L, true, 1_000L + AccountingDiagnostics.HEARTBEAT_INTERVAL_MS - 1))
        assertTrue(AccountingDiagnostics.heartbeatDue(1_000L, true, 1_000L + AccountingDiagnostics.HEARTBEAT_INTERVAL_MS))
        // A baseline from another boot says nothing about this boot's liveness.
        assertTrue(AccountingDiagnostics.heartbeatDue(9_999_999L, latestBootCountMatches = false, wallClockMs = 1_000L))
    }

    // --- §8.7 escalation (prompt eligibility, never a cause — AD-13) ---

    @Test
    fun escalation_requiresBothALongGapAndNoRecoverableActivity() {
        val long = AccountingDiagnostics.ESCALATION_THRESHOLD_MS
        assertTrue(AccountingDiagnostics.isPromptEligible(long, recoverableCreditMs = 0L))
        assertFalse(AccountingDiagnostics.isPromptEligible(long, recoverableCreditMs = 1L))
        assertFalse(AccountingDiagnostics.isPromptEligible(long - 1, recoverableCreditMs = 0L))
    }

    @Test
    fun outageSummary_countsAndFindsTheLargestWindow() {
        assertEquals(
            AccountingDiagnostics.OutageSummary(count = 0, largestWindowMs = 0L),
            AccountingDiagnostics.outageSummary(emptyList()),
        )
        assertEquals(
            AccountingDiagnostics.OutageSummary(count = 3, largestWindowMs = 900_000L),
            AccountingDiagnostics.outageSummary(listOf(120_000L, 900_000L, 300_000L)),
        )
    }

    // --- ADR #149: day-split siblings re-join before counting ---

    @Test
    fun mergedOutageSpans_rejoinsTheDaySplitIntoOneInterruption() {
        // One 120 s detection written across local midnight: two rows sharing the detection's
        // wall clock, contiguous in elapsed space. Counting rows would report the overnight
        // silence twice and the largest window as only its longer half.
        val rows = listOf(
            row(UsageLedgerKind.GAP_DETECTED, elapsedStartMs = 1_000L, elapsedEndMs = 61_000L),
            row(UsageLedgerKind.GAP_DETECTED, elapsedStartMs = 61_000L, elapsedEndMs = 121_000L),
        )

        assertEquals(listOf(120_000L), AccountingDiagnostics.mergedOutageSpans(rows))
    }

    @Test
    fun mergedOutageSpans_multiDaySplitStillCollapsesToOneSpan() {
        val rows = listOf(
            row(UsageLedgerKind.GAP_DETECTED, elapsedStartMs = 0L, elapsedEndMs = 10_000L),
            row(UsageLedgerKind.GAP_DETECTED, elapsedStartMs = 10_000L, elapsedEndMs = 20_000L),
            row(UsageLedgerKind.GAP_DETECTED, elapsedStartMs = 20_000L, elapsedEndMs = 30_000L),
        )

        assertEquals(listOf(30_000L), AccountingDiagnostics.mergedOutageSpans(rows))
    }

    @Test
    fun mergedOutageSpans_separateDetectionsNeverMerge_evenWhenContiguous() {
        // Different wall clocks = different detections: an abutment of two real interruptions
        // is two interruptions, not one long one.
        val rows = listOf(
            row(UsageLedgerKind.GAP_DETECTED, elapsedStartMs = 1_000L, elapsedEndMs = 61_000L, wallClockTsMs = 1_000L),
            row(UsageLedgerKind.GAP_DETECTED, elapsedStartMs = 61_000L, elapsedEndMs = 121_000L, wallClockTsMs = 2_000L),
        )

        assertEquals(listOf(60_000L, 60_000L), AccountingDiagnostics.mergedOutageSpans(rows))
    }

    @Test
    fun mergedOutageSpans_singleAndEmptyAreThemselves() {
        assertEquals(
            listOf(60_000L),
            AccountingDiagnostics.mergedOutageSpans(
                listOf(row(UsageLedgerKind.GAP_DETECTED, elapsedStartMs = 1_000L, elapsedEndMs = 61_000L)),
            ),
        )
        assertEquals(emptyList<Long>(), AccountingDiagnostics.mergedOutageSpans(emptyList()))
    }

    @Test
    fun mergedOutageSpans_sameWallDifferentBoots_doNotMerge() {
        val rows = listOf(
            row(UsageLedgerKind.GAP_DETECTED, elapsedStartMs = 1_000L, elapsedEndMs = 61_000L, bootCount = 6),
            row(UsageLedgerKind.GAP_DETECTED, elapsedStartMs = 61_000L, elapsedEndMs = 121_000L, bootCount = 7),
        )

        assertEquals(listOf(60_000L, 60_000L), AccountingDiagnostics.mergedOutageSpans(rows))
    }

    private fun row(
        kind: Int,
        elapsedStartMs: Long,
        elapsedEndMs: Long,
        wallClockTsMs: Long = 1_000L,
        bootCount: Int = 7,
    ) = UsageLedger(
        eventId = "e-1",
        kind = kind,
        wallClockTsMs = wallClockTsMs,
        elapsedStartMs = elapsedStartMs,
        elapsedEndMs = elapsedEndMs,
        bootCount = bootCount,
        packageName = AccountingDiagnostics.SENTINEL_PACKAGE,
        dayKey = "2026-09-28",
        creditedMs = 0L,
        source = source,
        sourceVersion = "test",
    )
}
