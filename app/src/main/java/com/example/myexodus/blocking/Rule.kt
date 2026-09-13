package com.example.myexodus.blocking

/**
 * Runtime decision model for one app restriction (Enhanced Mode spec §8 Step 2). Immutable;
 * built from [com.example.myexodus.data.db.entity.RestrictionRule] when a snapshot is
 * published. Carries the V1 decision inputs (plan §8 Step 4 reads the snapshot via O(1)
 * map lookup): absolute expiry (Rule 3), time window + active days (Rule 1), daily quota
 * (Rule 2), severity for the overlay messaging.
 */
data class Rule(
    val ruleId: String,
    val packageName: String,
    val isAlwaysOn: Boolean,
    val dailyLimitMs: Long,
    val timeRangeStart: Long?,
    val timeRangeEnd: Long?,
    val activeDaysOfWeek: String,
    val blockExpiresAt: Long,
    val severityLevel: String,
    val timezone: String = "UTC",
)
