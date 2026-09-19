package com.example.myexodus.data.db.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.PrimaryKey

@Entity(
    tableName = "coaching_step",
    foreignKeys = [
        ForeignKey(
            entity = UserProfile::class,
            parentColumns = ["user_id"],
            childColumns = ["profile_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class CoachingStep(
    @PrimaryKey @ColumnInfo(name = "step_id") val stepId: String,
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "step_order") val stepOrder: Int,
    @ColumnInfo(name = "action_text") val actionText: String,
    /** Blank = any time; otherwise the id of the [RiskWindow] this item is tied to (OPEN-4:
     *  every item always displays, the ones matching the live window highlight). */
    @ColumnInfo(name = "associated_trigger_window") val associatedTriggerWindow: String,
    /** [KIND_STEP] (ordered emergency steps, Req 20), [KIND_IF_THEN], or [KIND_VALUE] (FR-6). */
    @ColumnInfo(name = "kind", defaultValue = KIND_STEP) val kind: String = KIND_STEP,
) {
    companion object {
        const val KIND_STEP = "STEP"
        const val KIND_IF_THEN = "IF_THEN"
        const val KIND_VALUE = "VALUE"

        /** v2 FR-6 caps: per-kind item limit and per-item text length. */
        const val MAX_PER_KIND = 5
        const val MAX_TEXT_LENGTH = 200
    }
}
