package com.example.myexodus.recovery

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Pure evaluation of the user's willpower windows (V1 Req 19, FR-7). No Android, no clock, no
 * cache: callers pass the wall clock reading and re-evaluate on every use (v2 M1.4), so a
 * travel/zone change is always reflected and there is no skew to detect.
 *
 * Semantics, exactly the v2 contract:
 * - Window range is `[start, end)` in minutes-of-day.
 * - Overnight windows (`start > end`) wrap midnight: inside iff `t >= start || t < end`.
 * - The day mask (bit0 = Monday … bit6 = Sunday) applies to the day the window *starts* —
 *   the after-midnight part of an overnight window belongs to the previous day's bit.
 */
object RiskWindowEvaluator {

    fun isActive(now: LocalDateTime, window: RiskWindowSpec): Boolean {
        if (!window.enabled) return false
        val t = now.hour * 60 + now.minute
        return if (window.startMinute <= window.endMinute) {
            t >= window.startMinute && t < window.endMinute &&
                maskCovers(window.daysMask, now.toLocalDate())
        } else {
            when {
                t >= window.startMinute -> maskCovers(window.daysMask, now.toLocalDate())
                t < window.endMinute ->
                    maskCovers(window.daysMask, now.toLocalDate().minusDays(1))
                else -> false
            }
        }
    }

    /** The first active window, or null. Windows are few (≤3); linear scan is the whole story. */
    fun activeWindow(now: LocalDateTime, windows: List<RiskWindowSpec>): RiskWindowSpec? =
        windows.firstOrNull { isActive(now, it) }

    /** Minutes until the window's end from [now] (for "until 6:00" copy); 0 when not active. */
    fun minutesUntilEnd(now: LocalDateTime, window: RiskWindowSpec): Int {
        if (!isActive(now, window)) return 0
        val t = now.hour * 60 + now.minute
        val minutes = (window.endMinute - t + MINUTES_PER_DAY) % MINUTES_PER_DAY
        // A window that ends exactly at the same minute it started is a full-day window.
        return if (minutes == 0) MINUTES_PER_DAY else minutes
    }

    private fun maskCovers(daysMask: Int, date: LocalDate): Boolean =
        daysMask and (1 shl (date.dayOfWeek.value - 1)) != 0

    private const val MINUTES_PER_DAY = 24 * 60
}

/** Room-free view of a `risk_window` row (the entity maps to this one-for-one). */
data class RiskWindowSpec(
    val id: Long,
    val startMinute: Int,
    val endMinute: Int,
    val daysMask: Int,
    val enabled: Boolean = true,
) {
    val isOvernight: Boolean get() = startMinute > endMinute

    fun startLabel(): String = formatMinutes(startMinute)
    fun endLabel(): String = formatMinutes(endMinute)

    companion object {
        const val ALL_DAYS_MASK = 0b1111111

        fun formatMinutes(minutesOfDay: Int): String =
            "%02d:%02d".format((minutesOfDay / 60) % 24, minutesOfDay % 60)
    }
}
