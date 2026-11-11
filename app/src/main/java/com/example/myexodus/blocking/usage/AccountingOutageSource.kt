package com.example.myexodus.blocking.usage

import android.content.Context
import com.example.myexodus.data.db.VaultLockedException
import com.example.myexodus.settings.isBatteryExempt
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * D1/§8.7's read model for the three outage surfaces: the corroborated interruptions still
 * fresh enough to describe, paired with whether the exemption offer may accompany them.
 *
 * The evidence is the ledger, never a second counter store (AD-15): the count and the largest
 * window are derived from GAP_DETECTED rows, with the zero-length reboot boundaries excluded
 * by [AccountingDiagnostics.isMeasuredOutage] — a reboot is not an interruption to report.
 * The offer state is [AccountingDiagnosticsState]'s eligibility plus the platform's own
 * exemption answer.
 */
@Singleton
class AccountingOutageSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ledger: UsageLedgerRepository,
    private val state: AccountingDiagnosticsState,
) {
    /** The current outage report, or null when nothing measurable happened inside the
     *  freshness window — the same window the offer's own eligibility uses, so the report and
     *  the offer never disagree about what "recent" means. #13: the same read also yields the
     *  one-time "tracking can run again" confirmation ([AccountingOutage.trackingResumed]).
     *
     *  ADR #149: only windows the platform has not accounted for are reportable — a resolved
     *  gap (its window was queried; whatever it held was credited, or nothing was held) has no
     *  "some usage may not be counted" left in it. Counting resolved windows was the cry-wolf
     *  defect: nightly device sleep fabricates gap rows that always resolve with nothing lost.
     *  Sibling slices of one day-split detection re-join before counting so one overnight
     *  silence is one interruption. */
    suspend fun current(nowWallClockMs: Long = System.currentTimeMillis()): AccountingOutage? {
        val since = nowWallClockMs - AccountingDiagnosticsState.ELIGIBILITY_FRESHNESS_MS
        val unresolved = try {
            ledger.measuredOutagesSince(since)
        } catch (e: VaultLockedException) {
            // §8.9: a locked vault is a deferral, not a degradation — the surfaces re-query
            // on the next unlocked entry.
            return null
        }.filter { it.wallClockTsMs > state.gapsResolvedThroughMs }
        val exempt = isBatteryExempt(context)
        val stage = OutageStage.stage(
            newestMeasuredOutageAtMs = unresolved.maxOfOrNull { it.wallClockTsMs } ?: 0L,
            batteryExempt = exempt,
            lastFixFiredAtMs = state.lastPromptMs,
            dismissedAtMs = state.dismissedAtMs,
            nowWallClockMs = nowWallClockMs,
        )
        val summary = AccountingDiagnostics.outageSummary(
            AccountingDiagnostics.mergedOutageSpans(unresolved),
        )
        return when (stage) {
            // No reportable evidence (none, or the user dismissed all of it) — the offer still
            // has its own fresh evidence to consult (ADR #149).
            OutageStage.Stage.NONE -> offerReport(nowWallClockMs, exempt)
            OutageStage.Stage.CONFIRMED -> AccountingOutage(
                summary = summary,
                promptRoute = null,
                trackingResumed = true,
            )
            OutageStage.Stage.REPORT -> AccountingOutage(
                summary = summary,
                promptRoute = BatteryPromptGate.route(
                    alreadyExempt = exempt,
                    promptEligible = state.promptEligible(nowWallClockMs),
                ),
            )
        }
    }

    /**
     * ADR #149: R8's offer report — the one report a *resolved* gap still produces. §8.7's
     * eligible gap (long, nothing recoverable in its window) is the evidence the exemption
     * offer exists to explain, so the offer keeps its surfaces even though the window itself
     * is no longer counted. Exists exactly while an offer can be made: exempt devices (nothing
     * to offer) and dismissed/consumed eligibilities yield nothing, and the offer's own
     * cooldowns apply via [AccountingDiagnosticsState.promptEligible].
     */
    private fun offerReport(nowWallClockMs: Long, exempt: Boolean): AccountingOutage? {
        val since = state.promptEligibleSinceMs
        val gapMs = state.promptEligibleGapMs
        if (since == 0L || gapMs <= 0L || since <= state.dismissedAtMs) return null
        if (!state.promptEligible(nowWallClockMs)) return null
        val route = BatteryPromptGate.route(alreadyExempt = exempt, promptEligible = true)
            ?: return null
        return AccountingOutage(
            summary = AccountingDiagnostics.OutageSummary(1, gapMs),
            promptRoute = route,
        )
    }

    /** §8.7's prompt cooldown bookkeeping: the offer was accepted (the user fired the fix), so
     *  the eligibility it came from is consumed and no repeat offer is made for the cooldown. */
    fun markOfferShown(nowWallClockMs: Long = System.currentTimeMillis()) =
        state.markPromptShown(nowWallClockMs)

    /** #13: the user acknowledged the report (or its confirmation) — every outage surface goes
     *  quiet until a newer interruption is measured (see [OutageStage]). */
    fun dismiss(nowWallClockMs: Long = System.currentTimeMillis()) =
        state.markDismissed(nowWallClockMs)
}
