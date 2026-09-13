package com.example.myexodus.data.db.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(
    tableName = "daily_usage_tracker",
    foreignKeys = [
        ForeignKey(
            entity = UserProfile::class,
            parentColumns = ["user_id"],
            childColumns = ["profile_id"],
        ),
    ],
    indices = [
        Index(value = ["profile_id", "package_name", "date_epoch"], unique = true),
    ],
)
data class DailyUsageTracker(
    @PrimaryKey @ColumnInfo(name = "tracker_id") val trackerId: String,
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "date_epoch") val dateEpoch: Int,
    @ColumnInfo(name = "ms_used_today") val msUsedToday: Long,
) {
    companion object {
        /** UTC epoch-day integer (days since epoch), timezone-safe daily aggregation. */
        fun epochDayFromEpochMillis(epochMillisUtc: Long): Int =
            (epochMillisUtc / MILLIS_PER_DAY).toInt()

        private const val MILLIS_PER_DAY = 86_400_000L
    }
}
