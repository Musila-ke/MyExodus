package com.example.myexodus.data.db.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * The durable accounting journal (usage-ledger plan §8.1, R1): one append-only row per
 * accounting event. The daily-limit counter is a single scalar that Decision 20 overwrites in
 * place at the first write after local midnight, so a bad day's evidence was destroyed before
 * anyone could look at it — this table is what makes a day's usage a sum you can reconstruct
 * and audit instead of a number you have to trust.
 *
 * Rows are never mutated after insert (R7): a correction is a new [UsageLedgerKind.CORRECTION]
 * row that references the row it corrects, never an update. Aggregation over this table (§8.13)
 * is the authoritative definition of "usage today"; the `daily_usage_tracker` scalar is a
 * projection of it, updated in the same transaction as every credential-bearing insert.
 *
 * Local only, never transmitted (enforcement plan v2 Decision 12): no package name, timestamp
 * or duration ever leaves the device through this table.
 */
@Entity(
    tableName = "usage_ledger",
    indices = [
        Index(value = ["event_id"], unique = true),
        Index(value = ["package_name", "day_key", "kind", "credited_ms"]),
        Index(value = ["wall_clock_ts_ms"]),
    ],
)
data class UsageLedger(
    /** Room's autoGenerate emits `INTEGER PRIMARY KEY AUTOINCREMENT`, strictly stronger than the
     *  plain rowid the plan's illustrative DDL shows: an audit journal must never hand a new row
     *  a previously-used id. */
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    /** Deterministic dedup key (§8.6) — [eventIdFor] over the event's identity fields. */
    @ColumnInfo(name = "event_id") val eventId: String,
    @ColumnInfo(name = "kind") val kind: Int,
    @ColumnInfo(name = "wall_clock_ts_ms") val wallClockTsMs: Long,
    @ColumnInfo(name = "elapsed_start_ms") val elapsedStartMs: Long,
    @ColumnInfo(name = "elapsed_end_ms") val elapsedEndMs: Long,
    @ColumnInfo(name = "boot_count") val bootCount: Int,
    @ColumnInfo(name = "package_name") val packageName: String,
    /** Local-date string (e.g. `2026-09-27`), computed once at write time and never recomputed
     *  from [wallClockTsMs] — a timezone move must not silently restate history. */
    @ColumnInfo(name = "day_key") val dayKey: String,
    @ColumnInfo(name = "credited_ms") val creditedMs: Long,
    /** [UsageLedgerSource] — which detection path observed the event. */
    @ColumnInfo(name = "source") val source: String,
    /** The producing app version, so rows stay attributable across accounting changes. */
    @ColumnInfo(name = "source_version") val sourceVersion: String,
    /** Only a CORRECTION row points at another row's `event_id`; null for every other kind. */
    @ColumnInfo(name = "references_event_id") val referencesEventId: String? = null,
)

/** §8.1 kind vocabulary. Only [ACCOUNTING], [MIGRATION_CARRYOVER], [RECONCILIATION] and
 *  [CORRECTION] carry usage weight (§8.13); the rest are evidence about observation itself. */
object UsageLedgerKind {
    const val ACCOUNTING = 1
    const val HEARTBEAT = 2
    const val RULE_EVALUATION = 3
    const val GAP_DETECTED = 4
    const val MIGRATION_CARRYOVER = 5
    const val RECONCILIATION = 6
    const val CORRECTION = 7

    /** §8.13 — the kinds a day's total is the sum of. */
    val CREDITING = setOf(ACCOUNTING, MIGRATION_CARRYOVER, RECONCILIATION, CORRECTION)

    fun nameOf(kind: Int): String = when (kind) {
        ACCOUNTING -> "ACCOUNTING"
        HEARTBEAT -> "HEARTBEAT"
        RULE_EVALUATION -> "RULE_EVALUATION"
        GAP_DETECTED -> "GAP_DETECTED"
        MIGRATION_CARRYOVER -> "MIGRATION_CARRYOVER"
        RECONCILIATION -> "RECONCILIATION"
        CORRECTION -> "CORRECTION"
        else -> "UNKNOWN($kind)"
    }
}

/** Which path observed the event. MyExodus has one authority at a time (enforcement plan v2
 *  Decision 2); the plan's single-source assumption maps onto that arbiter, and this column is
 *  what records which holder was in force — the first thing a "why is the number wrong"
 *  investigation needs. */
object UsageLedgerSource {
    const val PATH_A = "PATH_A"
    const val PATH_B = "PATH_B"
    /** Migration-time seeding, and the maintenance sweeps that run without a foreground holder. */
    const val MAINTENANCE = "MAINTENANCE"
}
