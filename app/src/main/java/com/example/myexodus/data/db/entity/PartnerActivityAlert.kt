package com.example.myexodus.data.db.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index

/**
 * Recipient-local record of a shared relapse or milestone delivered on the staged transport
 * (rig finding 2026-11-15: a delivered milestone reached the tray and the streak state but
 * never "Recent activity" or the Shared-events chart). Staged deliveries write no chain rows,
 * so the dashboard's legacy chain source — the only place `alertOf` renders relapse/milestone
 * — never sees them. One row per delivered event; detail is retained 30 days, matching the
 * journal.
 */
@Entity(
    tableName = "partner_activity_alert",
    primaryKeys = ["pair_id", "event_id"],
    indices = [Index(value = ["occurred_at_ms"])],
)
data class PartnerActivityAlert(
    @ColumnInfo(name = "pair_id") val pairId: String,
    @ColumnInfo(name = "event_id") val eventId: String,
    /** The wire activity type: `relapse_recorded` or `milestone_reached`. */
    @ColumnInfo(name = "kind") val kind: String,
    /** The streak's name on the sender's side; null on pre-v2 bodies. */
    @ColumnInfo(name = "habit_label") val habitLabel: String?,
    /** Relapse rows: the ordinal day-count of the streak it ended. */
    @ColumnInfo(name = "ended_days") val endedDays: Int?,
    /** Milestone rows: the day reached. */
    @ColumnInfo(name = "milestone_day") val milestoneDay: Int?,
    /** The sender's own occurrence time — a backdated relapse reads as when it happened. */
    @ColumnInfo(name = "occurred_at_ms") val occurredAtMs: Long,
    @ColumnInfo(name = "expires_at_ms") val expiresAtMs: Long,
)
