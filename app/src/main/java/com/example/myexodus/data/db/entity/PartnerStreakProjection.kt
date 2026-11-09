package com.example.myexodus.data.db.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity

/**
 * The received peer's partner-visible state for one shared streak (schema v27, owner directive
 * 2026-11-09): one row per (pair, habit), keyed by the sender's stable habit id — labels can
 * repeat, ids cannot, so renaming a streak re-labels the same row instead of forking a second.
 * Applied wholesale with the same monotonic-sequence guard as [PartnerActivityProjection]; a
 * STOPPED or DELETED event deletes the row (the streak left the partner's view), while every
 * other habit-anchored kind upserts label + numbers.
 */
@Entity(tableName = "partner_streak_projection", primaryKeys = ["pair_id", "habit_id"])
data class PartnerStreakProjection(
    @ColumnInfo(name = "pair_id") val pairId: String,
    @ColumnInfo(name = "habit_id") val habitId: String,
    @ColumnInfo(name = "habit_label") val habitLabel: String,
    @ColumnInfo(name = "days_free") val daysFree: Int,
    @ColumnInfo(name = "relapse_count") val relapseCount: Int,
    /** [encodeMilestoneDays] form — the sender's achieved milestone days of this streak. */
    @ColumnInfo(name = "milestones_reached") val milestonesReached: String,
    /** The peer's wall-clock of the last applied transition for this streak. */
    @ColumnInfo(name = "last_event_occurred_at_ms") val lastEventOccurredAtMs: Long,
    /** Highest applied peer-stream sequence for this streak; 0 = nothing applied. */
    @ColumnInfo(name = "last_sequence") val lastSequence: Long,
)
