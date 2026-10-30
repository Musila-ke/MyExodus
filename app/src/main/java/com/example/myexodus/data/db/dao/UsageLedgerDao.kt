package com.example.myexodus.data.db.dao

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.example.myexodus.data.db.entity.UsageLedger

/** One package-day total rebuilt purely from the journal (§8.13). */
data class LedgerDayTotal(
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "day_key") val dayKey: String,
    @ColumnInfo(name = "total_ms") val totalMs: Long,
)

/** The credit-edge audit's per-package anchor (ADR #160): the wall of the newest
 *  interval-owning credit, so everything after it is by definition uncredited. */
data class LedgerCreditEdge(
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "credit_wall_ms") val creditWallMs: Long,
)

/**
 * Reads and appends to the usage journal. Append-only by construction: there is no `@Update`
 * and no `@Delete` here, and the only deletion is the age-bounded retention sweep (§8.8).
 * Every read that feeds a decision goes through [UsageLedgerRepository], not this DAO directly.
 */
@Dao
interface UsageLedgerDao {
    /** [OnConflictStrategy.IGNORE] is the idempotency mechanism: a replayed event collides on
     *  the unique `event_id` index and returns -1 without touching the existing row. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: UsageLedger): Long

    @Query("SELECT * FROM usage_ledger WHERE event_id = :eventId LIMIT 1")
    suspend fun findByEventId(eventId: String): UsageLedger?

    /** ADR #240: the retained scope's journal, read from its recovery copy (age-bounded). */
    @Query("SELECT * FROM usage_ledger")
    suspend fun all(): List<UsageLedger>

    /** The gap detector's temporal baseline (§8.4 / AD-15): the latest row *this source* wrote.
     *  Read from the journal so the baseline can never disagree with it. */
    @Query("SELECT * FROM usage_ledger WHERE source = :source ORDER BY id DESC LIMIT 1")
    suspend fun latestForSource(source: String): UsageLedger?

    /** §8.13 aggregation for one scope. [kinds] is always the resolving set
     *  ([com.example.myexodus.data.db.entity.UsageLedgerKind.CREDITING]). */
    @Query(
        "SELECT COALESCE(SUM(credited_ms), 0) FROM usage_ledger " +
            "WHERE package_name = :packageName AND day_key = :dayKey AND kind IN (:kinds)",
    )
    suspend fun sumCredited(packageName: String, dayKey: String, kinds: Collection<Int>): Long

    /** Every package-day total in the journal — the projection rebuild's input (§8.3). */
    @Query(
        "SELECT package_name AS package_name, day_key AS day_key, " +
            "SUM(credited_ms) AS total_ms FROM usage_ledger " +
            "WHERE kind IN (:kinds) GROUP BY package_name, day_key",
    )
    suspend fun dayTotals(kinds: Collection<Int>): List<LedgerDayTotal>

    /** Per-package day totals from [fromDayKey] onward. ISO day keys order lexicographically,
     *  so this is the Insights window's bounded read — never the whole 90-day journal. */
    @Query(
        "SELECT package_name AS package_name, day_key AS day_key, " +
            "SUM(credited_ms) AS total_ms FROM usage_ledger " +
            "WHERE kind IN (:kinds) AND day_key >= :fromDayKey " +
            "GROUP BY package_name, day_key",
    )
    suspend fun dayTotalsSince(
        kinds: Collection<Int>,
        fromDayKey: String,
    ): List<LedgerDayTotal>

    /** The credit-edge audit's anchor query (ADR #160, amended by ADR #191): the newest wall
     *  per package among the credit-answering kinds (ACCOUNTING, RECONCILIATION,
     *  MIGRATION_CARRYOVER), bounded by a wall cutoff so the audit can never re-open a span
     *  older than the day it may credit. */
    @Query(
        "SELECT package_name AS package_name, MAX(wall_clock_ts_ms) AS credit_wall_ms " +
            "FROM usage_ledger WHERE package_name IN (:packageNames) AND kind IN (:kinds) " +
            "AND wall_clock_ts_ms >= :sinceWallClockMs GROUP BY package_name",
    )
    suspend fun creditEdges(
        packageNames: Collection<String>,
        kinds: Collection<Int>,
        sinceWallClockMs: Long,
    ): List<LedgerCreditEdge>

    /** Intervals already journaled for the package, for §8.9's overlap check. Bounded by the
     *  reconciliation window's own elapsed span, so the result set stays small. `boot_count`
     *  scopes the comparison to one monotonic frame (§8.5): elapsed values from another boot
     *  are not comparable, and equal numbers are a coincidence, not coverage. */
    @Query(
        "SELECT * FROM usage_ledger WHERE package_name = :packageName AND boot_count = :bootCount " +
            "AND kind IN (:kinds) AND elapsed_end_ms > :fromElapsedMs AND elapsed_start_ms < :toElapsedMs " +
            "ORDER BY elapsed_start_ms",
    )
    suspend fun intervalsOverlapping(
        packageName: String,
        kinds: Collection<Int>,
        fromElapsedMs: Long,
        toElapsedMs: Long,
        bootCount: Int,
    ): List<UsageLedger>

    /** The day audit's row listing (debug seam): every journal row for one package-day, in
     *  write order — the per-row evidence a tracker-vs-platform investigation reads. */
    @Query(
        "SELECT * FROM usage_ledger WHERE package_name = :packageName AND day_key = :dayKey " +
            "ORDER BY id",
    )
    suspend fun rowsForPackageDay(packageName: String, dayKey: String): List<UsageLedger>

    /** Observation-outage evidence (§8.7): the gap rows since a wall-clock cutoff. */
    @Query(
        "SELECT * FROM usage_ledger WHERE kind = :kind AND wall_clock_ts_ms >= :sinceWallClockMs " +
            "ORDER BY wall_clock_ts_ms DESC",
    )
    suspend fun rowsOfKindSince(kind: Int, sinceWallClockMs: Long): List<UsageLedger>

    /** Retention (§8.8): how much is still eligible, so the sweep knows whether to re-run. */
    @Query("SELECT COUNT(*) FROM usage_ledger WHERE wall_clock_ts_ms < :cutoffWallClockMs")
    suspend fun countOlderThan(cutoffWallClockMs: Long): Int

    /** One bounded retention batch. Returns the rows deleted, so a caller can loop until it
     *  gets a short batch without ever holding a write transaction over the whole backlog. */
    @Query(
        "DELETE FROM usage_ledger WHERE id IN (" +
            "SELECT id FROM usage_ledger WHERE wall_clock_ts_ms < :cutoffWallClockMs " +
            "ORDER BY id LIMIT :batchSize)",
    )
    suspend fun deleteOldestBatch(cutoffWallClockMs: Long, batchSize: Int): Int
}
