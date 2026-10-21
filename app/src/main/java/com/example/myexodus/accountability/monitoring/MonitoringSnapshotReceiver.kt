package com.example.myexodus.accountability.monitoring

import android.util.Log
import com.example.myexodus.accountability.serialization.AccountabilityEvent
import com.example.myexodus.accountability.serialization.AccountabilityEventKind
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.entity.PartnerMonitoringSnapshot

/**
 * §7 Step 7 receive half: a verified, chained, decrypted `MONITORING_STATUS` body becomes the
 * recipient-local snapshot row. [PartnerMonitoringSnapshotDao.recordIfNewer] is the replay
 * guard — a replayed old snapshot never rolls the card backwards, and a newer install
 * generation (a re-pair) replaces regardless of sequence. Other contract kinds are not this
 * class's business and are ignored.
 */
class MonitoringSnapshotReceiver(
    private val storeFactory: AppRulesStoreFactory,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun onEvent(pairId: String, senderKeyId: String, event: AccountabilityEvent): Boolean {
        if (event.kind != AccountabilityEventKind.MONITORING_STATUS) return false
        val state = event.monitoringState ?: return false
        val bucket = event.outboxAgeBucket ?: return false
        val latest = event.latestRelationshipSequence ?: return false
        val store = storeFactory.open() ?: return false
        return runCatching {
            store.partnerMonitoringSnapshotDao().recordIfNewer(
                PartnerMonitoringSnapshot(
                    pairId = pairId,
                    monitoringState = state.wire,
                    outboxAgeBucket = bucket.wire,
                    snapshotInstallGeneration = senderKeyId,
                    latestRelationshipSeq = latest,
                    snapshotEventSequence = event.relationshipSequence,
                    occurredAtMinute = event.occurredAtMinute,
                    receivedAtMs = now(),
                ),
            )
        }.onFailure { Log.w(TAG, "monitoring snapshot store failed pairId=$pairId", it) }
            .getOrDefault(false)
    }

    private companion object {
        const val TAG = "MyExodusMonitoring"
    }
}
