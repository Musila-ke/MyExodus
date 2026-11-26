package com.example.myexodus.ui

import android.content.res.Resources
import com.example.myexodus.R
import com.example.myexodus.accountability.serialization.RuleSchedule
import com.example.myexodus.blocking.BlockDecisionEngine
import com.example.myexodus.blocking.EVERY_DAY
import com.example.myexodus.data.db.entity.RestrictionRule
import com.example.myexodus.data.db.entity.isExpiredAt
import com.example.myexodus.ui.partner.RelativeTime
import com.example.myexodus.ui.partner.relativeTime
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
 * Definite live state only: a usage-based rule whose usage is not known yet gets no label
 * rather than a claim the engine hasn't made (Rule 2 needs today's usage). Callers with a
 * live ledger pass [usageTodayMs]; those without get the same no-claim behavior as before.
 * Paused and expired are decided before the engine — those rules aren't enforced at all.
 */
fun ruleRowState(
    rule: RestrictionRule,
    nowEpochMillis: Long,
    res: Resources,
    usageTodayMs: Long? = null,
): RuleRowState {
    // Expired wins over paused: a rule the #18 sweep flipped off is off *because its duration
    // lapsed*, and "Paused" would blame the user for a switch they never touched.
    if (rule.isExpiredAt(nowEpochMillis)) return RuleRowState(res.getString(R.string.state_expired), active = false)
    if (!rule.isEnabled) return RuleRowState(res.getString(R.string.state_paused), active = false)
    val blocking = BlockDecisionEngine.blocksNow(
        isAlwaysOn = rule.isAlwaysOn,
        dailyLimitMs = rule.dailyLimitMs,
        timeRangeStart = rule.timeRangeStart,
        timeRangeEnd = rule.timeRangeEnd,
        activeDaysOfWeek = rule.activeDaysOfWeek,
        timezone = rule.timezone,
        blockExpiresAt = rule.blockExpiresAt,
        nowEpochMillis = nowEpochMillis,
        usageTodayMs = usageTodayMs,
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
        parts += windowLabel(rule.timeRangeStart, rule.timeRangeEnd, res)
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
        parts += res.getString(R.string.schedule_until, formatExpiry(rule.blockExpiresAt))
    }
    return if (parts.isEmpty()) res.getString(R.string.schedule_none) else parts.joinToString(" · ")
}

/** end <= start crosses midnight: the window ends on a later day than it starts. */
private fun windowLabel(startMinute: Long, endMinute: Long, res: Resources): String {
    val window = "${formatTime(startMinute)}–${formatTime(endMinute)}"
    return if (endMinute <= startMinute) res.getString(R.string.schedule_overnight_window, window) else window
}

/** OPD #48: one changed schedule field as the partner's rule card shows it — the axis and its
 *  value before and after the edit. */
data class RuleScheduleChange(val label: String, val before: String, val after: String)

private fun RuleSchedule.blockingText(res: Resources): String {
    val start = windowStartMinute
    val end = windowEndMinute
    if (!alwaysOn && start != null && end != null) return windowLabel(start, end, res)
    return res.getString(if (alwaysOn) R.string.schedule_always_on else R.string.partner_rule_no_hours)
}

private fun RuleSchedule.limitText(res: Resources): String =
    if (dailyLimitMs > 0) formatDuration(dailyLimitMs) else res.getString(R.string.schedule_off)

private fun RuleSchedule.endsText(res: Resources): String =
    if (expiresAtMs > 0 && expiresAtMs < FAR_FUTURE_EPOCH_MILLIS) {
        formatExpiry(expiresAtMs)
    } else {
        res.getString(R.string.schedule_never)
    }

/** The partner's after-snapshot of a rule (OPD #48): how it blocks, day scope, daily cap and
 *  end — the composition the owner's own rule rows use, without the partner field. */
