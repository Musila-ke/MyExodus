package com.example.myexodus.blocking

import android.util.Log
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
        val store = storeFactory.open() ?: return
        try {
            store.attemptLogDao().insert(
                AttemptLog(
                    occurredAtEpochMillis = System.currentTimeMillis(),
                    ruleId = ruleId,
                    blockedTarget = blockedTarget,
                    outcome = outcome,
                ),
            )
            Log.i(TAG, "attempt logged: outcome=$outcome rule=$ruleId target=$blockedTarget")
        } catch (e: Exception) {
            Log.w(TAG, "attempt log write failed: $outcome $blockedTarget", e)
        }
    }

    private companion object {
        const val TAG = "MyExodusAttempts"
    }
}
