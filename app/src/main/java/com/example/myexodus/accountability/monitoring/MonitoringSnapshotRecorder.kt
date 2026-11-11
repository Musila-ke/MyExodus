package com.example.myexodus.accountability.monitoring

import android.util.Log
import com.example.myexodus.accountability.serialization.AccountabilityEventCategory
import com.example.myexodus.accountability.serialization.AccountabilityEventWire
import com.example.myexodus.accountability.serialization.MonitoringCapability
import com.example.myexodus.accountability.serialization.MonitoringState
import com.example.myexodus.accountability.serialization.OutboxAgeBucket
import com.example.myexodus.accountability.sync.PartnerSyncSignals
import com.example.myexodus.accountability.state.AccountabilityDatabase
import com.example.myexodus.blocking.ProtectionAuthority
import com.example.myexodus.data.db.AppRulesStore
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.entity.PartnerEventJournal
import com.example.myexodus.data.db.entity.hasLiveWebsiteRule
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What this device can truthfully claim about its own monitoring right now (plan §4.7/§8.13).
 * [Confirmed] exists only when the enforcement FGS is alive in this process AND its status
 * monitor refreshed inside the freshness window — an independent check-in must never report
 * ACTIVE from a guess (§8.13 line 470).
 */
sealed interface MonitoringLiveness {
    /** The FGS is running and its authority was computed within the freshness window. */
    data class Confirmed(val authority: ProtectionAuthority) : MonitoringLiveness

    /** No FGS in this process (never started, stopped, or killed). */
    data object ServiceAbsent : MonitoringLiveness

    /** The FGS is alive but its stamp is stale — alive is not the same as observing. */
    data object Unverifiable : MonitoringLiveness
}

/** The wire state for a liveness reading — the only place the two vocabularies meet. */
fun MonitoringLiveness.monitoringState(): MonitoringState = when (this) {
    is MonitoringLiveness.Confirmed -> when (authority) {
        ProtectionAuthority.FULL -> MonitoringState.ACTIVE
        ProtectionAuthority.DEGRADED_POLLING_ONLY -> MonitoringState.DEGRADED
        ProtectionAuthority.DEGRADED_NO_DETECTION -> MonitoringState.UNAVAILABLE
    }
    MonitoringLiveness.ServiceAbsent -> MonitoringState.UNAVAILABLE
    MonitoringLiveness.Unverifiable -> MonitoringState.UNKNOWN
}

/**
 * §7 Step 7 sender half: journals an E2EE monitoring snapshot when this device's monitoring
 * state changes and on an hourly check-in, then updates the change-debounce row. The journal
 * row is the durable record; the per-pair envelope is built at reconcile
 * ([com.example.myexodus.accountability.sync.PartnerSyncCoordinator]), where the pair's
 * consent and enable watermark are checked.
 *
 * Debounce: [SNAPSHOT_INTERVAL_MILLIS] mirrors the plan's "during eligible check-ins" — a
 * steady device journals once an hour, a changing one journals immediately. [force] is for
 * the check-ins the plan names explicitly (service start, material change).
 */
