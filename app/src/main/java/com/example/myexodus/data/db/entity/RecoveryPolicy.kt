package com.example.myexodus.data.db.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * Global recovery/escape-hatch policy (blocking-core plan §4 Policy Domain). Single row
 * (id = 0). Consumed by the recovery state machine (§8.3) when Slice 2 lands: the friction
 * timer the user waits out, and the minimum gap between consecutive overrides. Seeded with
 * defaults on create/migration; the V1 settings UI will edit it.
 */
@Entity(tableName = "recovery_policy")
data class RecoveryPolicy(
    @PrimaryKey val id: Int = 0,
    @ColumnInfo(name = "default_friction_seconds") val defaultFrictionSeconds: Long,
    @ColumnInfo(name = "override_cooldown_seconds") val overrideCooldownSeconds: Long,
)
