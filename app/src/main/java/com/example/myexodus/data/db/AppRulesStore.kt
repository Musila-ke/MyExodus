package com.example.myexodus.data.db

import androidx.room3.Database
import androidx.room3.RoomDatabase
import com.example.myexodus.data.db.dao.AttemptLogDao
import com.example.myexodus.data.db.dao.CoachingStepDao
import com.example.myexodus.data.db.dao.DailyUsageTrackerDao
import com.example.myexodus.data.db.dao.HabitProgressDao
import com.example.myexodus.data.db.dao.MilestoneEventDao
import com.example.myexodus.data.db.dao.PolicyVersionDao
import com.example.myexodus.data.db.dao.RecoveryPolicyDao
import com.example.myexodus.data.db.dao.RecoveryStateDao
import com.example.myexodus.data.db.dao.RecoveryStreakDao
import com.example.myexodus.data.db.dao.RestrictionRuleDao
import com.example.myexodus.data.db.dao.UserProfileDao
import com.example.myexodus.data.db.entity.AttemptLog
import com.example.myexodus.data.db.entity.CoachingStep
import com.example.myexodus.data.db.entity.DailyUsageTracker
import com.example.myexodus.data.db.entity.HabitProgress
import com.example.myexodus.data.db.entity.MilestoneEvent
import com.example.myexodus.data.db.entity.PolicyVersion
import com.example.myexodus.data.db.entity.RecoveryPolicy
import com.example.myexodus.data.db.entity.RecoveryState
import com.example.myexodus.data.db.entity.RecoveryStreak
import com.example.myexodus.data.db.entity.RestrictionRule
import com.example.myexodus.data.db.entity.UserProfile

/**
 * Room database holding the rule and V1 data model plus the habit-streak tables — encrypted
 * with SQLCipher since habit-streaks plan §8 Step 1, opened through [AppRulesStoreFactory]
 * with a Keystore-wrapped DEK. Raw behavioral telemetry lives in [ActivityLogStore]
 * (SQLCipher); block/attempt history (blocking-core plan §6) is the [AttemptLog] table
 * here — Room is its system of record. Schemas are exported to `app/schemas/` for the
 * MigrationTestHelper harness (§12).
 */
@Database(
    entities = [
        UserProfile::class,
        RestrictionRule::class,
        DailyUsageTracker::class,
        HabitProgress::class,
        CoachingStep::class,
        PolicyVersion::class,
        RecoveryPolicy::class,
        RecoveryState::class,
        AttemptLog::class,
        RecoveryStreak::class,
        MilestoneEvent::class,
    ],
    version = 7,
    exportSchema = true,
)
abstract class AppRulesStore : RoomDatabase() {
    abstract fun attemptLogDao(): AttemptLogDao
    abstract fun userProfileDao(): UserProfileDao
    abstract fun restrictionRuleDao(): RestrictionRuleDao
    abstract fun dailyUsageTrackerDao(): DailyUsageTrackerDao
    abstract fun habitProgressDao(): HabitProgressDao
    abstract fun coachingStepDao(): CoachingStepDao
    abstract fun policyVersionDao(): PolicyVersionDao
    abstract fun recoveryPolicyDao(): RecoveryPolicyDao
    abstract fun recoveryStateDao(): RecoveryStateDao
    abstract fun recoveryStreakDao(): RecoveryStreakDao
    abstract fun milestoneEventDao(): MilestoneEventDao
}
