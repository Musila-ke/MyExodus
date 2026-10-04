package com.example.myexodus.data.db.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey

@Entity(tableName = "user_profile")
data class UserProfile(
    @PrimaryKey @ColumnInfo(name = "user_id") val userId: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "email") val email: String,
    @ColumnInfo(name = "phone_number") val phoneNumber: String?,
    @ColumnInfo(name = "date_of_birth") val dateOfBirth: Long,
    @ColumnInfo(name = "has_accessibility_permission") val hasAccessibilityPermission: Boolean,
    @ColumnInfo(name = "has_usage_stats_permission") val hasUsageStatsPermission: Boolean,
    @ColumnInfo(name = "has_vpn_permission") val hasVpnPermission: Boolean,
    @ColumnInfo(name = "has_notification_permission") val hasNotificationPermission: Boolean,
    @ColumnInfo(name = "has_device_admin_permission") val hasDeviceAdminPermission: Boolean,
    @ColumnInfo(name = "device_public_key") val devicePublicKey: String?,
    @ColumnInfo(name = "recovery_escrow_key") val recoveryEscrowKey: String?,
    @ColumnInfo(name = "is_active") val isActive: Boolean,
    // Partner-sharing consent moved out of this row in v16 (ADR #135): the profile-wide
    // categories (check-ins, recovery) are chosen per pair — `pair_profile_sharing`.
)
