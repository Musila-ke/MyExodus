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
import com.example.myexodus.data.db.VaultLockedException
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
 * Vault gating (§13.4, refined by the Reliable Partner Event Delivery plan §4 line 87): the
 * notification posts from background-accessible state when the vault is locked, then the
 * exception propagates and [com.example.myexodus.accountability.delivery.PartnerMailboxReceiver]
 * stores a PENDING receipt — the record migrates after the normal foreground unlock (the
 * retry runs with `postNotification = false`, so the alert is never posted twice), never from
 * a background unlock of its own.
 */
class PartnerRuleChangeProjection(
    private val storeFactory: AppRulesStoreFactory,
    private val enrollmentDao: EnrollmentDao,
    private val notify: (
        pairId: String,
        partnerName: String,
        change: RuleChange,
        identity: RuleIdentity?,
        eventKey: String,
        /** The wire's semantic occurrence instant (`occurredAtMinute`), for the delayed marker. */
        occurredAtMs: Long,
    ) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {

    suspend fun onDelivered(
        document: PartnerMailboxDocument,
        plaintext: ByteArray,
        postNotification: Boolean = true,
    ) {
        if (document.kind != PartnerEventKind.RULE_CHANGE_ATTEMPT.wire) return
        val pairId = document.pairId ?: return
        val eventId = document.eventId ?: return
        val decoded = PartnerRuleChangePayload.decodeOrNull(plaintext) ?: return
        val store = try {
            storeFactory.open()
        } catch (e: VaultLockedException) {
            if (postNotification) postAlert(pairId, eventId, decoded)
            throw e
        } ?: throw IllegalStateException("rule change projection needs the local store")

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
                ruleSchedule = decoded.schedule?.encode(),
                rulePreviousSchedule = decoded.previousSchedule?.encode(),
                occurredAtMinute = decoded.occurredAtMinute,
                receivedAtMs = nowMs,
                expiresAtMs = nowMs + PartnerEventJournal.RETENTION_MILLIS,
            ),
        )
        if (inserted == -1L) return
        if (postNotification) postAlert(pairId, eventId, decoded)
    }

    private suspend fun postAlert(
        pairId: String,
        eventId: String,
        decoded: PartnerRuleChangePayload.Decoded,
    ) {
        val enrollment = enrollmentDao.getByPairId(pairId) ?: return
        // A failed post must not stall the mailbox watermark: the feed row is the source of
        // truth and a retry would find the insert above already stored.
        runCatching {
            notify(
                pairId, displayNameOf(enrollment), decoded.change, decoded.identity, eventId,
                decoded.occurredAtMinute * 60_000L,
            )
        }.onFailure { Log.w(TAG, "rule change notification failed pairId=$pairId", it) }
    }

    private companion object {
        const val TAG = "MyExodusRuleControl"
    }
}
