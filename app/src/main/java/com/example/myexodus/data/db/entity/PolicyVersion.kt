package com.example.myexodus.data.db.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * Room-backed policy version (blocking-core plan §4: "PolicySnapshot(version) — versioned,
 * observed via Flow"; §6: rule writes bump PolicyVersion in the same transaction). Single
 * row (id = 0) incremented on every rule write; the sole authority for snapshot versioning —
 * engines cache it, Room owns it.
 */
@Entity(tableName = "policy_version")
data class PolicyVersion(
    @PrimaryKey val id: Int = 0,
    @ColumnInfo(name = "version") val version: Long,
)
