package com.example.myexodus.accountability.sync

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import android.util.Log

/**
 * Plan §8 Step 7 / Step 9 (client side) / AD-11: the FCM receiver. Every push is a generic
 * wake signal (§8.12: `{v:"1", type:"SYNC_AVAILABLE", messageId?}`) — never content — so
 * this service never renders a partner event itself; it hands the optional messageId to the
 * sync pipeline and lets the local processing path (dedup, verify, decrypt, notify) own
 * everything downstream. A wake with no messageId still runs the pass, which is how in-app
 * sync stays current when a visible notification is muted or never shown.
 *
 * The one exception (SOS §8.17/FR-20): a `partner_nudge` push IS rendered here, as a generic
 * local notification posted immediately — the payload carries no content to protect, and a
 * foreground nudge must alert now, not at the next work run. The card itself still comes
 * from Firestore via Home's listener.
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

    override fun onMessageReceived(message: RemoteMessage) {
        // SOS §8.17/§6.7: a partner_nudge push is rendered here and now — a generic local
        // notification, nothing else: no Firestore reads, no decryption, no network from this
        // callback (the Home listener renders the card independently). An invalid nudge_id is
        // ignored, never posted, never synced.
        if (message.data[PartnerNudgePush.TYPE_KEY] == PartnerNudgePush.TYPE_PARTNER_NUDGE) {
            val nudgeId = PartnerNudgePush.validNudgeId(message.data[PartnerNudgePush.NUDGE_ID_KEY])
            if (nudgeId == null) {
                Log.w(TAG, "partner_nudge push without a valid nudge_id — ignored")
            } else {
                Log.i(TAG, "nudge push — posting check-in notification")
                PartnerNotifications.notifyPartnerNudge(this, nudgeId)
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
    }
}
