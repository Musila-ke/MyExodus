package com.example.myexodus.blocking

import android.util.Log
import com.example.myexodus.accountability.sync.PartnerSyncSignals
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.entity.AttemptLog
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Block/attempt history writes (blocking-core plan §6). Called from the FGS tick at
 * detection time. Writes are best-effort: a failed write (store open failure) is logged,
 * never thrown — the caller is on an enforcement path and must not fail because of
 * telemetry. Partner delivery of the same event is the PartnerSync worker's job (§14
 * reconcile); this class only records.
 */
@Singleton
class AttemptRecorder @Inject constructor(
    private val storeFactory: AppRulesStoreFactory,
) {
    suspend fun recordBlocked(ruleId: String, blockedTarget: String) {
        val store = storeFactory.open() ?: run {
            Log.w(TAG, "attempt log skipped: store unavailable")
            return
        }
        try {
            // The a11y decision and the FGS poll can observe the SAME blocked launch far
            // apart: the wall-launch grace retries a cold-start eviction for up to 10 s
            // (EnforcementForegroundService.BLOCK_LAUNCH_GRACE_MS) and the a11y cover
            // re-samples for a few more, so one wall episode produced two attempt rows and
            // two partner notifications (owner report 2026-11-10). Thirty seconds covers
            // the whole episode while a deliberate re-open a minute later still counts.
            val now = System.currentTimeMillis()
            if (store.attemptLogDao().countRecentBlocked(
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
                    outcome = AttemptLog.OUTCOME_BLOCKED,
                ),
            )
            // Owner 2026-11-02: the partner must hear about a wall now, not at the next
            // 15-min pass. Same kick (and same cold-signing heal) as every other shared write.
            PartnerSyncSignals.activityEmitted.tryEmit(Unit)
            Log.i(TAG, "block logged: rule=$ruleId target=$blockedTarget")
        } catch (e: Exception) {
            Log.w(TAG, "attempt log write failed: $blockedTarget", e)
        }
    }

    private companion object {
        const val TAG = "MyExodusAttempts"
        const val BLOCK_DEDUPE_WINDOW_MS = 30_000L
    }
}
