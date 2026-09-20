package com.example.myexodus.blocking

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar
import java.util.TimeZone

/** The day string that restricts nothing — what a rule carries until a day scope narrows it. */
internal const val EVERY_DAY = "1111111"

/** The one place the day-string convention lives: index 0 = Monday ("1111111" = Mon..Sun).
 *  Shared with the accounting engine and both enforcement services so the same bits always
 *  mean the same day. A short/malformed string matches no day. */
internal fun dayActiveOn(activeDaysOfWeek: String, dayOfWeek: DayOfWeek): Boolean =
    activeDaysOfWeek.getOrNull(dayOfWeek.value - 1) == '1'

/** A rule's zone as java.time; an unrecognized id falls back to the device zone. The editor
 *  only ever writes valid IANA ids — the fallback is for hand-edited, harness, or legacy rows,
 *  and it must not throw inside an accounting transaction. */
internal fun ruleZoneId(timezone: String): ZoneId =
    runCatching { ZoneId.of(timezone) }.getOrDefault(ZoneId.systemDefault())

/**
 * True when a rule's day scope covers *now*, judged in the rule's own zone — the shared gate
 * for "does this rule operate today" on the accounting and enforcement paths. The decision
 * engine does NOT use this: it evaluates days off the caller's `nowEpochMillis` so a decision
 * about any instant is reproducible (tests, dashboards) rather than pinned to wall-clock now.
 */
internal fun dayScopeCoversToday(activeDaysOfWeek: String, timezone: String): Boolean =
    activeDaysOfWeek == EVERY_DAY ||
        dayActiveOn(activeDaysOfWeek, LocalDate.now(ruleZoneId(timezone)).dayOfWeek)

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
 * - Day scope: `activeDaysOfWeek` gates the WHOLE rule (user decision 2026-09-20) — window,
 *   quota, and always-on all apply only on listed days. "1111111" (every rule written before
 *   day scoping) therefore behaves exactly as it did.
 * - Rule 1 (active schedules): evaluated in the rule's explicit IANA timezone (blocking-core
 *   plan §7.1) — time falls within [timeRangeStart, timeRangeEnd) — crossing midnight
 *   allowed — and the day-of-week is active. Calendar wall-clock fields in the rule's
 *   zone make DST transitions behave as the rule's local time reads (spring-forward skips the
 *   nonexistent hour; fall-back repeats it). An overnight window's post-midnight hours are
 *   judged against the day the window STARTED (user ruling 2026-09-20, matching
 *   RiskWindowEvaluator): Friday 22:00-07:00 still runs at Saturday 03:00.
 * - Rule 2 (usage quota): when the caller supplies usage data, `usageTodayMs >= dailyLimitMs`
 *   blocks — on active days only. The enhanced-mode spec does not source usage on the hot
 *   path (V1's UsageStatsManager flow is untouched, plan §1), so the quota is evaluated only
 *   when the input is provided.
 * - Otherwise `isAlwaysOn` blocks — on active days only.
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
            val active = dayActive(cal, activeDaysOfWeek)
            val withinWindow = timeRangeStart != null && timeRangeEnd != null &&
                inTimeWindow(cal, timeRangeStart, timeRangeEnd) && windowDayActive(cal, activeDaysOfWeek, timeRangeStart, timeRangeEnd)
            if (withinWindow) return BlockDecision.Block(severityLevel, isWebsite)
            if (usageTodayMs != null && dailyLimitMs > 0 && active && usageTodayMs >= dailyLimitMs) {
                return BlockDecision.Block(severityLevel, isWebsite)
            }
            return if (isAlwaysOn && active) BlockDecision.Block(severityLevel, isWebsite) else BlockDecision.Allow
        }

        /** Window in minutes-since-midnight; may cross midnight (start > end). */
        private fun inTimeWindow(cal: Calendar, startMin: Long, endMin: Long): Boolean {
            val nowMin = minutesSinceMidnight(cal)
            return if (startMin <= endMin) nowMin >= startMin && nowMin < endMin
            else nowMin >= startMin || nowMin < endMin
        }

        private fun minutesSinceMidnight(cal: Calendar): Long =
            cal.get(Calendar.HOUR_OF_DAY) * 60L + cal.get(Calendar.MINUTE)

        /** Calendar's SUNDAY=1..SATURDAY=7 as java.time, in the rule's zone. */
        private fun dayOfWeek(cal: Calendar): DayOfWeek =
            DayOfWeek.of((cal.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1)

        /** The quota and always-on gates: the day it is *now* in the rule's zone. */
        private fun dayActive(cal: Calendar, activeDaysOfWeek: String): Boolean =
            dayActiveOn(activeDaysOfWeek, dayOfWeek(cal))

        /** Window gate: an overnight window's post-midnight leg belongs to the day it STARTED —
         *  Friday 22:00-07:00 is still Friday at Saturday 03:00 (user ruling 2026-09-20).
         *  Only reached once [inTimeWindow] has already said yes. */
        private fun windowDayActive(cal: Calendar, activeDaysOfWeek: String, startMin: Long, endMin: Long): Boolean {
            val day = if (endMin < startMin && minutesSinceMidnight(cal) < endMin) dayOfWeek(cal).minus(1) else dayOfWeek(cal)
            return dayActiveOn(activeDaysOfWeek, day)
        }
    }
}
