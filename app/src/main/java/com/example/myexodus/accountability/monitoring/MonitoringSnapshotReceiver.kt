package com.example.myexodus.accountability.monitoring

import android.util.Log
import com.example.myexodus.accountability.serialization.AccountabilityEvent
import com.example.myexodus.accountability.serialization.AccountabilityEventKind
import com.example.myexodus.accountability.serialization.MonitoringCapability
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.entity.PartnerMonitoringSnapshot

/**
 * §7 Step 7 receive half: a verified, chained, decrypted `MONITORING_STATUS` body becomes the
 * recipient-local snapshot row. [PartnerMonitoringSnapshotDao.recordIfNewer] is the replay
 * guard — a replayed old snapshot never rolls the card backwards, and a newer install
 * generation (a re-pair) replaces regardless of sequence. Other contract kinds are not this
 * class's business and are ignored.
 *
 * A v3 body also states the grants the sender held. The return value is the set that was
 * held in the previous snapshot and is gone now — empty when nothing was lost, when either
 * side predates the grant field, or when the snapshot is a re-pair's first from a new
 * install. The caller decides whether any locally-known shared rule needs the lost grant;
 * this class only reports the transition.
 */
class MonitoringSnapshotReceiver(
    private val storeFactory: AppRulesStoreFactory,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun onEvent(
        pairId: String,
        senderKeyId: String,
        event: AccountabilityEvent,
    ): Set<MonitoringCapability> {
        if (event.kind != AccountabilityEventKind.MONITORING_STATUS) return emptySet()
        val state = event.monitoringState ?: return emptySet()
        val bucket = event.outboxAgeBucket ?: return emptySet()
        val latest = event.latestRelationshipSequence ?: return emptySet()
        val store = storeFactory.open() ?: return emptySet()
        return runCatching {
            val dao = store.partnerMonitoringSnapshotDao()
            val previous = dao.byPair(pairId)
            val admitted = dao.recordIfNewer(
                PartnerMonitoringSnapshot(
                    pairId = pairId,
                    monitoringState = state.wire,
                    outboxAgeBucket = bucket.wire,
                    snapshotInstallGeneration = senderKeyId,
                    latestRelationshipSeq = latest,
                    snapshotEventSequence = event.relationshipSequence,
                    occurredAtMinute = event.occurredAtMinute,
                    receivedAtMs = now(),
                    capabilities = event.capabilities?.let(MonitoringCapability::encodeSet),
                ),
            )
            if (!admitted) return@runCatching emptySet<MonitoringCapability>()
            lostCapabilities(
                previous = previous?.takeIf { it.snapshotInstallGeneration == senderKeyId }
                    ?.capabilities,
                current = event.capabilities,
            )
        }.onFailure { Log.w(TAG, "monitoring snapshot store failed pairId=$pairId", it) }
            .getOrDefault(emptySet())
    }

    private companion object {
        const val TAG = "MyExodusMonitoring"
    }
}

/**
 * Held-then-gone grants between two snapshots. Null on either side is "this sender (or row)
 * never stated grants" — never a loss, so a pre-v3 sender cannot raise a false alert.
 */
internal fun lostCapabilities(
    previous: String?,
    current: Set<MonitoringCapability>?,
): Set<MonitoringCapability> {
    val before = previous?.let(MonitoringCapability::decodeSetOrNull) ?: return emptySet()
    val after = current ?: return emptySet()
    return before - after
}
