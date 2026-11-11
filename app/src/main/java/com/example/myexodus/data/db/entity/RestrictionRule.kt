package com.example.myexodus.data.db.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * CHECK constraints (Rule 4 self-block, severity values) are added by SchemaConstraints,
 * which rebuilds this table with the named constraints — Room cannot express CHECK clauses.
 */
@Entity(
    tableName = "restriction_rules",
    foreignKeys = [
        ForeignKey(
            entity = UserProfile::class,
            parentColumns = ["user_id"],
            childColumns = ["profile_id"],
        ),
    ],
    indices = [
        Index(value = ["profile_id", "blocked_package"], unique = true),
    ],
)
data class RestrictionRule(
    @PrimaryKey @ColumnInfo(name = "rule_id") val ruleId: String,
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "blocked_package") val blockedPackage: String,
    @ColumnInfo(name = "target_type") val targetType: String,
    @ColumnInfo(name = "is_always_on") val isAlwaysOn: Boolean,
    @ColumnInfo(name = "daily_limit_ms") val dailyLimitMs: Long,
    @ColumnInfo(name = "time_range_start") val timeRangeStart: Long?,
    @ColumnInfo(name = "time_range_end") val timeRangeEnd: Long?,
    @ColumnInfo(name = "active_days_of_week") val activeDaysOfWeek: String,
    @ColumnInfo(name = "timezone", defaultValue = "UTC") val timezone: String = "UTC",
    @ColumnInfo(name = "block_expires_at") val blockExpiresAt: Long,
    @ColumnInfo(name = "severity_level") val severityLevel: String,
    /** Pause switch (Rules screen): a disabled rule stays stored but leaves the snapshot. */
    @ColumnInfo(name = "is_enabled", defaultValue = "1") val isEnabled: Boolean = true,
    /** Partner roles (plan §2.1 req 1): local pair ids, independent, valid only while paired. */
    @ColumnInfo(name = "assigned_pair_id") val assignedPairId: String? = null,
    @ColumnInfo(name = "backup_pair_id") val backupPairId: String? = null,
    @ColumnInfo(name = "streak_pair_id") val streakPairId: String? = null,
    /** Owner mandate 2026-10-31: rules created together live in one named batch. Membership
     *  rides on each rule (denormalized name) so the partner-facing identity needs no join. */
    @ColumnInfo(name = "batch_id") val batchId: String? = null,
    @ColumnInfo(name = "batch_name") val batchName: String? = null,
)

/**
 * Rule 3 (absolute expiration) — `blockExpiresAt > 0` and past means the rule is enforced by
 * nothing: the decision engine allows, the snapshot drops it, and daily-limit tracking must
 * not accrue, warn, or wall for it (device-caught 2026-09-16: an override-expired rule kept
 * counting usage and warned "close to daily limit").
 */
fun RestrictionRule.isExpiredAt(nowEpochMillis: Long): Boolean =
    blockExpiresAt > 0 && nowEpochMillis > blockExpiresAt

/** "The tunnel has something to do" — any website rule the publisher would publish (enabled,
 *  unexpired), limit or block-only. Shared by the Home/Rules readiness surfaces and the
 *  monitoring snapshot's state: a missing VPN consent only degrades protection while one of
 *  these exists. */
fun hasLiveWebsiteRule(rules: List<RestrictionRule>, nowEpochMillis: Long): Boolean =
    rules.any {
        it.targetType == "WEBSITE" && it.isEnabled && !it.isExpiredAt(nowEpochMillis)
    }
