package com.example.myexodus.ui

import android.content.res.Resources
import com.example.myexodus.R
import com.example.myexodus.blocking.BlockDecisionEngine
import com.example.myexodus.blocking.EVERY_DAY
import com.example.myexodus.data.db.entity.RestrictionRule
import com.example.myexodus.data.db.entity.isExpiredAt
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/** Year 9999 — anything past this reads as "never expires" to a human. */
private const val FAR_FUTURE_EPOCH_MILLIS = 253_402_300_799_999L

/** One rule's live state as a row shows it; `label == null` = no honest claim available. */
data class RuleRowState(val label: String?, val active: Boolean)

/**
 * Definite live state only: a usage-based rule with no live usage feed gets no label rather
 * than a claim the engine hasn't made (Rule 2 depends on today's usage, which has no Flow).
 * Paused and expired are decided before the engine — those rules aren't enforced at all.
 */
fun ruleRowState(rule: RestrictionRule, nowEpochMillis: Long, res: Resources): RuleRowState {
    if (!rule.isEnabled) return RuleRowState(res.getString(R.string.state_paused), active = false)
    if (rule.isExpiredAt(nowEpochMillis)) return RuleRowState(res.getString(R.string.state_expired), active = false)
    val blocking = BlockDecisionEngine.blocksNow(
        isAlwaysOn = rule.isAlwaysOn,
        dailyLimitMs = rule.dailyLimitMs,
        timeRangeStart = rule.timeRangeStart,
        timeRangeEnd = rule.timeRangeEnd,
        activeDaysOfWeek = rule.activeDaysOfWeek,
        timezone = rule.timezone,
        blockExpiresAt = rule.blockExpiresAt,
        nowEpochMillis = nowEpochMillis,
    )
    return when {
        blocking -> RuleRowState(res.getString(R.string.state_blocked_now), active = true)
        rule.dailyLimitMs > 0 -> RuleRowState(null, active = false)
        else -> RuleRowState(res.getString(R.string.state_not_active_now), active = false)
    }
}

/** Human summary of a rule's schedule: mechanism (+overnight), day scope, limit, expiry.
 *  Days scope the whole rule (user decision 2026-09-20), so they print once — and only when
 *  they narrow the rule from every day. */
fun scheduleSummary(rule: RestrictionRule, res: Resources): String {
    val parts = mutableListOf<String>()
    if (rule.isAlwaysOn) {
        parts += res.getString(R.string.schedule_always_on)
    }
    if (rule.timeRangeStart != null && rule.timeRangeEnd != null) {
        val window = "${formatTime(rule.timeRangeStart)}–${formatTime(rule.timeRangeEnd)}"
        parts += if (rule.isOvernight) res.getString(R.string.schedule_overnight_window, window) else window
    }
    // A limit beside "always on" is inert — the rule already covers every hour — so the row
    // never claims it. The editor zeroes it on save; this is for rules saved before that.
    if (rule.dailyLimitMs > 0 && !rule.isAlwaysOn) {
        parts += res.getString(R.string.schedule_daily_limit, formatDuration(rule.dailyLimitMs))
    }
    val days = daysLabel(rule.activeDaysOfWeek, res)
    if (days != null && rule.activeDaysOfWeek != EVERY_DAY) parts += days
    // 0 = never expires (engine semantics); Long.MAX_VALUE is the harness's "never" sentinel —
    // rendering it as a date shows year 292278994.
    if (rule.blockExpiresAt > 0 && rule.blockExpiresAt < FAR_FUTURE_EPOCH_MILLIS) {
        parts += res.getString(R.string.schedule_until, formatDate(rule.blockExpiresAt))
    }
    return if (parts.isEmpty()) res.getString(R.string.schedule_none) else parts.joinToString(" · ")
}

/** end < start crosses midnight: the window ends on a later day than it starts. */
val RestrictionRule.isOvernight: Boolean
    get() = timeRangeStart != null && timeRangeEnd != null && timeRangeEnd <= timeRangeStart

fun formatTime(minutesOfDay: Long): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        .format(LocalTime.ofSecondOfDay(minutesOfDay * 60))

fun formatDate(epochMillis: Long): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate())

fun formatDuration(ms: Long): String {
    val totalMinutes = ms / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
        hours > 0 -> "${hours}h"
        else -> "${minutes}m"
    }
}

/** Monday-first day string ↔ the bitmask shape (bit0 = Monday), shared by both editors. */
fun daysMaskToString(daysMask: Int): String =
    (0..6).joinToString("") { if (daysMask and (1 shl it) != 0) "1" else "0" }

fun daysStringToMask(days: String): Int =
    (0..6).fold(0) { mask, index -> if (days.getOrNull(index) == '1') mask or (1 shl index) else mask }

/** Flips one day, always returning a full seven characters — a short string would silently
 *  mean "no day matches" once it reaches the mask conversion. Clearing the last remaining day
 *  is refused: a days set with no day never matches, so the whole rule could never fire. */
fun toggleDay(days: String, index: Int): String {
    if (days.getOrNull(index) == '1' && days.count { it == '1' } == 1) return days
    val chars = days.padEnd(7, '0').toCharArray()
    chars[index] = if (chars[index] == '1') '0' else '1'
    return String(chars)
}

fun daysLabel(activeDays: String, res: Resources): String? {
    if (activeDays.length < 7) return null
    if (activeDays.all { it == '1' }) return res.getString(R.string.days_every_day)
    if (activeDays == "1111100") return res.getString(R.string.days_weekdays)
    if (activeDays == "0000011") return res.getString(R.string.days_weekends)
    return (0..6).filter { activeDays[it] == '1' }
        .joinToString(", ") {
            DayOfWeek.of(it + 1).getDisplayName(TextStyle.SHORT, Locale.getDefault())
        }
}
