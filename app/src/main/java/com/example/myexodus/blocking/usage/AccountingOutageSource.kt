package com.example.myexodus.blocking.usage

import android.content.Context
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
     *  the offer never disagree about what "recent" means. */
    suspend fun current(nowWallClockMs: Long = System.currentTimeMillis()): AccountingOutage? {
        val since = nowWallClockMs - AccountingDiagnosticsState.ELIGIBILITY_FRESHNESS_MS
        val spans = ledger.outageSpansSince(since)
        if (spans.isEmpty()) return null
        return AccountingOutage(
            summary = AccountingDiagnostics.outageSummary(spans),
            promptRoute = BatteryPromptGate.route(
                alreadyExempt = isBatteryExempt(context),
                promptEligible = state.promptEligible(nowWallClockMs),
            ),
        )
    }

    /** §8.7's prompt cooldown bookkeeping: the offer was accepted (the user fired the fix), so
     *  the eligibility it came from is consumed and no repeat offer is made for the cooldown. */
    fun markOfferShown(nowWallClockMs: Long = System.currentTimeMillis()) =
        state.markPromptShown(nowWallClockMs)
}
