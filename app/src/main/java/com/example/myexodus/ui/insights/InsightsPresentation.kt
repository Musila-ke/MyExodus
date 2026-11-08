package com.example.myexodus.ui.insights

import com.example.myexodus.insights.InsightDay
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Short weekday for a bar's footer ("Mon"), locale-driven and test-pinned. */
fun shortDayLabel(date: LocalDate, locale: Locale = Locale.getDefault()): String =
    date.format(DateTimeFormatter.ofPattern("EEE", locale))

/** Heading for a tapped day in the detail sheet ("Mon, Oct 20"), locale-driven. */
fun detailDayLabel(date: LocalDate, locale: Locale = Locale.getDefault()): String =
    date.format(DateTimeFormatter.ofPattern("EEE, MMM d", locale))

/**
 * Compact duration for a bar label, where seven columns share the width: "2h05", "45m", "0m".
 * The long form (`formatDuration`) stays on totals and rows. Minutes are floored — a bar never
 * claims time the ledger did not credit.
 */
fun compactDuration(ms: Long): String {
    val minutes = (ms / 60_000).coerceAtLeast(0)
    return if (minutes >= 60) {
        "${minutes / 60}h${(minutes % 60).toString().padStart(2, '0')}"
    } else {
        "${minutes}m"
    }
}

/** Bar height as a fraction of the strip's tallest value; 0 when nothing has a value. */
fun shareOf(value: Long, max: Long): Float =
    if (max <= 0L) 0f else (value.toDouble() / max.toDouble()).toFloat().coerceIn(0f, 1f)

/** Tallest attempts day in the window — the strip's shared scale. */
fun maxAttempts(days: List<InsightDay>): Int =
    days.maxOfOrNull { it.blocked + it.overrides } ?: 0

/** Tallest usage day in the window — the strip's shared scale. */
fun maxCreditedMs(days: List<InsightDay>): Long =
    days.maxOfOrNull { it.creditedMs } ?: 0L
