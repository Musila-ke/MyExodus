package com.example.myexodus.domainusage

import androidx.room3.ColumnInfo
import androidx.room3.Entity

/**
 * §4.9 / S-4: the only storage this feature adds — one row per `(rule_id, revision, day_key)`.
 * Holds no hostnames, IPs, DNS names or timestamps other than `day_key` (NFR-4, S-4), and no
 * foreign keys: a rule row can be deleted while its usage history is still inside the retention
 * window, and this table's rows are read by `(rule_id, revision)` only.
 */
@Entity(tableName = "rule_usage_daily", primaryKeys = ["rule_id", "revision", "day_key"])
data class RuleUsageDaily(
    @ColumnInfo(name = "rule_id") val ruleId: String,
    @ColumnInfo(name = "revision") val revision: Long,
    @ColumnInfo(name = "day_key") val dayKey: String,
    @ColumnInfo(name = "active_ms") val activeMs: Long,
)
