package com.example.myexodus.data.db.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * One urge episode on the recovery screen (recovery-resource plan v3 W7): the local record
 * behind the urge log (V1 Req 10), the resource history (FR-9), and the partner mirror
 * (V1 Req 20 partner half, W5). States are the v2 FR-5 machine —
 * STARTED → COMPLETED | ABANDONED | INTERRUPTED — where INTERRUPTED is written by the
 * app-start sweep for rows left STARTED by process death. Per-pair mirror delivery lives in
 * [RecoveryMirrorLink] (schema v12, ADR #111); [eventSequence] is the pre-v12 single-link
 * column, kept only as the legacy marker the pending query excludes on.
 */
@Entity(
    tableName = "recovery_session",
    indices = [
        Index(value = ["started_at_epoch_ms"]),
        Index(value = ["event_sequence"]),
    ],
)
data class RecoverySession(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    /** Catalog technique id, or [TECHNIQUE_NONE] for a log-only episode. */
    @ColumnInfo(name = "technique") val technique: String,
    /** Optional 1–10 rating from the log form (both nullable — the form is skippable). */
    @ColumnInfo(name = "intensity_start") val intensityStart: Int? = null,
    @ColumnInfo(name = "intensity_end") val intensityEnd: Int? = null,
    @ColumnInfo(name = "state") val state: String = STATE_STARTED,
    @ColumnInfo(name = "started_at_epoch_ms") val startedAtEpochMs: Long,
    @ColumnInfo(name = "ended_at_epoch_ms") val endedAtEpochMs: Long? = null,
    @ColumnInfo(name = "duration_sec") val durationSec: Int? = null,
    /** IANA zone at start — history and window math stay interpretable after travel. */
    @ColumnInfo(name = "zone_id") val zoneId: String,
    @ColumnInfo(name = "entry") val entry: String,
    @ColumnInfo(name = "false_alarm") val falseAlarm: Boolean = false,
    @ColumnInfo(name = "event_sequence") val eventSequence: Long? = null,
) {
    val isTerminal: Boolean get() = state != STATE_STARTED

    companion object {
        const val STATE_STARTED = "STARTED"
        const val STATE_COMPLETED = "COMPLETED"
        const val STATE_ABANDONED = "ABANDONED"
        const val STATE_INTERRUPTED = "INTERRUPTED"

        const val TECHNIQUE_NONE = "none"

        /** Where the recovery surface was opened from (v2 `entry` column). */
        const val ENTRY_BLOCK_SCREEN = "BLOCK_SCREEN"
        const val ENTRY_WIDGET = "WIDGET"
        const val ENTRY_TAB = "TAB"

        /** Home's quick action writes in place, so the row records the real surface. */
        const val ENTRY_HOME = "HOME"

        /** Process death leaves rows STARTED; past this age the sweep closes them (v2 M2.4). */
        const val STALE_SESSION_MS = 15 * 60 * 1000L
    }
}
