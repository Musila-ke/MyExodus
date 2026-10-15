package com.example.myexodus.blocking.usage

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §8.7's prompt-eligibility status and nothing else — the gap evidence itself lives in the
 * ledger, and the ledger is the baseline (§8.4/AD-15). These keys only carry the *user-facing
 * consequence* of a corroborated gap, which is why they are plain preferences: they have a
 * lifetime of hours and no accounting meaning.
 *
 * AD-13: "eligible" means *a corroborated tracking interruption is waiting to be explained* —
 * it is never a diagnosed cause, and nothing read from here may be presented as one.
 */
@Singleton
class AccountingDiagnosticsState @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Wall clock of the most recent corroborated eligible gap; 0 = none waiting. */
    val promptEligibleSinceMs: Long get() = prefs.getLong(KEY_ELIGIBLE_SINCE, 0L)

    /** The span of the gap that made the offer eligible — the offer report's own evidence when
     *  no unresolved window exists to describe (ADR #149). 0 = none waiting. */
    val promptEligibleGapMs: Long get() = prefs.getLong(KEY_ELIGIBLE_GAP_MS, 0L)

    /**
     * Wall clock through which measured gaps have been resolved by a real platform answer
     * (ADR #149): the outage read model counts only gaps beyond this, because an interruption
     * whose window the platform has already accounted for has no "usage may not be counted"
     * left to report. Monotonic by construction ([markGapsResolvedThrough] keeps the max), and
     * committed synchronously — the write happens on the FGS tick right before a kill can
     * land, so the #146 apply()-loss doctrine applies here too. 0 = nothing resolved yet.
     */
    val gapsResolvedThroughMs: Long get() = prefs.getLong(KEY_RESOLVED_THROUGH, 0L)

    val lastPromptMs: Long get() = prefs.getLong(KEY_LAST_PROMPT, 0L)

    val lastDeclinedMs: Long get() = prefs.getLong(KEY_LAST_DECLINED, 0L)

    /** #13: wall clock of the last dismissal of an outage report; 0 = never dismissed. */
    val dismissedAtMs: Long get() = prefs.getLong(KEY_DISMISSED, 0L)

    fun markPromptEligible(nowWallClockMs: Long, gapMs: Long) {
        prefs.edit {
            putLong(KEY_ELIGIBLE_SINCE, nowWallClockMs)
            putLong(KEY_ELIGIBLE_GAP_MS, gapMs)
        }
    }

    /** ADR #149: a real platform answer resolved every measured gap up to [nowWallClockMs] —
     *  its window was queried and its consequence settled. Never moves backward. */
    fun markGapsResolvedThrough(nowWallClockMs: Long) {
        if (nowWallClockMs <= gapsResolvedThroughMs) return
        prefs.edit(commit = true) { putLong(KEY_RESOLVED_THROUGH, nowWallClockMs) }
    }

    /** #13: the report was acknowledged — every outage surface stays silent for evidence at or
     *  before this instant. A newer measured interruption re-raises the report: a dismissal
     *  silences the report it named, never future ones. */
    fun markDismissed(nowWallClockMs: Long) {
        prefs.edit { putLong(KEY_DISMISSED, nowWallClockMs) }
    }

    /** The interruption that made this eligible either turned out to be recoverable or has
     *  been explained — either way there is nothing left to ask the user about. */
    fun clearEligibility() {
        prefs.edit {
            remove(KEY_ELIGIBLE_SINCE)
            remove(KEY_ELIGIBLE_GAP_MS)
        }
    }

    fun markPromptShown(nowWallClockMs: Long) {
        prefs.edit {
            putLong(KEY_LAST_PROMPT, nowWallClockMs)
            remove(KEY_ELIGIBLE_SINCE)
            remove(KEY_ELIGIBLE_GAP_MS)
        }
    }

    fun markDeclined(nowWallClockMs: Long) {
        prefs.edit {
            putLong(KEY_LAST_DECLINED, nowWallClockMs)
            remove(KEY_ELIGIBLE_SINCE)
            remove(KEY_ELIGIBLE_GAP_MS)
        }
    }

    /**
     * R8's full gate, minus the platform checks the caller owns (already-exempt, channel
     * eligibility): an eligible gap exists, it is fresh enough to still describe something the
     * user will recognise, and no cooldown is running. Cooldowns are deliberate — a prompt for
     * a tracking interruption is an offer, and an offer that keeps reappearing is nagging.
     */
    fun promptEligible(
        nowWallClockMs: Long,
        freshnessMs: Long = ELIGIBILITY_FRESHNESS_MS,
        promptCooldownMs: Long = PROMPT_COOLDOWN_MS,
        declineCooldownMs: Long = DECLINE_COOLDOWN_MS,
    ): Boolean {
        val since = promptEligibleSinceMs
        if (since == 0L || nowWallClockMs - since > freshnessMs) return false
        val shown = lastPromptMs
        if (shown != 0L && nowWallClockMs - shown < promptCooldownMs) return false
        val declined = lastDeclinedMs
        if (declined != 0L && nowWallClockMs - declined < declineCooldownMs) return false
        return true
    }

    companion object {
        private const val PREFS_NAME = "accounting_diagnostics"
        private const val KEY_ELIGIBLE_SINCE = "prompt_eligible_since_ms"
        private const val KEY_ELIGIBLE_GAP_MS = "prompt_eligible_gap_ms"
        private const val KEY_RESOLVED_THROUGH = "gaps_resolved_through_ms"
        private const val KEY_LAST_PROMPT = "last_prompt_ms"
        private const val KEY_LAST_DECLINED = "last_declined_ms"
        private const val KEY_DISMISSED = "dismissed_at_ms"

        /**
         * An interruption recorded long ago no longer explains what the user is seeing, so it
         * stops being prompt-worthy. The cooldown values are product choices, revisable on
         * device evidence like every other on-device constant: 24 h between offers, a week
         * after a decline.
         */
        const val ELIGIBILITY_FRESHNESS_MS = 24 * 60 * 60 * 1000L
        const val PROMPT_COOLDOWN_MS = 24 * 60 * 60 * 1000L
        const val DECLINE_COOLDOWN_MS = 7 * 24 * 60 * 60 * 1000L
    }
}
