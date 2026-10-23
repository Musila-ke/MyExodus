package com.example.myexodus.accountability.sync

import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.myexodus.accountability.enrollment.EnrollmentDao
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Plan §8 Step 7 / Step 9 (client side) / AD-11: the FCM receiver. Every push is a generic
 * wake signal (§8.12: `{v:"1", type:"SYNC_AVAILABLE", messageId?}`) — never content — so
 * this service never renders a partner event itself; it hands the optional messageId to the
 * sync pipeline and lets the local processing path (dedup, verify, decrypt, notify) own
 * everything downstream. A wake with no messageId still runs the pass, which is how in-app
 * sync stays current when a visible notification is muted or never shown.
 *
 * The one exception (SOS §8.17/FR-20): a `partner_nudge` push IS rendered here as a local
 * notification posted immediately — the payload carries no content to protect, and a nudge
 * must alert now, not at the next work run. Since D16 (owner ruling 2026-10-23) the push is
 * data-only and carries the pair id; this phone resolves its own pairing label for that pair
 * and names the sender, keeping the generic copy when no label resolves. The card itself
 * still comes from Firestore via Home's listener.
 *
 * All other callbacks enqueue the same one-time partner-sync work rather than doing work
 * inline: the service's execution window is short and the FCM wake is only a latency
 * optimization on top of the 15-minute cadence — a Doze-deferred run is late, not lost.
 * `onDeletedMessages` enqueues the same watermark-reconciling pass (plan §8 Step 9's
 * `triggerFullMailboxReconciliation` — the pass IS the reconciliation, so no separate
 * sync-required flag is persisted), and a wake's messageId rides into the work as input
 * data so the pull can handle that exact message first.
 */
@AndroidEntryPoint
class PartnerMessagingService : FirebaseMessagingService() {

    // Field injection never runs for the directly constructed service in the instrumented
    // test seam; partnerNameFor() treats that as "no local label" and posts the generic copy.
    @Inject lateinit var enrollmentDao: EnrollmentDao

    override fun onMessageReceived(message: RemoteMessage) {
        // SOS §8.17/§6.7: a partner_nudge push is rendered here and now — one local
        // notification, nothing else: no Firestore reads, no decryption, no network from this
        // callback (the Home listener renders the card independently). The only read is the
        // bounded local-label lookup below (D16). An invalid nudge_id is ignored, never
        // posted, never synced; an unusable pair id only costs the name.
        if (message.data[PartnerNudgePush.TYPE_KEY] == PartnerNudgePush.TYPE_PARTNER_NUDGE) {
            val nudgeId = PartnerNudgePush.validNudgeId(message.data[PartnerNudgePush.NUDGE_ID_KEY])
            if (nudgeId == null) {
                Log.w(TAG, "partner_nudge push without a valid nudge_id — ignored")
            } else {
                val partnerName = partnerNameFor(
                    PartnerNudgePush.validPairId(message.data[PartnerNudgePush.PAIR_ID_KEY]),
                )
                // The label itself is never logged (telemetry hygiene: no uids, names, pairs).
                Log.i(TAG, "nudge push — posting " + if (partnerName != null) "named" else "generic")
                PartnerNotifications.notifyPartnerNudge(this, nudgeId, partnerName)
            }
            return
        }
        when (val wake = PartnerPushParser.parse(message.data)) {
            is PartnerPushWake.Sync -> {
                if (wake.messageId != null) {
                    Log.i(TAG, "wake for ${wake.messageId}")
                } else {
                    Log.i(TAG, "generic wake — full sync pass")
                }
                enqueueSync(wake.messageId)
            }
            // §8.12/§4: the server's reminder carries fixed OS copy and no event; there is
            // nothing to pull. It still proves the push path works in the send metrics.
            PartnerPushWake.CheckinReminder ->
                Log.i(TAG, "check-in reminder shown by the OS — nothing to sync")
            PartnerPushWake.Ignored ->
                Log.i(TAG, "ignoring unrecognized push (from ${message.from})")
        }
    }

    /** Token rotation: the new token must be registered or push dies silently (AD-18c).
     *  The sync pass includes registration, so the hop is the same as every other wake. */
    override fun onNewToken(token: String) {
        Log.i(TAG, "FCM token rotated — re-registering via sync pass")
        enqueueSync(null)
    }

    /** FCM dropped queued messages (device offline past its buffer, app force-stopped for
     *  long stretches): the mailbox, not the push channel, is the source of truth — run the
     *  sync pass to pick up whatever the push path missed. */
    override fun onDeletedMessages() {
        Log.w(TAG, "FCM deleted queued messages — reconciling")
        enqueueSync(null)
    }

    /** D16: this phone's own name for the pair — a single-row local read, bounded so a busy or
     *  broken DB can never hold the FCM callback open. Null on every failure path (no pair
     *  row, uninitialized test seam, storage error): the post keeps the generic copy. */
    private fun partnerNameFor(pairId: String?): String? {
        if (pairId == null || !::enrollmentDao.isInitialized) return null
        return runCatching {
            runBlocking {
                withTimeoutOrNull(LABEL_LOOKUP_TIMEOUT_MS) { enrollmentDao.getByPairId(pairId) }
            }
        }.getOrNull()?.let(::displayNameOf)
    }

    private fun enqueueSync(messageId: String?) {
        val data = messageId?.let { workDataOf(PARTNER_WAKE_MESSAGE_ID to it) }
        val request = OneTimeWorkRequestBuilder<PartnerSyncWorker>()
            .apply { if (data != null) setInputData(data) }
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork(WAKE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    private companion object {
        const val TAG = "MyExodusPush"
        // Distinct from the periodic work's name ("partner-sync" in MyExodusApp): sharing a
        // unique name would let KEEP suppress the wake while periodic work is enqueued.
        const val WAKE_WORK_NAME = "partner-sync-wake"
        const val LABEL_LOOKUP_TIMEOUT_MS = 1_000L
    }
}
