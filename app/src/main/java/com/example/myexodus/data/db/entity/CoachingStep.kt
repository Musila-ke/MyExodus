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
    @ColumnInfo(name = "associated_trigger_window") val associatedTriggerWindow: String,
)
