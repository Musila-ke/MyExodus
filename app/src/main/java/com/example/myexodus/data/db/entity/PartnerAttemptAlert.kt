package com.example.myexodus.data.db.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index

/**
 * Recipient-local record of a staged blocked/override attempt (owner battery 2026-11-12: a
 * staged `BLOCK_ATTEMPT` notified nothing and left no Rules & blocks row). Staged deliveries
 * write no chain rows, so the legacy attempt source never sees them; one row per delivered
 * event keeps the notification and its history row together. Detail is retained 30 days,
 * matching the journal's retention.
 */
@Entity(
    tableName = "partner_attempt_alert",
    primaryKeys = ["pair_id", "event_id"],
    indices = [Index(value = ["occurred_at_ms"])],
)
data class PartnerAttemptAlert(
    @ColumnInfo(name = "pair_id") val pairId: String,
    @ColumnInfo(name = "event_id") val eventId: String,
    /** The sender's wire text: "blocked:<target>" or "override:<target>|<batch name>". */
    @ColumnInfo(name = "text") val text: String,
    @ColumnInfo(name = "app_label") val appLabel: String?,
    @ColumnInfo(name = "occurred_at_ms") val occurredAtMs: Long,
    @ColumnInfo(name = "expires_at_ms") val expiresAtMs: Long,
)
