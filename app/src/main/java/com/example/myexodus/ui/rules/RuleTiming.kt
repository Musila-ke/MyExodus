package com.example.myexodus.ui.rules

import com.example.myexodus.blocking.dayActiveOn
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** One full week plus a day, from yesterday: every weekday is covered even when the window
 *  already started yesterday (overnight leg) and the active day is the furthest one out. */
private const val SCAN_DAYS = 7L

/**
 * Earliest instant at or after [nowEpochMillis] when [fields] can block, or null when they
 * never can. Whole-day mechanisms (always-on and the daily limit) open on every active day;
 * a window opens at its start time on an active start day, its post-midnight leg included.
 * The scan starts at yesterday so an overnight window already running counts as "now".
 */
internal fun firstBlockOpportunityMillis(
    fields: RuleFields,
    timezone: String,
    nowEpochMillis: Long,
): Long? {
    val zone = runCatching { ZoneId.of(timezone) }.getOrDefault(ZoneId.systemDefault())
    val today = Instant.ofEpochMilli(nowEpochMillis).atZone(zone).toLocalDate()
    val wholeDay = fields.isAlwaysOn || fields.dailyLimitMs > 0L
    for (offset in -1L..SCAN_DAYS) {
        val date = today.plusDays(offset)
        if (!dayActiveOn(fields.activeDaysOfWeek, date.dayOfWeek)) continue
        val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val start: Long
        val end: Long
        if (wholeDay) {
            start = dayStart
            end = dayEnd
        } else {
            val startMinutes = fields.timeRangeStart ?: continue
            val endMinutes = fields.timeRangeEnd ?: continue
            start = atMinuteOfDay(date, startMinutes, zone)
            end = if (endMinutes > startMinutes) {
                atMinuteOfDay(date, endMinutes, zone)
            } else {
                atMinuteOfDay(date.plusDays(1), endMinutes, zone)
            }
        }
        val opportunity = maxOf(nowEpochMillis, start)
        if (opportunity < end) return opportunity
    }
    return null
}

/**
 * True when an end date makes [fields] inert: no active day or window can start before the
 * rule stops applying, so saving it would only promise a block that never comes. Rules that
 * never end (0) are never rejected here.
 */
internal fun expiresBeforeFirstBlock(
    fields: RuleFields,
    timezone: String,
    expiresAt: Long,
    nowEpochMillis: Long,
): Boolean {
    if (expiresAt <= 0L) return false
    val first = firstBlockOpportunityMillis(fields, timezone, nowEpochMillis) ?: return true
    return first > expiresAt
}

/** Local wall-clock minute in [zone]; [LocalTime] wraps out-of-range values instead of throwing. */
private fun atMinuteOfDay(date: LocalDate, minutes: Long, zone: ZoneId): Long =
    date.atTime(LocalTime.MIN.plusMinutes(minutes)).atZone(zone).toInstant().toEpochMilli()
