package com.example.myexodus.blocking.usage

import android.util.Log
import com.example.myexodus.data.db.entity.UsageLedger
import com.example.myexodus.data.db.entity.UsageLedgerSource
import com.example.myexodus.telemetry.CrashReporting
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The wall-clock window a recorded gap leaves open to platform recovery: end-exclusive, and
 * clipped exactly like the process-death repair ([UsageGapRepair.repairWindow]) so both
 * recovery paths credit comparable spans. [elapsedFromMs]/[elapsedToMs] are the same window in
 * monotonic space — what the RECONCILIATION row's interval is built from.
 *
 * [bootBoundary] marks the §8.5 reboot record: the elapsed frame restarts at the boot, so the
 * window's pre-boot part has no monotonic representation and the elapsed pair is the post-boot
 * mapping (0 → now). Such a window is recovery-only — never prompt evidence, because §8.7's
 * escalation requires a boot count unchanged since the last observation.
 */
data class GapRecoveryRequest(
    val window: LongRange,
    val elapsedFromMs: Long,
    val elapsedToMs: Long,
    val gapSpanMs: Long,
    val bootBoundary: Boolean = false,
)

/**
 * §8.7's gap detector, run on the FGS's 30 s cycle: the authoritative accounting source's
 * silence is measured against the ledger itself (§8.4/AD-15 — the baseline is the latest
 * existing row, never a separate store), a silence past cadence-plus-tolerance becomes a
 * GAP_DETECTED row, and an actively accounting pipeline gets a HEARTBEAT row at most once per
 * [AccountingDiagnostics.HEARTBEAT_INTERVAL_MS] instead.
 *
 * Binder work is deliberately *not* here: the caller (the service) queries the platform and
 * feeds the outcome back through [recordRecovery]. That split is what keeps §8.7's
 * corroboration honest — "no recoverable activity" is only ever concluded from a real platform
 * answer, never from a query this class could not have made. AD-13 throughout: everything
 * recorded here says tracking was interrupted, never why.
 */
