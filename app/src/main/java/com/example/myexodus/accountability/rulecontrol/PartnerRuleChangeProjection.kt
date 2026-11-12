package com.example.myexodus.accountability.rulecontrol

import android.util.Log
import com.example.myexodus.accountability.delivery.PartnerEventKind
import com.example.myexodus.accountability.delivery.PartnerMailboxDocument
import com.example.myexodus.accountability.delivery.PartnerRuleChangePayload
import com.example.myexodus.accountability.enrollment.EnrollmentDao
import com.example.myexodus.accountability.serialization.RuleChange
import com.example.myexodus.accountability.serialization.RuleIdentity
import com.example.myexodus.accountability.sync.displayNameOf
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.entity.PartnerEventJournal
import com.example.myexodus.data.db.entity.PartnerRuleChange

/**
 * §13.4 recipient half of the signed rule-lifecycle transport: one delivered
 * `RULE_CHANGE_ATTEMPT` document becomes exactly the feed row (and notification) the legacy
 * chain path produced, so a pair sees the same dashboard whether a change arrived on the old
 * or the new transport.
 *
 * The `(pairId, eventId)` primary key carries the source journal row's UUID, so a mailbox
 * redelivery — or the same logical change arriving on both transports during the staged
 * rollout — inserts once and notifies once.
 *
 * Vault gating (§13.4): the projection opens the vault through the app-lock gate. While it is
 * locked the exception propagates and [com.example.myexodus.accountability.delivery.PartnerMailboxReceiver]
 * leaves the document unreceipted, so the record migrates only after the normal foreground
 * unlock — never from a background unlock of its own.
 */
class PartnerRuleChangeProjection(
    private val storeFactory: AppRulesStoreFactory,
    private val enrollmentDao: EnrollmentDao,
    private val notify: (pairId: String, partnerName: String, change: RuleChange, identity: RuleIdentity?) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {

    suspend fun onDelivered(document: PartnerMailboxDocument, plaintext: ByteArray) {
        if (document.kind != PartnerEventKind.RULE_CHANGE_ATTEMPT.wire) return
        val pairId = document.pairId ?: return
        val eventId = document.eventId ?: return
        val decoded = PartnerRuleChangePayload.decodeOrNull(plaintext) ?: return
        val store = storeFactory.open()
            ?: throw IllegalStateException("rule change projection needs the local store")

        val nowMs = now()
        val dao = store.partnerRuleChangeDao()
        dao.deleteExpired(nowMs)
        // The gap rows share this retention cadence and are written off the rule path, so the
        // same sweep keeps them bounded.
        store.partnerProtectionGapDao().deleteExpired(nowMs)
        val inserted = dao.insert(
            PartnerRuleChange(
                pairId = pairId,
                eventId = eventId,
                ruleId = decoded.ruleId.toString(),
                ruleChange = decoded.change.wire,
                ruleLabel = decoded.identity.label,
                ruleTargetType = decoded.identity.targetType.wire,
                ruleItemCount = decoded.identity.itemCount,
                ruleBatchLabel = decoded.identity.batchLabel,
                occurredAtMinute = decoded.occurredAtMinute,
                receivedAtMs = nowMs,
                expiresAtMs = nowMs + PartnerEventJournal.RETENTION_MILLIS,
            ),
        )
        if (inserted == -1L) return
        val enrollment = enrollmentDao.getByPairId(pairId) ?: return
        // A failed post must not stall the mailbox watermark: the feed row is the source of
        // truth and a retry would find the insert above already stored.
        runCatching {
            notify(pairId, displayNameOf(enrollment), decoded.change, decoded.identity)
        }.onFailure { Log.w(TAG, "rule change notification failed pairId=$pairId", it) }
    }

    private companion object {
        const val TAG = "MyExodusRuleControl"
    }
}
