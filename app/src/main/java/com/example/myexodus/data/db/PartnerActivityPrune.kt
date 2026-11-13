package com.example.myexodus.data.db

/**
 * Deletes every pair-scoped content row outside the chain tables: the partner's snapshots,
 * plans, rule changes, sharing consent, and the Home pinned message. Shared by the orphan
 * sweep, the account-deletion purge, the unpair shred, and pairing completion. The chain
 * tables are wiped separately by ChainStateStore — these live in the rules store, so AD-07's
 * deterministic pairId reuse would otherwise carry a previous session's rows into a re-pair.
 */
suspend fun AppRulesStore.prunePartnerActivity(pairIds: List<String>) {
    partnerActivityDao().deleteTargetedJournalFor(pairIds)
    partnerActivityDao().deleteLinksFor(pairIds)
    partnerActivityDao().deleteProjectionsFor(pairIds)
    partnerActivityDao().deleteStreakProjectionsFor(pairIds)
    pairSharingDao().deleteStreakFor(pairIds)
    pairSharingDao().deleteProfileFor(pairIds)
    partnerPlanDao().deleteForPairs(pairIds)
    partnerMonitoringSnapshotDao().deleteFor(pairIds)
    partnerRuleChangeDao().deleteFor(pairIds)
    partnerProtectionGapDao().deleteFor(pairIds)
    partnerAttemptAlertDao().deleteFor(pairIds)
    partnerSharingAlertDao().deleteFor(pairIds)
    partnerEventJournalDao().deleteRuleRowsFor(pairIds)
}
