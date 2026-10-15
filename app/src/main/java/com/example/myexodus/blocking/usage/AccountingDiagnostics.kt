package com.example.myexodus.blocking.usage

import com.example.myexodus.data.db.entity.UsageLedger
import com.example.myexodus.data.db.entity.UsageLedgerKind

/**
 * Usage-ledger plan §8.7 — the arithmetic behind gap detection, escalation, and liveness.
 * Pure functions only; [UsageDiagnosticsRecorder] owns the database side.
 *
 * A gap is *measurement*, never cause: every label here describes whether tracking was
 * interrupted and how long for. Nothing in this file, its callers, or any user-facing string
 * may present a gap as a proven OEM or Android battery-management action (AD-13).
 */
object AccountingDiagnostics {

    /**
     * Liveness cadence. The FGS tick runs every 1.5 s; a HEARTBEAT row is written at most once
     * per this interval, and only while the authoritative source has no newer row (an actively
     * accounting pipeline needs no separate liveness evidence — its ACCOUNTING rows are it).
     */
    const val HEARTBEAT_INTERVAL_MS = 30_000L

    /**
     * §8.7's "cadence plus tolerance margin (initial default: cadence × 3)": 30 s + 90 s.
     * Silence longer than this from every accounting source, within one boot, is a
     * `measurement_gap`.
     */
    const val MEASUREMENT_GAP_THRESHOLD_MS = HEARTBEAT_INTERVAL_MS + 3 * HEARTBEAT_INTERVAL_MS

    /**
     * The plan names an escalation threshold without fixing its value (§8.7); 5 minutes is the
     * desktop-verified bar below which a restart is ordinary churn and a prompt would be
     * noise. Revisable on device evidence (task #22), like every other on-device constant.
     */
    const val ESCALATION_THRESHOLD_MS = 5 * 60 * 1000L

    /**
     * HEARTBEAT and GAP_DETECTED rows attest the *pipeline*, not a package — the shipped
     * schema requires a non-empty `package_name` on every row, and this sentinel keeps those
     * rows out of every real package's scope: the kinds are excluded from all aggregation
     * (§8.13) and `*` is never a valid Android package name.
     */
    const val SENTINEL_PACKAGE = "*"

    /** Heartbeats are bucketed to [HEARTBEAT_INTERVAL_MS] in elapsed space so a retry inside
     *  the same bucket derives the same deterministic event id and dedupes (§8.6). */
    fun heartbeatBucketMs(elapsedNowMs: Long): Long =
        elapsedNowMs / HEARTBEAT_INTERVAL_MS * HEARTBEAT_INTERVAL_MS

    fun heartbeatDraft(source: String, bootCount: Int, wallClockMs: Long, elapsedNowMs: Long) =
        LedgerEventDraft(
            kind = UsageLedgerKind.HEARTBEAT,
            packageName = SENTINEL_PACKAGE,
            creditedMs = 0L,
            elapsedStartMs = heartbeatBucketMs(elapsedNowMs),
            elapsedEndMs = heartbeatBucketMs(elapsedNowMs),
            bootCount = bootCount,
            source = source,
            wallClockTsMs = wallClockMs,
        )

    /** The unobserved window itself: `elapsed_start` is where observation stopped (the last
     *  evidence's `elapsed_end`), `elapsed_end` is when the hole was noticed. Credits nothing —
     *  it is evidence whose span is the dead window. */
    fun gapDraft(
        source: String,
        bootCount: Int,
        wallClockMs: Long,
        fromElapsedMs: Long,
        toElapsedMs: Long,
    ) = LedgerEventDraft(
        kind = UsageLedgerKind.GAP_DETECTED,
        packageName = SENTINEL_PACKAGE,
        creditedMs = 0L,
        elapsedStartMs = fromElapsedMs,
        elapsedEndMs = toElapsedMs,
        bootCount = bootCount,
        source = source,
        wallClockTsMs = wallClockMs,
    )

