package com.example.myexodus.blocking

import android.util.Log
import com.example.myexodus.accountability.sync.PartnerSyncSignals
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.entity.AttemptLog
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Block/attempt history writes (blocking-core plan §6). Called from the FGS tick at
 * detection time and from [BlockerActivity] when the user forces an override. Writes are
 * best-effort: a failed write (store open failure) is logged, never thrown — the caller is
 * on an enforcement path and must not fail because of telemetry. Partner delivery of the
 * same event is the PartnerSync worker's job (§14 reconcile); this class only records.
 */
@Singleton
class AttemptRecorder @Inject constructor(
    private val storeFactory: AppRulesStoreFactory,
) {
    suspend fun recordBlocked(ruleId: String, blockedTarget: String) =
        record(AttemptLog.OUTCOME_BLOCKED, ruleId, blockedTarget)

    suspend fun recordOverride(ruleId: String, blockedTarget: String) =
        record(AttemptLog.OUTCOME_OVERRIDE, ruleId, blockedTarget)

    private suspend fun record(outcome: String, ruleId: String, blockedTarget: String) {
        val store = storeFactory.open() ?: run {
            Log.w(TAG, "attempt log skipped: store unavailable")
            return
        }
        try {
            // The a11y decision and the FGS poll observe the same blocked launch within a
            // poll interval (1.5 s). Five seconds absorbs that skew without merging two
            // deliberate re-opens; a clock-skewed future row does not count (DAO note).
            // Overrides are never deduped: each is a separate user act.
            val now = System.currentTimeMillis()
            if (outcome == AttemptLog.OUTCOME_BLOCKED &&
                store.attemptLogDao().countRecentBlocked(
                    ruleId,
                    blockedTarget,
                    now - BLOCK_DEDUPE_WINDOW_MS,
                    now,
                ) > 0
            ) {
                return
            }
            store.attemptLogDao().insert(
                AttemptLog(
                    occurredAtEpochMillis = System.currentTimeMillis(),
                    ruleId = ruleId,
                    blockedTarget = blockedTarget,
                    outcome = outcome,
                ),
            )
            // Owner 2026-11-02: the partner must hear about a wall now, not at the next
            // 15-min pass. Same kick (and same cold-signing heal) as every other shared write.
            PartnerSyncSignals.activityEmitted.tryEmit(Unit)
            Log.i(TAG, "attempt logged: outcome=$outcome rule=$ruleId target=$blockedTarget")
        } catch (e: Exception) {
            Log.w(TAG, "attempt log write failed: $outcome $blockedTarget", e)
        }
    }

    private companion object {
        const val TAG = "MyExodusAttempts"
        const val BLOCK_DEDUPE_WINDOW_MS = 5_000L
    }
}
