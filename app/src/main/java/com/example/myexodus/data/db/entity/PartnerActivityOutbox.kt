package com.example.myexodus.data.db.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * Journal of one local partner-visible activity event (schema v13, partner-activity plan §8.1/§8.2
 * delta). Written inside the same domain transaction that applies the local transition, so the
 * event exists locally from t0 even though its envelope cannot be created then: signing is
 * biometric-gated (900 s window) and each pair's chain sequence is allocated at reconcile time.
 * The reconcile loop encodes one payload per row per pair from this journal and records the
 * delivery in [PartnerActivityLink]; the durable envelope bytes then live in the accountability
 * `outbox` (shipped rails, ADR #111). Rows are written unconditionally — fan-out is a transport
 * concern — and swept by [com.example.myexodus.data.db.dao.PartnerActivityDao]'s retention query.
 */
@Entity(
    tableName = "partner_activity_outbox",
    indices = [Index(value = ["created_at_ms"])],
)
data class PartnerActivityOutbox(
    @PrimaryKey @ColumnInfo(name = "event_id") val eventId: String,
    /** The wire type — one of `EventPayload.ACTIVITY_TYPES`, stored verbatim. */
    @ColumnInfo(name = "kind") val kind: String,
    /** Motivational message body; `""` for every state-snapshot kind. */
    @ColumnInfo(name = "text") val text: String,
    /** Sender wall-clock of the transition (the wire `eo`). */
    @ColumnInfo(name = "occurred_at_ms") val occurredAtMs: Long,
    @ColumnInfo(name = "days_free") val daysFree: Int,
    @ColumnInfo(name = "relapse_count") val relapseCount: Int,
    /** [encodeMilestoneDays] form — ascending milestone days, comma-joined. */
    @ColumnInfo(name = "milestones_reached") val milestonesReached: String,
    @ColumnInfo(name = "coaching_progress") val coachingProgress: Int,
    /** The schema this snapshot conforms to, stated by the emitter — never defaulted from a
     *  constant, so a future version bump cannot silently re-label an old snapshot (FR-17). */
    @ColumnInfo(name = "protocol_version") val protocolVersion: Int,
    @ColumnInfo(name = "schema_version") val schemaVersion: Int,
    /** Local journal write time — the retention sweep's clock. */
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
)

/** Canonical column form of the ascending milestone-day list: `"7,30"`, empty list = `""`. */
fun encodeMilestoneDays(days: List<Int>): String = days.joinToString(",")

/** Tolerant by design: a partially corrupt column must degrade the partner card, never crash it. */
fun decodeMilestoneDays(text: String): List<Int> = text.split(',').mapNotNull { it.toIntOrNull() }
