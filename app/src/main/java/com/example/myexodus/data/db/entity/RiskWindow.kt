package com.example.myexodus.data.db.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import com.example.myexodus.recovery.RiskWindowSpec

/**
 * A local-time willpower window (V1 Req 19, FR-7): minutes-of-day range with a day mask,
 * evaluated by the pure `RiskWindowEvaluator` on every use (no cached verdict). At most
 * three rows exist; the default (20:00–06:00, all days) is created lazily on first read.
 */
@Entity(tableName = "risk_window")
data class RiskWindow(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "start_minute") val startMinute: Int,
    @ColumnInfo(name = "end_minute") val endMinute: Int,
    /** bit0 = Monday … bit6 = Sunday, applied to the day the window starts. */
    @ColumnInfo(name = "days_mask") val daysMask: Int,
    @ColumnInfo(name = "enabled") val enabled: Boolean = true,
) {
    val isOvernight: Boolean get() = startMinute > endMinute

    fun toSpec(): RiskWindowSpec = RiskWindowSpec(
        id = id,
        startMinute = startMinute,
        endMinute = endMinute,
        daysMask = daysMask,
        enabled = enabled,
    )

    companion object {
        const val MAX_WINDOWS = 3
        val DEFAULT = RiskWindow(
            startMinute = 20 * 60,
            endMinute = 6 * 60,
            daysMask = RiskWindowSpec.ALL_DAYS_MASK,
        )
    }
}