fun RuleSchedule.summaryText(res: Resources): String {
    val parts = mutableListOf<String>()
    if (alwaysOn) parts += res.getString(R.string.schedule_always_on)
    val start = windowStartMinute
    val end = windowEndMinute
    if (start != null && end != null) parts += windowLabel(start, end, res)
    if (dailyLimitMs > 0 && !alwaysOn) {
        parts += res.getString(R.string.schedule_daily_limit, formatDuration(dailyLimitMs))
    }
    if (days != EVERY_DAY) daysLabel(days, res)?.takeIf(String::isNotEmpty)?.let { parts += it }
    if (expiresAtMs > 0 && expiresAtMs < FAR_FUTURE_EPOCH_MILLIS) {
        parts += res.getString(R.string.schedule_until, formatExpiry(expiresAtMs))
    }
    return if (parts.isEmpty()) res.getString(R.string.schedule_none) else parts.joinToString(" · ")
}

/** Per-axis before→after of an edited rule, in reading order; empty when no schedule field
 *  changed (the caller then shows the after-snapshot instead). */
fun RuleSchedule.changesFrom(before: RuleSchedule, res: Resources): List<RuleScheduleChange> =
    changedAxesFrom(before).map { axis ->
        when (axis) {
            RuleSchedule.Axis.BLOCKING -> RuleScheduleChange(
                res.getString(R.string.partner_rule_axis_block),
                before.blockingText(res),
                blockingText(res),
            )
            RuleSchedule.Axis.DAYS -> RuleScheduleChange(
                res.getString(R.string.partner_rule_axis_days),
                daysLabel(before.days, res)?.takeIf(String::isNotEmpty) ?: before.days,
                daysLabel(days, res)?.takeIf(String::isNotEmpty) ?: days,
            )
            RuleSchedule.Axis.LIMIT -> RuleScheduleChange(
                res.getString(R.string.partner_rule_axis_limit),
                before.limitText(res),
                limitText(res),
            )
            RuleSchedule.Axis.ENDS -> RuleScheduleChange(
                res.getString(R.string.partner_rule_axis_ends),
                before.endsText(res),
                endsText(res),
            )
        }
    }

fun formatTime(minutesOfDay: Long): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        .format(LocalTime.ofSecondOfDay(minutesOfDay * 60))

/** Expiry is an end-of-day instant (RuleEditor.toLocalEndOfDayMillis); showing the date alone
 *  read as "ends when that date begins" (owner report 2026-11-24). */
fun formatExpiry(epochMillis: Long): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

/** Day + month for dates the user just lived; a date from another year keeps its year so an
 *  old "Free since" never reads as this year's. */
fun formatDayMonth(epochMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
    val zone = ZoneId.systemDefault()
    val then = Instant.ofEpochMilli(epochMillis).atZone(zone)
    val pattern = if (then.year == Instant.ofEpochMilli(nowMillis).atZone(zone).year) "MMM d" else "MMM d, yyyy"
    return DateTimeFormatter.ofPattern(pattern, Locale.getDefault()).format(then)
}

/** Weekday + day + month, no year — the Home header eyebrow ("Tuesday, 22 September"). The
 *  year is dead weight on a screen whose subject is the current streak, and no localized
 *  FormatStyle drops it, so the pattern is explicit (names still come from the locale). */
fun formatHeaderDate(epochMillis: Long): String =
    DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault())
        .format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate())

/** "3h ago" / "Sep 19" — the partner surfaces' shared age line (dashboard summary, Home's
 *  pinned-message card). Older than a week falls back to the date, because "12d ago" stops
 *  meaning anything a reader can place. */
fun relativeTimeText(nowMillis: Long, thenMillis: Long?): String =
    when (val rel = relativeTime(nowMillis, thenMillis ?: nowMillis)) {
        RelativeTime.JustNow -> "just now"
        is RelativeTime.Minutes -> "${rel.value}m ago"
        is RelativeTime.Hours -> "${rel.value}h ago"
        is RelativeTime.Days -> "${rel.value}d ago"
        is RelativeTime.Older -> DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
            .format(Instant.ofEpochMilli(rel.epochMillis).atZone(ZoneId.systemDefault()).toLocalDate())
    }

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
