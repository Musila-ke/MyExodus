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
 * Plan §8 Step 7 (client side) / AD-11: the FCM receiver. The relay sends a data-only,
 * generic wake signal (`{messageId, type:"domain-event"}`) — never content, never a
 * notification block — so this service never renders anything itself; it hands the
 * messageId to the sync pipeline and lets the local processing path (dedup, verify,
 * decrypt, notify) own everything downstream.
 *
 * All three callbacks enqueue the same one-time partner-sync work rather than doing work
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
        val messageId = message.data[PARTNER_WAKE_MESSAGE_ID]
        if (messageId.isNullOrBlank()) {
            // Not our wake shape (or a console test send) — nothing actionable.
            Log.i(TAG, "ignoring push without messageId (from ${message.from})")
            return
        }
        Log.i(TAG, "wake for $messageId")
        enqueueSync(messageId)
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
