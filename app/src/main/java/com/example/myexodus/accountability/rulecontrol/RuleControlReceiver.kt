package com.example.myexodus.accountability.rulecontrol

import android.util.Log
import com.example.myexodus.accountability.serialization.AccountabilityEvent
import com.example.myexodus.accountability.serialization.AccountabilityEventKind
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.entity.PartnerEventJournal
import com.example.myexodus.data.db.entity.PartnerRuleChange

/**
 * §8.4 RULE_CONTROL receive half: a verified, chained, decrypted rule-lifecycle body becomes
 * the recipient-local feed row. The primary key (pair, event) makes mailbox redelivery a
 * no-op; expired rows are swept on every insert, which bounds the table without a worker.
 * Other contract kinds are not this class's business and are ignored.
 */
class RuleControlReceiver(
    private val storeFactory: AppRulesStoreFactory,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun onEvent(pairId: String, event: AccountabilityEvent): Boolean {
        if (event.kind != AccountabilityEventKind.RULE_CONTROL) return false
        val ruleId = event.ruleId ?: return false
        val change = event.ruleChange ?: return false
        val store = storeFactory.open() ?: return false
        return runCatching {
            val nowMs = now()
            val dao = store.partnerRuleChangeDao()
            dao.deleteExpired(nowMs)
            val identity = event.ruleIdentity
            dao.insert(
                PartnerRuleChange(
                    pairId = pairId,
                    eventId = event.eventId,
                    ruleId = ruleId.toString(),
                    ruleChange = change.wire,
                    ruleLabel = identity?.label,
                    ruleTargetType = identity?.targetType?.wire,
                    ruleItemCount = identity?.itemCount,
                    ruleBatchLabel = identity?.batchLabel,
                    occurredAtMinute = event.occurredAtMinute,
                    receivedAtMs = nowMs,
                    expiresAtMs = nowMs + PartnerEventJournal.RETENTION_MILLIS,
                ),
            )
            true
        }.onFailure { Log.w(TAG, "rule change store failed pairId=$pairId", it) }
            .getOrDefault(false)
    }

    private companion object {
        const val TAG = "MyExodusRuleControl"
    }
}
