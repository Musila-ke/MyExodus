package com.example.myexodus.data.db

import androidx.room3.Database
import androidx.room3.RoomDatabase
import com.example.myexodus.data.db.dao.AttemptLogDao
import com.example.myexodus.data.db.dao.CoachingStepDao
import com.example.myexodus.data.db.dao.DailyUsageTrackerDao
import com.example.myexodus.data.db.dao.HabitProgressDao
import com.example.myexodus.data.db.dao.MilestoneEventDao
import com.example.myexodus.data.db.dao.PairSharingDao
import com.example.myexodus.data.db.dao.PartnerActivityDao
import com.example.myexodus.data.db.dao.PartnerEventJournalDao
import com.example.myexodus.data.db.dao.PartnerLivenessDao
import com.example.myexodus.data.db.dao.PartnerMonitoringSnapshotDao
import com.example.myexodus.data.db.dao.PartnerPlanDao
import com.example.myexodus.data.db.dao.PartnerRuleChangeDao
import com.example.myexodus.data.db.dao.PolicyVersionDao
import com.example.myexodus.data.db.dao.RecoveryPolicyDao
import com.example.myexodus.data.db.dao.RecoverySessionDao
import com.example.myexodus.data.db.dao.RecoveryStateDao
import com.example.myexodus.data.db.dao.RecoveryStreakDao
import com.example.myexodus.data.db.dao.RemoteBackupAnchorDao
import com.example.myexodus.data.db.dao.RiskWindowDao
import com.example.myexodus.data.db.dao.RestrictionRuleDao
import com.example.myexodus.data.db.dao.UsageLedgerDao
import com.example.myexodus.data.db.dao.UserProfileDao
import com.example.myexodus.data.db.entity.AttemptLog
import com.example.myexodus.data.db.entity.AttemptMirrorLink
import com.example.myexodus.data.db.entity.CoachingStep
import com.example.myexodus.data.db.entity.DailyUsageTracker
import com.example.myexodus.data.db.entity.DeviceLiveness
import com.example.myexodus.data.db.entity.HabitProgress
import com.example.myexodus.data.db.entity.MilestoneEvent
import com.example.myexodus.data.db.entity.PairProfileSharing
import com.example.myexodus.data.db.entity.PairStreakSharing
import com.example.myexodus.data.db.entity.PartnerActivityLink
import com.example.myexodus.data.db.entity.PartnerActivityOutbox
import com.example.myexodus.data.db.entity.PartnerActivityProjection
import com.example.myexodus.data.db.entity.PartnerEventJournal
import com.example.myexodus.data.db.entity.PartnerMonitoringCheckin
import com.example.myexodus.data.db.entity.PartnerMonitoringSnapshot
import com.example.myexodus.data.db.entity.PartnerPlan
import com.example.myexodus.data.db.entity.PartnerPlanTask
import com.example.myexodus.data.db.entity.PartnerRuleChange
import com.example.myexodus.data.db.entity.PolicyVersion
import com.example.myexodus.data.db.entity.RecoveryMirrorLink
import com.example.myexodus.data.db.entity.RecoveryPolicy
import com.example.myexodus.data.db.entity.RecoverySession
import com.example.myexodus.data.db.entity.RecoveryState
import com.example.myexodus.data.db.entity.RecoveryStreak
import com.example.myexodus.data.db.entity.RemoteBackupAnchor
import com.example.myexodus.data.db.entity.RestrictionRule
import com.example.myexodus.data.db.entity.RiskWindow
import com.example.myexodus.data.db.entity.UsageLedger
import com.example.myexodus.data.db.entity.UserProfile
import com.example.myexodus.domainusage.RuleUsageDaily
import com.example.myexodus.domainusage.RuleUsageDao

/**
 * Room database holding the rule and V1 data model plus the habit-streak tables and the
 * partner-activity journal/projection (v13/v14) — encrypted with SQLCipher since habit-streaks
 * plan §8 Step 1, opened through [AppRulesStoreFactory] with a Keystore-wrapped DEK. Raw
 * behavioral telemetry lives in [ActivityLogStore] (SQLCipher); block/attempt history
 * (blocking-core plan §6) is the [AttemptLog] table here — Room is its system of record.
 * Schemas are exported to `app/schemas/` for the MigrationTestHelper harness (§12).
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
        RecoverySession::class,
        RiskWindow::class,
        RuleUsageDaily::class,
        UsageLedger::class,
        AttemptMirrorLink::class,
        RecoveryMirrorLink::class,
        PartnerActivityOutbox::class,
        PartnerActivityLink::class,
        PartnerActivityProjection::class,
        PairStreakSharing::class,
        PairProfileSharing::class,
        PartnerPlan::class,
        PartnerPlanTask::class,
        PartnerEventJournal::class,
        DeviceLiveness::class,
        PartnerMonitoringCheckin::class,
        PartnerMonitoringSnapshot::class,
        PartnerRuleChange::class,
        RemoteBackupAnchor::class,
    ],
    version = 26,
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
    abstract fun recoverySessionDao(): RecoverySessionDao
    abstract fun riskWindowDao(): RiskWindowDao
    abstract fun ruleUsageDao(): RuleUsageDao
    abstract fun usageLedgerDao(): UsageLedgerDao
    abstract fun partnerActivityDao(): PartnerActivityDao
    abstract fun partnerEventJournalDao(): PartnerEventJournalDao
    abstract fun partnerLivenessDao(): PartnerLivenessDao
    abstract fun partnerMonitoringSnapshotDao(): PartnerMonitoringSnapshotDao
    abstract fun partnerRuleChangeDao(): PartnerRuleChangeDao
    abstract fun pairSharingDao(): PairSharingDao
    abstract fun partnerPlanDao(): PartnerPlanDao
    abstract fun remoteBackupAnchorDao(): RemoteBackupAnchorDao
}