class MonitoringSnapshotRecorder(
    private val storeFactory: AppRulesStoreFactory,
    private val outbox: AccountabilityDatabase,
    private val liveness: () -> MonitoringLiveness,
    /** Grants held right now; null when any probe failed (the snapshot then claims nothing). */
    private val capabilities: () -> Set<MonitoringCapability>?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    /** Last observed grant set — the change-detection base (guarded by [mutex]). */
    private var lastCapabilities: Set<MonitoringCapability>? = null

    /** True when a journal row was written. */
    suspend fun record(force: Boolean = false): Boolean = mutex.withLock {
        val store = storeFactory.open() ?: return@withLock false
        val dao = store.partnerLivenessDao()
        val previous = dao.checkin()
        val reading = liveness()
        // Grants are only claimed from a confirmed live reading; an absent or stale service
        // says nothing about permissions, and must not read as "all revoked".
        val caps = if (reading is MonitoringLiveness.Confirmed) capabilities() else null
        val nowMs = now()
        val state = protectionState(reading, caps, store, nowMs)
        val changed = previous?.lastState != state.wire
        val capsChanged = caps != null && caps != lastCapabilities
        val due = nowMs - (previous?.lastRecordedAtMs ?: 0L) >= SNAPSHOT_INTERVAL_MILLIS
        if (!force && previous != null && !changed && !capsChanged && !due) return@withLock false

        val consented = runCatching {
            store.pairSharingDao().profileAll().any { it.shareAvailability }
        }.getOrDefault(false)
        var journaled = false
        if (consented) {
            journaled = runCatching {
                store.partnerEventJournalDao().insert(
                    PartnerEventJournal.forEvent(
                        eventId = AccountabilityEventWire.newEventId(),
                        category = categoryFor(previous?.lastState, state, capsLost(caps)),
                        occurredAtMinute = AccountabilityEventWire.minuteOf(nowMs),
                        createdAtMs = nowMs,
                        monitoringState = state,
                        outboxAgeBucket = outboxAgeBucket(nowMs),
                        capabilities = caps,
                    ),
                )
            }.onFailure { Log.w(TAG, "monitoring snapshot journal failed", it) }
                .isSuccess
        }
        // The observation base advances with the debounce even without consent (or on a
        // failed insert): otherwise every later tick replays the same grant change.
        if (caps != null) lastCapabilities = caps
        // Owner 2026-11-02: a journaled snapshot is partner-visible — deliver it now, not on
        // the next 15-min pass. Same kick as every other shared write.
        if (journaled) PartnerSyncSignals.activityEmitted.tryEmit(Unit)
        // The debounce row advances even when no pair is consented: the state was still
        // observed, and an enable must not replay "changed" for a stale transition.
        runCatching {
            dao.insertCheckinIfAbsent()
            dao.updateLastSnapshot(state.wire, nowMs)
        }.onFailure { Log.w(TAG, "monitoring check-in row update failed", it) }
        journaled
    }

    /**
     * Owner 2026-11-11: the partner pill reports protection quality, not only detection
     * authority. Accessibility alive holds authority FULL, but a missing essential grant
     * (usage, overlay, or VPN consent while a website rule is live) leaves blocking partial —
     * the same verdict the user side computes in BlockingReadiness.limitedBeyondAuthority.
     */
    private suspend fun protectionState(
        reading: MonitoringLiveness,
        caps: Set<MonitoringCapability>?,
        store: AppRulesStore,
        nowMs: Long,
    ): MonitoringState {
        val base = reading.monitoringState()
        if (base != MonitoringState.ACTIVE || caps == null) return base
        val essentialGap = MonitoringCapability.USAGE_ACCESS !in caps ||
            MonitoringCapability.OVERLAY !in caps ||
            (MonitoringCapability.WEBSITE_FILTER !in caps && hasLiveWebsiteRuleAt(store, nowMs))
        return if (essentialGap) MonitoringState.DEGRADED else base
    }

    /** An unreadable rules table claims nothing: a VPN gap degrades only when a website rule
     *  is known to need the tunnel. */
    private suspend fun hasLiveWebsiteRuleAt(
        store: AppRulesStore,
        nowMs: Long,
    ): Boolean = runCatching {
        hasLiveWebsiteRule(store.restrictionRuleDao().getActiveRules(), nowMs)
    }.getOrDefault(false)

    /** A null oldest pending row means nothing is owed — the freshest possible outbox. */
    private suspend fun outboxAgeBucket(nowMs: Long): OutboxAgeBucket {
        val oldest = try {
            outbox.outboxDao().oldestPendingCreatedAt()
        } catch (e: Exception) {
            Log.w(TAG, "outbox age read failed", e)
            return OutboxAgeBucket.UNKNOWN
        } ?: return OutboxAgeBucket.UNDER_1H
        val age = nowMs - oldest
        return when {
            age < HOUR_MILLIS -> OutboxAgeBucket.UNDER_1H
            age < 6 * HOUR_MILLIS -> OutboxAgeBucket.ONE_TO_SIX_HOURS
            age < 24 * HOUR_MILLIS -> OutboxAgeBucket.SIX_TO_TWENTY_FOUR_HOURS
            else -> OutboxAgeBucket.OVER_TWENTY_FOUR_HOURS
        }
    }

    private fun capsLost(current: Set<MonitoringCapability>?): Boolean {
        val previous = lastCapabilities ?: return false
        return current != null && previous - current != emptySet<MonitoringCapability>()
    }

    /**
     * The category coarse-labels *why* this snapshot exists: the first or a steady periodic
     * one is device status; a transition into ACTIVE is a permission/component change, and a
     * grant disappearing under an unchanged authority is the same kind of change. Receivers
     * render the state field; the category is the notification-copy hint.
     */
    private fun categoryFor(
        previousState: String?,
        state: MonitoringState,
        capsLost: Boolean,
    ): AccountabilityEventCategory =
        when {
            capsLost -> AccountabilityEventCategory.MONITORING_PERMISSION_CHANGED
            previousState == null -> AccountabilityEventCategory.DEVICE_STATUS_CHANGED
            previousState == state.wire -> AccountabilityEventCategory.DEVICE_STATUS_CHANGED
            state == MonitoringState.ACTIVE -> AccountabilityEventCategory.MONITORING_PERMISSION_CHANGED
            else -> AccountabilityEventCategory.MONITORING_COMPONENT_UNAVAILABLE
        }

    companion object {
        private const val TAG = "MyExodusMonitoring"
        private const val HOUR_MILLIS = 60L * 60 * 1000

        /** Plan §4.7 "during eligible check-ins": one steady-state snapshot an hour. */
        const val SNAPSHOT_INTERVAL_MILLIS = HOUR_MILLIS
    }
}
