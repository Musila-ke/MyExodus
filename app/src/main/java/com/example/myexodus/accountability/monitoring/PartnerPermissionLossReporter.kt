package com.example.myexodus.accountability.monitoring

import android.util.Log
import com.example.myexodus.accountability.enrollment.EnrollmentDao
import com.example.myexodus.accountability.serialization.MonitoringCapability
import com.example.myexodus.accountability.sync.displayNameOf
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.entity.PartnerEventJournal
import com.example.myexodus.data.db.entity.PartnerProtectionGap

/**
 * Owner directive 2026-11-10, shared by both transports: a grant the other phone lost alerts
 * only when a rule assigned to this partner still needs it, and the loss is also a feed row
 * (Rules & blocks), not only a tray notification. Extracted from the legacy chain callback so
 * the staged observation projection raises the identical alert from a delivered
 * `MONITORING_UPDATE`.
 */
class PartnerPermissionLossReporter(
    private val storeFactory: AppRulesStoreFactory,
    private val enrollmentDao: EnrollmentDao,
    private val notifyLost: (
        pairId: String,
        partnerName: String,
        needing: Map<MonitoringCapability, List<String>>,
    ) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun report(
        pairId: String,
        eventId: String,
        occurredAtMinute: Long,
        lost: Set<MonitoringCapability>,
    ) {
        if (lost.isEmpty()) return
        runCatching {
            val store = storeFactory.open() ?: return@runCatching
            val rows = store.partnerRuleChangeDao().recent(pairId, now(), RULE_FEED_LIMIT)
            val needing = rulesNeeding(rows, lost)
            if (needing.isEmpty()) return@runCatching
            val nowMs = now()
            runCatching {
                needing.forEach { (capability, rules) ->
                    store.partnerProtectionGapDao().insert(
                        PartnerProtectionGap(
                            pairId = pairId,
                            eventId = eventId,
                            capability = capability.wire,
                            ruleNames = PartnerProtectionGap.encodeRuleNames(rules),
                            occurredAtMs = occurredAtMinute * 60_000L,
                            expiresAtMs = nowMs + PartnerEventJournal.RETENTION_MILLIS,
                        ),
                    )
                }
            }.onFailure { Log.w(TAG, "protection gap store failed pairId=$pairId", it) }
            enrollmentDao.getByPairId(pairId)?.let { enrollment ->
                notifyLost(pairId, displayNameOf(enrollment), needing)
            }
        }.onFailure { Log.w(TAG, "permission-lost notification failed pairId=$pairId", it) }
    }

    private companion object {
        const val TAG = "MyExodusPermissionLoss"
        const val RULE_FEED_LIMIT = 100
    }
}
