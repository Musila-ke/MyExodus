package com.example.myexodus.data.db.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * The received peer's partner-visible state for one pair (schema v13, plan §8.10 delta): one row
 * per pair, updated from each validated activity event's `stateAfter` in chain order. Applied
 * wholesale — never as a delta — so duplicate delivery cannot double-count (§4.6); [lastSequence]
 * is the monotonic guard that makes a late replay of an older snapshot a no-op. [lastSequence] = 0
 * means "nothing applied yet" (chain sequences start at 1), which is also the placeholder a row
 * created by a message-only arrival carries. The pinned message is the Req 15 arrival surface —
 * the newest one across pairs is shown on the user's home dashboard.
 */
@Entity(tableName = "partner_activity_projection")
data class PartnerActivityProjection(
    @PrimaryKey @ColumnInfo(name = "pair_id") val pairId: String,
    @ColumnInfo(name = "days_free") val daysFree: Int,
    @ColumnInfo(name = "relapse_count") val relapseCount: Int,
    /** [encodeMilestoneDays] form — the sender's achieved milestone days of their current streak. */
    @ColumnInfo(name = "milestones_reached") val milestonesReached: String,
    @ColumnInfo(name = "coaching_progress") val coachingProgress: Int,
    /** The peer's wall-clock of the last applied transition — display chronology only. */
    @ColumnInfo(name = "last_event_occurred_at_ms") val lastEventOccurredAtMs: Long,
    /** Highest applied peer-stream sequence; 0 = nothing applied. */
    @ColumnInfo(name = "last_sequence") val lastSequence: Long,
    @ColumnInfo(name = "pinned_message_text") val pinnedMessageText: String?,
    @ColumnInfo(name = "pinned_message_at_ms") val pinnedMessageAtMs: Long?,
)
