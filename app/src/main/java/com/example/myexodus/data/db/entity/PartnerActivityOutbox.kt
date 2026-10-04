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
    /** The habit this row's snapshot is anchored to (schema v16, ADR #135) — the consent key
     *  the per-pair reconcile filter joins on (`pair_streak_sharing.habit_progress_id`).
     *  Habit-anchored kinds carry their own habit; profile-anchored kinds carry the writer's
     *  displayed habit and reconcile re-anchors to the pair's first visible habit. Null only
     *  on pre-v16 rows or when the profile had no habit at write time; such rows are never
     *  matched by the consent filter and are swept with the normal retention. */
    @ColumnInfo(name = "habit_progress_id") val habitProgressId: String? = null,
    /** Addressing (schema v14, #96): null = every enrolled pair (state snapshots fan out).
     *  Non-null = this row concerns one pair only — a Req 15 message is written for the
     *  selected partner, and only that partner must ever receive it. */
    @ColumnInfo(name = "target_pair_id") val targetPairId: String? = null,
    /** Motivational message body; `""` for every state-snapshot kind. */
    @ColumnInfo(name = "text") val text: String,
    /** Sender wall-clock of the transition (the wire `eo`). */
    @ColumnInfo(name = "occurred_at_ms") val occurredAtMs: Long,
    @ColumnInfo(name = "days_free") val daysFree: Int,
    @ColumnInfo(name = "relapse_count") val relapseCount: Int,
    /** [encodeMilestoneDays] form — ascending milestone days, comma-joined. */
    @ColumnInfo(name = "milestones_reached") val milestonesReached: String,
    /** The habit's display name at emit time (v15, ADR #134) — the partner must see *which*
     *  streak an event concerns. Null on pre-v15 rows and profile-anchored kinds. */
    @ColumnInfo(name = "habit_label") val habitLabel: String? = null,
    /** Relapse rows: displayed day-count of the streak that ended (the same ordinal the
     *  sender's own relapse copy shows). Null for every other kind. */
    @ColumnInfo(name = "ended_days") val endedDays: Int? = null,
    /** Milestone rows: the day reached. Null for every other kind. */
    @ColumnInfo(name = "milestone_day") val milestoneDay: Int? = null,
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
