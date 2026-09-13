package com.example.myexodus.blocking

import java.util.Calendar
import java.util.TimeZone

sealed interface BlockDecision {
    data object Allow : BlockDecision
    data class Block(val severityLevel: String, val isWebsite: Boolean) : BlockDecision
}

/**
 * Decides block/allow against the currently published [RulesSnapshot] (Enhanced Mode spec
 * §8 Step 4 / §6): O(1) map lookup for apps, linear scan over pre-canonicalized URL patterns
 * for websites (single-digit ms at the 2,000-rule cap — plan §8 Step 2). No I/O, no regex
 * compilation, no allocation beyond the result — the snapshot is pre-canonicalized at build
 * time (plan §11).
 *
 * Applies the V1 decision rules:
 * - Rule 3 (absolute expiration): `blockExpiresAt > 0 && now > blockExpiresAt` lifts the
 *   restriction entirely; 0 means "never expires" (the V1 seeded value for always-on rules).
 * - Rule 1 (active schedules): evaluated in the rule's explicit IANA timezone (blocking-core
 *   plan §7.1) — time falls within [timeRangeStart, timeRangeEnd) — crossing midnight
 *   allowed — and the current day-of-week is in `activeDaysOfWeek` ("1111111" = Mon..Sun).
 *   Calendar wall-clock fields in the rule's zone make DST transitions behave as the rule's
 *   local time reads (spring-forward skips the nonexistent hour; fall-back repeats it).
 * - Rule 2 (usage quota): when the caller supplies usage data, `usageTodayMs >= dailyLimitMs`
 *   blocks. The enhanced-mode spec does not source usage on the hot path (V1's
 *   UsageStatsManager flow is untouched, plan §1), so the quota is evaluated only when the
 *   input is provided.
 * - Otherwise `isAlwaysOn` blocks.
 */
