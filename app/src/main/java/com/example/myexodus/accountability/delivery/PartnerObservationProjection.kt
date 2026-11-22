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
import com.example.myexodus.data.db.AppRulesStore
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.entity.PartnerActivityAlert
import com.example.myexodus.data.db.entity.PartnerAttemptAlert
import com.example.myexodus.data.db.entity.PartnerEventJournal
import com.example.myexodus.data.db.entity.PartnerSharingAlert

/**
 * §13.3 Phase 7 recipient half of the auth-free staged transport for everything the rule
 * projection does not own: activity/streak/plan content and blocked attempts
 * (`ACCOUNTABILITY_UPDATE` / `BLOCK_ATTEMPT`) and monitoring snapshots (`MONITORING_UPDATE`). One delivered document becomes exactly the local
 * state (and notification) the legacy chain path produced, so a pair sees the same dashboard
 * whichever transport carried the event.
 *
 * Vault gating (§13.4, refined by the Reliable Partner Event Delivery plan §4 line 87): the
 * notification posts FIRST, from background-accessible state (enrollment rows + strings never
 * need the vault) — App Lock must never silence the tray. Only then does the state projection
 * open the vault; while it is locked the exception propagates, [PartnerMailboxReceiver] stores
 * a PENDING receipt, and the next pass re-delivers with `notify = false`.
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
    private val now: () -> Long = System::currentTimeMillis,
) {

    suspend fun onDelivered(
        document: PartnerMailboxDocument,
        plaintext: ByteArray,
        notify: Boolean = true,
    ) {
        val pairId = document.pairId ?: return
        when (document.kind) {
            PartnerEventKind.BLOCK_ATTEMPT.wire,
            PartnerEventKind.ACCOUNTABILITY_UPDATE.wire ->
                onAccountabilityUpdate(pairId, document, plaintext, notify)
            PartnerEventKind.MONITORING_UPDATE.wire -> onMonitoringUpdate(pairId, document, plaintext)
            else -> Unit
        }
    }

    private suspend fun onAccountabilityUpdate(
        pairId: String,
        document: PartnerMailboxDocument,
        plaintext: ByteArray,
        notify: Boolean,
    ) {
        val payload = runCatching { decodePayload(plaintext) }.getOrNull() ?: return
        if (notify) notifyEvent(pairId, document, payload)
        val store = storeFactory.open()
            ?: throw IllegalStateException("observation projection needs the local store")
        // Staged attempts write no chain rows, so the dashboard's legacy attempt source never
        // sees them — persist the row the notification announces (Rules & blocks parity).
        if (document.kind == PartnerEventKind.BLOCK_ATTEMPT.wire) {
            persistAttempt(store, pairId, document, payload)
        }
        // Same gap for the consent revokes: no chain row and no text/state, so without this
        // row the notification would be the only surface they ever reach.
        if (payload.type in EventPayload.SHARING_STOPPED_TYPES) {
            persistSharingAlert(store, pairId, document, payload)
        }
        // And the same gap for the two activity kinds the feed renders as history rows: a
        // delivered relapse/milestone reached the tray and the state snapshot only, so the
        // "Recent activity" feed and its per-day chart silently lost them (rig 2026-11-15).
        if (payload.type == EventPayload.TYPE_RELAPSE_RECORDED ||
            payload.type == EventPayload.TYPE_MILESTONE_REACHED
        ) {
            persistActivityAlert(store, pairId, document, payload)
        }
        if (payload.type == PlanWire.TYPE_PLAN_PUBLISHED || payload.type == PlanWire.TYPE_PLAN_PROGRESS) {
            planReceiver.applyAppended(pairId, payload)
        } else {
            activityReceiver.applyAppended(
                pairId,
                payload,
                PartnerEventProtocol.STAGED_SEQUENCE_BASE + (document.senderSequence ?: 0L),
            )
        }
    }

    /** Posted before the vault opens (see the class note). Same skip list as the legacy path:
     *  approvals own their notifications and plan progress is bookkeeping the author's
     *  checklist repaints from. The document id keys the tray entry, so a burst of events
     *  never collapses into one replace-in-place item. */
    private suspend fun notifyEvent(
        pairId: String,
        document: PartnerMailboxDocument,
        payload: EventPayload,
    ) {
        if (payload.type == EventPayload.TYPE_APPROVAL_REQUEST ||
            payload.type == EventPayload.TYPE_APPROVAL_RESPONSE ||
            payload.type == PlanWire.TYPE_PLAN_PROGRESS
        ) {
            return
        }
        enrollmentDao.getByPairId(pairId)?.let { enrollment ->
            runCatching {
                PartnerNotifications.notifyPartnerEvent(
                    context, pairId, displayNameOf(enrollment), payload,
                    eventKey = document.messageId,
                )
            }.onFailure { Log.w(TAG, "partner event notification failed pairId=$pairId", it) }
        }
    }

    private suspend fun persistAttempt(
        store: AppRulesStore,
        pairId: String,
        document: PartnerMailboxDocument,
        payload: EventPayload,
    ) {
        val eventId = document.eventId ?: return
        runCatching {
            val nowMs = now()
            val dao = store.partnerAttemptAlertDao()
            dao.deleteExpired(nowMs)
            dao.insert(
                PartnerAttemptAlert(
                    pairId = pairId,
                    eventId = eventId,
                    text = payload.text,
                    appLabel = payload.appLabel,
                    // The block instant, never the delivery time: a deferred attempt must
                    // keep its place in the chain relative to newer rule events (owner
                    // report 2026-11-23 — "ruled turned off ... then rule blocked" read
                    // backwards because rows were stamped at delivery).
                    occurredAtMs = document.deviceOccurredAtMs ?: document.createdAtMs,
                    expiresAtMs = nowMs + PartnerEventJournal.RETENTION_MILLIS,
                ),
            )
        }.onFailure { Log.w(TAG, "attempt alert store failed pairId=$pairId", it) }
    }

    private suspend fun persistActivityAlert(
        store: AppRulesStore,
        pairId: String,
        document: PartnerMailboxDocument,
        payload: EventPayload,
    ) {
        val eventId = document.eventId ?: return
        // The feed renders these two kinds from their stateAfter only (alertOf returns null
        // without it); a body that lacks one has no row to restore.
        val state = payload.stateAfter ?: return
        runCatching {
            val nowMs = now()
            val dao = store.partnerActivityAlertDao()
            dao.deleteExpired(nowMs)
            dao.insert(
                PartnerActivityAlert(
                    pairId = pairId,
                    eventId = eventId,
                    kind = payload.type,
                    habitLabel = state.habitLabel,
                    endedDays = state.endedDays,
                    milestoneDay = state.milestoneDay,
                    occurredAtMs = state.eventOccurredAtMs,
                    expiresAtMs = nowMs + PartnerEventJournal.RETENTION_MILLIS,
                ),
            )
        }.onFailure { Log.w(TAG, "activity alert store failed pairId=$pairId", it) }
    }

    private suspend fun persistSharingAlert(
        store: AppRulesStore,
        pairId: String,
        document: PartnerMailboxDocument,
        payload: EventPayload,
    ) {
        val eventId = document.eventId ?: return
        runCatching {
            val nowMs = now()
            val dao = store.partnerSharingAlertDao()
            dao.deleteExpired(nowMs)
            dao.insert(
                PartnerSharingAlert(
                    pairId = pairId,
                    eventId = eventId,
                    kind = payload.type,
                    label = payload.stateAfter?.habitLabel?.takeIf { it.isNotBlank() },
                    occurredAtMs = document.deviceOccurredAtMs ?: document.createdAtMs,
                    expiresAtMs = nowMs + PartnerEventJournal.RETENTION_MILLIS,
                ),
            )
        }.onFailure { Log.w(TAG, "sharing alert store failed pairId=$pairId", it) }
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
