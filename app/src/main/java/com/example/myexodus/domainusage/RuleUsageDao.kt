package com.example.myexodus.domainusage

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction

/** D-32 × §6.8: the highest revision ever persisted for a rule id, so a restart cannot rewind it. */
data class RuleRevision(val ruleId: String, val revision: Long)

/**
 * §8 S7.1: the `rule_usage_daily` reader/writer. Every call runs on `domainusage-db` (§4.3,
 * INV-1) — the tick and relay threads only ever hand over an already-taken [UsageSnapshot] list.
 */
@Dao
abstract class RuleUsageDao {

    /** §6.8's startup load: today's rows for every `(rule_id, revision)` that has one. */
    @Query("SELECT * FROM rule_usage_daily WHERE day_key = :dayKey")
    abstract suspend fun loadDay(dayKey: String): List<RuleUsageDaily>

    /** ADR #240: the retained scope's per-rule history, read from its recovery copy. */
    @Query("SELECT * FROM rule_usage_daily")
    abstract suspend fun all(): List<RuleUsageDaily>

    /** ADR #240: a same-device recovery replays retained rows under INV-5's never-lower rule. */
    @Transaction
    open suspend fun restoreMax(rows: List<RuleUsageDaily>) {
        for (row in rows) upsertMaxRow(row.ruleId, row.revision, row.dayKey, row.activeMs)
    }

    /** D-32 × §6.8: across every retained day — the last edit may have happened yesterday. */
    @Query("SELECT rule_id AS ruleId, MAX(revision) AS revision FROM rule_usage_daily GROUP BY rule_id")
    abstract suspend fun maxRevisions(): List<RuleRevision>

    /** §6.8/§6.7: rows outside the retention window; both runs pass their own cutoff. */
    @Query("DELETE FROM rule_usage_daily WHERE day_key < :cutoffDayKey")
    abstract suspend fun deleteOlderThan(cutoffDayKey: String)

    /**
     * One statement per row (the composite primary key is the conflict target), where the
     * `MAX` in `DO UPDATE` keeps INV-5's never-lower semantics. A separate INSERT + UPDATE pair
     * doubles the DAO round-trips for the same page writes — PF-7 measured 327 ms p50 / 573 ms
     * p99 for 200 rows that way, and the statement half of that was pure overhead.
     */
    @Query(
        "INSERT INTO rule_usage_daily (rule_id, revision, day_key, active_ms) " +
            "VALUES (:ruleId, :revision, :dayKey, :activeMs) " +
            "ON CONFLICT(rule_id, revision, day_key) " +
            "DO UPDATE SET active_ms = MAX(active_ms, excluded.active_ms)",
    )
    protected abstract suspend fun upsertMaxRow(ruleId: String, revision: Long, dayKey: String, activeMs: Long)

    /**
     * §6.6/INV-5: absolute totals written as a generation-guarded `MAX` — one transaction per
     * cycle, idempotent under retry and crash, and never lowering a stored total.
     */
    @Transaction
    open suspend fun upsertMax(rows: List<UsageSnapshot>) {
        for (row in rows) upsertMaxRow(row.ruleId, row.revision, row.dayKey, row.totalMs)
    }
}