    /**
     * §8.5's reboot boundary. The interval spanning a reboot is not expressible by subtracting
     * elapsed values, so the record carries a zero-length interval at the observation point —
     * the same "no reconstructable sub-timeline" shape MIGRATION_CARRYOVER uses. It is the only
     * zero-length GAP_DETECTED row there is, and [isMeasuredOutage] is the one check that
     * separates the two meanings, so outage counts never report a reboot as a tracking failure.
     */
    fun rebootGapDraft(source: String, bootCount: Int, wallClockMs: Long, elapsedNowMs: Long) =
        gapDraft(source, bootCount, wallClockMs, fromElapsedMs = elapsedNowMs, toElapsedMs = elapsedNowMs)

    /** True for a GAP_DETECTED row that *measures* a silence; false for [rebootGapDraft]'s
     *  zero-length boundary record. Outage counts and the largest-dead-window surface take only
     *  the rows this accepts. */
    fun isMeasuredOutage(row: UsageLedger): Boolean =
        row.kind == UsageLedgerKind.GAP_DETECTED && row.elapsedEndMs > row.elapsedStartMs

    /**
     * How long observation has been silent, or null when there is no gap to record: a baseline
     * from another boot (elapsed clocks are not comparable across boots — §8.5) and a delta
     * within the threshold are both "nothing happened". A negative delta is a clock anomaly,
     * not a gap.
     */
    fun gapSpan(
        baselineBootCount: Int,
        bootCount: Int,
        baselineElapsedEndMs: Long,
        nowElapsedMs: Long,
        thresholdMs: Long = MEASUREMENT_GAP_THRESHOLD_MS,
    ): Long? {
        if (baselineBootCount != bootCount) return null
        val span = nowElapsedMs - baselineElapsedEndMs
        return if (span > thresholdMs) span else null
    }

    /** One row per interval while the authoritative source is alive; see [HEARTBEAT_INTERVAL_MS]. */
    fun heartbeatDue(
        latestWallClockMs: Long?,
        latestBootCountMatches: Boolean,
        wallClockMs: Long,
        intervalMs: Long = HEARTBEAT_INTERVAL_MS,
    ): Boolean = latestWallClockMs == null ||
        !latestBootCountMatches ||
        wallClockMs - latestWallClockMs >= intervalMs

    /**
     * §8.7's corroboration: the gap outlasted [escalationThresholdMs] and the platform's own
     * record shows no recoverable activity inside it. When the platform *can* see the window,
     * the accounting is recoverable (it is credited as RECONCILIATION instead) and the outage
     * had no consequence worth interrupting the user for.
     */
    fun isPromptEligible(
        gapMs: Long,
        recoverableCreditMs: Long,
        escalationThresholdMs: Long = ESCALATION_THRESHOLD_MS,
    ): Boolean = gapMs >= escalationThresholdMs && recoverableCreditMs <= 0L

    /**
     * The measured outages in [rows] with the day-split's sibling slices re-joined (ADR #149).
     * [UsageLedgerMapper.splitAtDayBoundary] writes one row per local day an interval crosses,
     * all sharing the detection's wall clock and contiguous in elapsed space — counting them
     * separately reports one overnight silence as two interruptions, and the largest window as
     * only its larger half. Siblings are rows from one detection (same wall clock, boot, and
     * source); rows from different detections never merge even when their intervals abut,
     * because an abutment of separate detections is two interruptions.
     */
    fun mergedOutageSpans(rows: List<UsageLedger>): List<Long> =
        rows.groupBy { Triple(it.wallClockTsMs, it.bootCount, it.source) }
            .values
            .flatMap { detection ->
                val sorted = detection.sortedBy { it.elapsedStartMs }
                val spans = mutableListOf<Long>()
                var start = sorted.first().elapsedStartMs
                var end = sorted.first().elapsedEndMs
                for (row in sorted.drop(1)) {
                    if (row.elapsedStartMs <= end) {
                        end = maxOf(end, row.elapsedEndMs)
                    } else {
                        spans += end - start
                        start = row.elapsedStartMs
                        end = row.elapsedEndMs
                    }
                }
                spans += end - start
                spans
            }

    /** §8.7's summary read: how many outages, and the largest dead window among them. */
    data class OutageSummary(val count: Int, val largestWindowMs: Long)

    fun outageSummary(gapSpansMs: List<Long>): OutageSummary =
        OutageSummary(gapSpansMs.size, gapSpansMs.maxOrNull() ?: 0L)
}
