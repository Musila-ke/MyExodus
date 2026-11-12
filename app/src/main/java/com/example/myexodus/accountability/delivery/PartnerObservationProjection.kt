package com.example.myexodus.accountability.delivery

import android.content.Context
import android.util.Log
import com.example.myexodus.accountability.activity.PartnerActivityReceiver
import com.example.myexodus.accountability.enrollment.EnrollmentDao
import com.example.myexodus.accountability.monitoring.MonitoringSnapshotReceiver
import com.example.myexodus.accountability.monitoring.PartnerPermissionLossReporter
import com.example.myexodus.accountability.plan.PartnerPlanReceiver
import com.example.myexodus.accountability.serialization.AccountabilityEventWire
import com.example.myexodus.accountability.serialization.EventPayload
import com.example.myexodus.accountability.serialization.PlanWire
import com.example.myexodus.accountability.serialization.decodePayload
import com.example.myexodus.accountability.sync.PartnerNotifications
import com.example.myexodus.accountability.sync.displayNameOf
import com.example.myexodus.data.db.AppRulesStoreFactory

/**
 * §13.3 Phase 7 recipient half of the auth-free staged transport for everything the rule
 * projection does not own: activity/streak/plan/attempt content (`ACCOUNTABILITY_UPDATE`) and
 * monitoring snapshots (`MONITORING_UPDATE`). One delivered document becomes exactly the local
 * state (and notification) the legacy chain path produced, so a pair sees the same dashboard
 * whichever transport carried the event.
 *
 * Vault gating (§13.4): the projection opens the vault first. While it is locked the exception
 * propagates and [PartnerMailboxReceiver] leaves the document unreceipted, so it is retried
 * after the normal foreground unlock.
 *
 * The staged body cannot know its own sequence (the outbox assigns it after sealing), so the
 * document's `sequence` field stands in through [PartnerEventProtocol.STAGED_SEQUENCE_BASE].
 * Rule-lifecycle kinds are ignored here — [com.example.myexodus.accountability.rulecontrol.PartnerRuleChangeProjection] owns them.
 */
class PartnerObservationProjection(
    private val context: Context,
    private val storeFactory: AppRulesStoreFactory,
    private val enrollmentDao: EnrollmentDao,
    private val activityReceiver: PartnerActivityReceiver,
    private val planReceiver: PartnerPlanReceiver,
    private val monitoringReceiver: MonitoringSnapshotReceiver,
    private val permissionLoss: PartnerPermissionLossReporter,
) {

    suspend fun onDelivered(document: PartnerMailboxDocument, plaintext: ByteArray) {
        val pairId = document.pairId ?: return
        when (document.kind) {
            PartnerEventKind.ACCOUNTABILITY_UPDATE.wire ->
                onAccountabilityUpdate(pairId, document, plaintext)
            PartnerEventKind.MONITORING_UPDATE.wire -> onMonitoringUpdate(pairId, document, plaintext)
            else -> Unit
        }
    }

    private suspend fun onAccountabilityUpdate(
        pairId: String,
        document: PartnerMailboxDocument,
        plaintext: ByteArray,
    ) {
        val payload = runCatching { decodePayload(plaintext) }.getOrNull() ?: return
        storeFactory.open() ?: throw IllegalStateException("observation projection needs the local store")
        if (payload.type == PlanWire.TYPE_PLAN_PUBLISHED || payload.type == PlanWire.TYPE_PLAN_PROGRESS) {
            planReceiver.applyAppended(pairId, payload)
        } else {
            activityReceiver.applyAppended(
                pairId,
                payload,
                PartnerEventProtocol.STAGED_SEQUENCE_BASE + (document.senderSequence ?: 0L),
            )
        }
        // Same skip list as the legacy path: approvals own their notifications and plan
        // progress is bookkeeping the author's checklist repaints from.
        if (payload.type != EventPayload.TYPE_APPROVAL_REQUEST &&
            payload.type != EventPayload.TYPE_APPROVAL_RESPONSE &&
            payload.type != PlanWire.TYPE_PLAN_PROGRESS
        ) {
            enrollmentDao.getByPairId(pairId)?.let { enrollment ->
                runCatching {
                    PartnerNotifications.notifyPartnerEvent(
                        context, pairId, displayNameOf(enrollment), payload,
                    )
                }.onFailure { Log.w(TAG, "partner event notification failed pairId=$pairId", it) }
            }
        }
    }

    private suspend fun onMonitoringUpdate(
        pairId: String,
        document: PartnerMailboxDocument,
        plaintext: ByteArray,
    ) {
        val senderInstallationId = document.senderInstallationId ?: return
        val event = AccountabilityEventWire.decodeOrNull(plaintext) ?: return
        storeFactory.open() ?: throw IllegalStateException("observation projection needs the local store")
        val lost = monitoringReceiver.onEvent(
            pairId = pairId,
            senderKeyId = senderInstallationId,
            event = event,
            sequenceOverride = PartnerEventProtocol.STAGED_SEQUENCE_BASE + (document.senderSequence ?: 0L),
        )
        permissionLoss.report(pairId, event.eventId, event.occurredAtMinute, lost)
    }

    private companion object {
        const val TAG = "MyExodusObservation"
    }
}