class BlockDecisionEngine(
    private val snapshotProvider: () -> RulesSnapshot,
) {
    fun decideApp(packageName: String, nowEpochMillis: Long, usageTodayMs: Long? = null): BlockDecision {
        val rule = snapshotProvider().appRules[packageName] ?: return BlockDecision.Allow
        return evaluate(
            isAlwaysOn = rule.isAlwaysOn,
            dailyLimitMs = rule.dailyLimitMs,
            timeRangeStart = rule.timeRangeStart,
            timeRangeEnd = rule.timeRangeEnd,
            activeDaysOfWeek = rule.activeDaysOfWeek,
            timezone = rule.timezone,
            blockExpiresAt = rule.blockExpiresAt,
            severityLevel = rule.severityLevel,
            isWebsite = false,
            nowEpochMillis = nowEpochMillis,
            usageTodayMs = usageTodayMs,
        )
    }

    fun decideUrl(canonical: CanonicalUrl, nowEpochMillis: Long, usageTodayMs: Long? = null): BlockDecision {
        val pattern = matchingUrlRule(canonical) ?: return BlockDecision.Allow
        return evaluate(
            isAlwaysOn = pattern.isAlwaysOn,
            dailyLimitMs = pattern.dailyLimitMs,
            timeRangeStart = pattern.timeRangeStart,
            timeRangeEnd = pattern.timeRangeEnd,
            activeDaysOfWeek = pattern.activeDaysOfWeek,
            timezone = pattern.timezone,
            blockExpiresAt = pattern.blockExpiresAt,
            severityLevel = pattern.severityLevel,
            isWebsite = true,
            nowEpochMillis = nowEpochMillis,
            usageTodayMs = usageTodayMs,
        )
    }

    /** The URL rule [decideUrl] acts on, or null — the Interception Escape Hatch needs its
     *  ruleId to start the same recovery session the app block screen starts. */
    fun matchingUrlRule(canonical: CanonicalUrl): UrlPattern? =
        snapshotProvider().urlRules.firstOrNull { it.matches(canonical) }

    private fun evaluate(
        isAlwaysOn: Boolean,
        dailyLimitMs: Long,
        timeRangeStart: Long?,
        timeRangeEnd: Long?,
        activeDaysOfWeek: String,
        timezone: String,
        blockExpiresAt: Long,
        severityLevel: String,
        isWebsite: Boolean,
        nowEpochMillis: Long,
        usageTodayMs: Long?,
    ): BlockDecision = evaluateRule(
        isAlwaysOn, dailyLimitMs, timeRangeStart, timeRangeEnd, activeDaysOfWeek, timezone,
        blockExpiresAt, severityLevel, isWebsite, nowEpochMillis, usageTodayMs,
    )

    companion object {

        /**
         * Live "does this rule block right now" state for a single rule — same Rule 1 + Rule 3
         * logic the enforcement path uses, so the dashboard never re-derives schedule semantics
         * (timezone/DST/midnight-crossing) differently from the engine (Req 9/11 home state).
         */
        fun blocksNow(
            isAlwaysOn: Boolean,
            dailyLimitMs: Long,
            timeRangeStart: Long?,
            timeRangeEnd: Long?,
            activeDaysOfWeek: String,
            timezone: String,
            blockExpiresAt: Long,
            nowEpochMillis: Long,
            usageTodayMs: Long? = null,
        ): Boolean = evaluateRule(
            isAlwaysOn, dailyLimitMs, timeRangeStart, timeRangeEnd, activeDaysOfWeek, timezone,
            blockExpiresAt, severityLevel = "", isWebsite = false, nowEpochMillis, usageTodayMs,
        ) is BlockDecision.Block

        private fun evaluateRule(
            isAlwaysOn: Boolean,
            dailyLimitMs: Long,
            timeRangeStart: Long?,
            timeRangeEnd: Long?,
            activeDaysOfWeek: String,
            timezone: String,
            blockExpiresAt: Long,
            severityLevel: String,
            isWebsite: Boolean,
            nowEpochMillis: Long,
            usageTodayMs: Long?,
        ): BlockDecision {
            if (blockExpiresAt > 0 && nowEpochMillis > blockExpiresAt) return BlockDecision.Allow
            // One Calendar per decision, not two (each get() re-derives the time fields).
            // Evaluated in the rule's explicit zone — never the device's implicit default.
            val cal = Calendar.getInstance(TimeZone.getTimeZone(timezone)).apply { timeInMillis = nowEpochMillis }
            val withinWindow = timeRangeStart != null && timeRangeEnd != null &&
                inTimeWindow(cal, timeRangeStart, timeRangeEnd) &&
                dayActive(cal, activeDaysOfWeek)
            if (withinWindow) return BlockDecision.Block(severityLevel, isWebsite)
            if (usageTodayMs != null && dailyLimitMs > 0 && usageTodayMs >= dailyLimitMs) {
                return BlockDecision.Block(severityLevel, isWebsite)
            }
            return if (isAlwaysOn) BlockDecision.Block(severityLevel, isWebsite) else BlockDecision.Allow
        }

        /** Window in minutes-since-midnight; may cross midnight (start > end). */
        private fun inTimeWindow(cal: Calendar, startMin: Long, endMin: Long): Boolean {
            val nowMin = minutesSinceMidnight(cal)
            return if (startMin <= endMin) nowMin >= startMin && nowMin < endMin
            else nowMin >= startMin || nowMin < endMin
        }

        private fun minutesSinceMidnight(cal: Calendar): Long =
            cal.get(Calendar.HOUR_OF_DAY) * 60L + cal.get(Calendar.MINUTE)

        /** "1111111" = Mon..Sun (index 0 = Monday). Day check uses the current day; for
         *  midnight-crossing windows the post-midnight hours inherit the same day requirement. */
        private fun dayActive(cal: Calendar, activeDaysOfWeek: String): Boolean {
            val dayIndex = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7 // Mon=0 .. Sun=6
            return activeDaysOfWeek.getOrNull(dayIndex) == '1'
        }
    }
}
