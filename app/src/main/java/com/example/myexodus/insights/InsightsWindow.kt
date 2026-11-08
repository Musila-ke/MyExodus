package com.example.myexodus.insights

import com.example.myexodus.data.db.dao.AttemptStamp
import com.example.myexodus.data.db.entity.AttemptLog
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One target's attempts inside a day, most-hit first. */
data class TargetAttempts(val target: String, val count: Int)

/** One local day's attempt counts and per-target detail for the Insights strip. */
data class DayAttempts(
    val blocked: Int,
    val overrides: Int,
    val blockedTargets: List<TargetAttempts> = emptyList(),
    val overrideTargets: List<TargetAttempts> = emptyList(),
)

/**
 * The Insights report window: today plus the six preceding local days. Attempts and sessions
 * carry wall-clock timestamps, so their day membership is computed in the device's current
 * zone at read time (a timezone move re-buckets history, the same way a reader would re-read
 * the dates on a calendar). Usage never uses this — the ledger's `day_key` is frozen at write
 * time and is the accounting authority (ADR #181), so the screen reads those keys as stored.
 */
object InsightsWindow {
    const val DAYS = 7

    /** Oldest → newest, ending with today in [zone]. */
    fun days(nowMs: Long, zone: ZoneId): List<LocalDate> {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        return (DAYS - 1 downTo 0).map { today.minusDays(it.toLong()) }
    }

    /** First instant of the window's oldest day — the inclusive query bound. */
    fun startMs(nowMs: Long, zone: ZoneId): Long =
        days(nowMs, zone).first().atStartOfDay(zone).toInstant().toEpochMilli()

    /**
     * Buckets stamps into [days] in [zone]. Stamps outside the window are dropped rather than
     * clamped: a row exactly at the bound can belong to the previous day in a zone whose offset
     * moved, and counting it there would invent a seventh day's neighbour.
     */
    fun bucketAttempts(
        stamps: List<AttemptStamp>,
        days: List<LocalDate>,
        zone: ZoneId,
    ): Map<LocalDate, DayAttempts> {
        val wanted = days.toSet()
        val blocked = HashMap<LocalDate, MutableMap<String, Int>>()
        val overrides = HashMap<LocalDate, MutableMap<String, Int>>()
        for (stamp in stamps) {
            val day = Instant.ofEpochMilli(stamp.occurredAtEpochMillis).atZone(zone).toLocalDate()
            if (day !in wanted) continue
            val perTarget = when (stamp.outcome) {
                AttemptLog.OUTCOME_BLOCKED -> blocked
                AttemptLog.OUTCOME_OVERRIDE -> overrides
                else -> continue
            }
            val dayTargets = perTarget.getOrPut(day) { HashMap() }
            dayTargets[stamp.target] = (dayTargets[stamp.target] ?: 0) + 1
        }
        return (blocked.keys + overrides.keys).associateWith { day ->
            val blockedDay = blocked[day].orEmpty()
            val overrideDay = overrides[day].orEmpty()
            DayAttempts(
                blocked = blockedDay.values.sum(),
                overrides = overrideDay.values.sum(),
                blockedTargets = ranked(blockedDay),
                overrideTargets = ranked(overrideDay),
            )
        }
    }

    private fun ranked(counts: Map<String, Int>): List<TargetAttempts> = counts.entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .map { TargetAttempts(it.key, it.value) }
}