@Singleton
class UsageDiagnosticsRecorder @Inject constructor(
    private val ledger: UsageLedgerRepository,
    private val state: AccountingDiagnosticsState,
    private val crashReporting: CrashReporting,
) {
    /**
     * One diagnostics cycle for [source] (the current authority holder — callers pass nothing
     * when no source holds accounting authority). [trackingActive] is the caller's answer to
     * "is anything actually being accounted right now" (any enabled daily-limit rule): when
     * false, pipeline silence is the expected state, not an outage — only the liveness
     * baseline is kept fresh, so a later activation measures from its own first observation
     * and can never inherit a pre-tracking "interruption" (owner-reported false 18 h outage
     * popup after enabling one limit, 2026-10-03). Returns the recovery window when a gap was
     * recorded, null otherwise.
     *
     * Must NOT be wrapped in the accounting dispatcher by callers: the ledger calls inside hop
     * there themselves, and a nested `limitedParallelism(1)` view would deadlock the single
     * accounting slot against itself.
     */
    suspend fun pass(
        source: String,
        bootCount: Int,
        elapsedNowMs: Long,
        wallClockMs: Long,
        trackingActive: Boolean = true,
    ): GapRecoveryRequest? {
        // §9's "unavailable boot count": nothing is attributable to a monotonic frame that
        // cannot be named, and nothing is invented to fill in for it. Record and stop.
        if (bootCount < 0) {
            crashReporting.nonFatal("accounting_clock_anomaly", cause = "boot_count_unreadable")
            return null
        }
        val baseline = latestAccountingEvidence()
        // Nothing tracked → silence is expected, not measurable: no gap row and no reboot row
        // (neither interrupted anything). The heartbeat still runs — boot mismatch included,
        // where heartbeatDue fires immediately — so the baseline advances and activation
        // later starts the gap detector from its own moment.
        if (!trackingActive) {
            if (AccountingDiagnostics.heartbeatDue(baseline?.wallClockTsMs, baseline?.bootCount == bootCount, wallClockMs)) {
                ledger.appendStandalone(
                    AccountingDiagnostics.heartbeatDraft(source, bootCount, wallClockMs, elapsedNowMs),
                )
            }
            return null
        }
        // §8.5's reboot flow: the interval spanning the boundary is never computed by
        // subtracting elapsed values. It is recorded as a zero-length boundary gap (the
        // RECONCILIATION-shaped record [AccountingDiagnostics.rebootGapDraft] documents), and
        // the wall window it leaves behind is offered to platform recovery.
        if (baseline != null && baseline.bootCount != bootCount) {
            ledger.appendStandalone(
                AccountingDiagnostics.rebootGapDraft(source, bootCount, wallClockMs, elapsedNowMs),
            )
            Log.w(TAG, "reboot boundary ${baseline.bootCount} -> $bootCount, wall window ${baseline.wallClockTsMs}..$wallClockMs")
            crashReporting.nonFatal(
                "accounting_reboot_boundary",
                cause = "boot_count_changed",
                "unobserved_ms" to (wallClockMs - baseline.wallClockTsMs).toString(),
            )
            return rebootRecoveryRequest(baseline.wallClockTsMs, wallClockMs, elapsedNowMs)
        }
        val bootMatches = baseline?.bootCount == bootCount
        val gapSpanMs = baseline?.let {
            AccountingDiagnostics.gapSpan(it.bootCount, bootCount, it.elapsedEndMs, elapsedNowMs)
        }
        var request: GapRecoveryRequest? = null
        if (baseline != null && gapSpanMs != null) {
            ledger.appendStandalone(
                AccountingDiagnostics.gapDraft(
                    source, bootCount, wallClockMs, baseline.elapsedEndMs, elapsedNowMs,
                ),
            )
            Log.w(TAG, "measurement gap ${gapSpanMs}ms since elapsed=${baseline.elapsedEndMs} source=$source")
            crashReporting.nonFatal(
                "accounting_measurement_gap",
                cause = "no_observation",
                "gap_ms" to gapSpanMs.toString(),
                "source" to source,
            )
            request = recoveryRequest(gapSpanMs, wallClockMs, elapsedNowMs)
        }
        // The gap row above is already liveness evidence anchored at "now"; otherwise the
        // authoritative source earns one heartbeat per interval while it has nothing newer.
        if (request == null && AccountingDiagnostics.heartbeatDue(baseline?.wallClockTsMs, bootMatches, wallClockMs)) {
            ledger.appendStandalone(
                AccountingDiagnostics.heartbeatDraft(source, bootCount, wallClockMs, elapsedNowMs),
            )
        }
        return request
    }

    /**
     * §8.7's corroboration outcome for the gap [pass] just returned. [recoverableMs] is the
     * platform's own foreground time inside the window (0 = the interruption hid nothing), or
     * null when the platform could not be consulted — an unverifiable window is never escalated.
     * [bootBoundary] carries the request's own marker: escalation requires a boot count
     * unchanged since the last observation, so a reboot window is recovery-only.
     *
     * ADR #149: a numeric answer also advances the resolved-through watermark — the platform
     * accounted for the window (whatever it held was credited, or nothing was held), so the
     * outage report must stop counting it as "some usage may not be counted". The no-answer
     * cases deliberately leave the watermark alone: an unverifiable window is exactly what the
     * report is for.
     */
    fun recordRecovery(gapSpanMs: Long, recoverableMs: Long?, nowWallClockMs: Long, bootBoundary: Boolean) {
        if (recoverableMs == null || bootBoundary) return
        state.markGapsResolvedThrough(nowWallClockMs)
        if (AccountingDiagnostics.isPromptEligible(gapSpanMs, recoverableMs)) {
            state.markPromptEligible(nowWallClockMs, gapSpanMs)
        } else {
            // Recoverable activity: the outage had a consequence that is already being
            // repaired, so there is nothing to ask the user about.
            state.clearEligibility()
        }
    }

    /**
     * ADR #149's one-time upgrade seed, called by the service on any diagnostics pass that
     * holds platform access: the first such pass presumes every pre-existing measured gap
     * resolved. Pre-fix builds counted corroborated and uncorroborated gaps alike (the defect
     * this fixes) and stored no distinction, so the backlog cannot be re-classified — and its
     * harm is bounded, because the report's own 24 h freshness ages it out. A device without
     * usage access never seeds: there the count is genuinely unverifiable and stays honest.
     */
    fun seedResolvedThrough(nowWallClockMs: Long) {
        if (state.gapsResolvedThroughMs == 0L) state.markGapsResolvedThrough(nowWallClockMs)
    }

    /**
     * ADR #160's credit-edge audit, reporting its own numeric answer. The audit queries the
     * platform across the uncredited span just like a gap recovery does, so the watermark
     * advances exactly as [recordRecovery]'s does for a real answer: whatever the span held
     * was credited, or was found empty — either way the outage report must stop counting
     * windows inside it. Never called without a real answer; a skipped or failed query
     * leaves the watermark where it was.
     */
    fun markResolvedThrough(nowWallClockMs: Long) {
        state.markGapsResolvedThrough(nowWallClockMs)
    }

    /**
     * The newest row either live accounting path wrote. Merged rather than per-source on
     * purpose: an authority handoff (Path A ↔ Path B) is ordinary, and measuring silence from
     * the *new* holder's own last row would misreport the handoff as an outage (§8.7 measures
     * observation, and during a handoff observation never stopped). HEARTBEAT and GAP_DETECTED
     * rows count as evidence — that is exactly what they are for.
     */
    private suspend fun latestAccountingEvidence(): UsageLedger? = listOfNotNull(
        ledger.latestForSource(UsageLedgerSource.PATH_A),
        ledger.latestForSource(UsageLedgerSource.PATH_B),
    ).maxByOrNull { it.id }

    /** The wall window the gap leaves recoverable, in both clocks. Null when the gap is not
     *  recoverable-as-a-window: fully before today's midnight, or too short to be worth a
     *  query. */
    private fun recoveryRequest(
        gapSpanMs: Long,
        wallClockMs: Long,
        elapsedNowMs: Long,
    ): GapRecoveryRequest? {
        val window = UsageGapRepair.repairWindow(wallClockMs - gapSpanMs, wallClockMs, localMidnight(wallClockMs))
            ?: return null
        return GapRecoveryRequest(
            window = window,
            elapsedFromMs = elapsedNowMs - (wallClockMs - window.first),
            elapsedToMs = elapsedNowMs - (wallClockMs - (window.last + 1)),
            gapSpanMs = gapSpanMs,
        )
    }

    /**
     * §8.5's reboot recovery window: (last observation, now], clipped to today's local midnight
     * so the credit lands on the day the projection can hold it (AD-18 — §8.11's "goes forward"
     * rule). No repair cap applies: the pre-shutdown part is exactly what the query is for, and
     * the midnight clip is what bounds it. [UsageGapRepair.repairWindow]'s floor is not applied
     * either — a reboot boundary is never ordinary transition jitter.
     *
     * The elapsed pair is the *post-boot* mapping: the pre-boot part of the window has no
     * monotonic representation in this frame (§8.5), so the row's interval starts at this boot's
     * zero. That can only ever under-credit the pre-boot share when the same-boot overlap check
     * scales it down — never double-credit, which is the direction that matters.
     */
    private fun rebootRecoveryRequest(
        baselineWallClockMs: Long,
        wallClockMs: Long,
        elapsedNowMs: Long,
    ): GapRecoveryRequest? {
        val from = maxOf(baselineWallClockMs, localMidnight(wallClockMs))
        if (wallClockMs <= from) return null
        return GapRecoveryRequest(
            window = from until wallClockMs,
            elapsedFromMs = 0L,
            elapsedToMs = elapsedNowMs,
            gapSpanMs = wallClockMs - baselineWallClockMs,
            bootBoundary = true,
        )
    }

    /** Today's local midnight *as the caller's wall clock reads it* — never the process clock,
     *  so a test can place the boundary at any hour. */
    private fun localMidnight(wallClockMs: Long): Long {
        val zone = ZoneId.systemDefault()
        return Instant.ofEpochMilli(wallClockMs).atZone(zone).toLocalDate().atStartOfDay(zone)
            .toInstant().toEpochMilli()
    }

    private companion object {
        const val TAG = "MyExodusLedger"
    }
}
