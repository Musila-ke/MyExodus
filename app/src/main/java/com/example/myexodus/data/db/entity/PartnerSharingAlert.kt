package com.example.myexodus.data.db.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index

/**
 * Recipient-local record of a sharing-consent revoke delivered on the staged transport
 * (owner ask 2026-11-13: availability and check-ins switch-offs belong in Rules & blocks and
 * the feed, not only in the tray). Staged deliveries write no chain rows, and these revoke
 * bodies carry no text or state, so neither feed source could see them. One row per delivered
 * event; detail is retained 30 days, matching the journal.
 */
@Entity(
    tableName = "partner_sharing_alert",
    primaryKeys = ["pair_id", "event_id"],
    indices = [Index(value = ["occurred_at_ms"])],
)
data class PartnerSharingAlert(
    @ColumnInfo(name = "pair_id") val pairId: String,
    @ColumnInfo(name = "event_id") val eventId: String,
    /** The wire consent type, e.g. `availability_sharing_stopped`. */
    @ColumnInfo(name = "kind") val kind: String,
    /** The streak's name on a streak revoke; null for the profile-wide switches. */
    @ColumnInfo(name = "label") val label: String?,
    @ColumnInfo(name = "occurred_at_ms") val occurredAtMs: Long,
    @ColumnInfo(name = "expires_at_ms") val expiresAtMs: Long,
)
