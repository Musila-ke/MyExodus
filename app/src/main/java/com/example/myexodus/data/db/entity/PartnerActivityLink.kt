package com.example.myexodus.data.db.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity

/**
 * One delivered mirror of a [PartnerActivityOutbox] journal row to one pair (schema v13). The
 * activity-layer twin of [AttemptMirrorLink] (ADR #111): the composite key makes each
 * (event, pair) delivery exactly-once, so a row pending for one pair stays pending for every
 * pair that has no link yet — the §19.5 multi-pair fan-out.
 */
@Entity(
    tableName = "partner_activity_link",
    primaryKeys = ["event_id", "pair_id"],
)
data class PartnerActivityLink(
    @ColumnInfo(name = "event_id") val eventId: String,
    @ColumnInfo(name = "pair_id") val pairId: String,
    /** The chain sequence of the emitted event in THIS pair's chain. */
    @ColumnInfo(name = "sequence") val sequence: Long,
)
