package com.example.myexodus.accountability.sync

import android.util.Log
import com.example.myexodus.accountability.state.PendingOutboxRow
import com.google.firebase.firestore.FirebaseFirestoreException
import java.util.Base64

/**
 * Plan §7 item 9 / §6 data flow steps 3+8 (AD-06b): the v2 outbox drain — reads PENDING
 * outbox rows and writes the **exact stored envelope bytes** (base64, nothing re-serialized)
 * to `/mailboxes/{partnerUid}/messages/{messageId}`, `messageId` as the document id, so a
 * re-drain can never duplicate an entry. A row is marked `SENT` once the Firestore write
 * succeeds — "sent" at this layer means the mailbox write, independent of push delivery
 * (§6 step 8). Mailbox writes are what triggers the relay ([functions/relay.js]).
 *
 * Collaborators are function-typed seams (house pattern, see [PushInstallationRegistrar]) —
 * unit-testable without Firestore. App Check is already installed by the time this runs: the
 * coordinator only drains after [PairUidResolver] (which installs it) resolved the uids.
 *
 * Failure semantics (§9 row 1): a failed write leaves the row PENDING — the 15-minute
 * [PartnerSyncWorker] cadence is the retry, and [recordAttempt] keeps the attempt count
 * observable. `PERMISSION_DENIED` is *also* left pending (deviating from v1's terminal
 * reading): the realistic causes are a pairing that is not active *yet* and a replay of an
 * already-delivered message (a crash between the write and the SENT mark — under the rules'
 * create-only gate that replay reads as a denied update, indistinguishable from either other
 * cause). Retrying is the only option that never drops a message; unpair clears the outbox
 * ([com.example.myexodus.accountability.state.ChainStateStore.deletePairData]), so a
 * permanently-rejected row cannot outlive the pair.
 *
 * Local bookkeeping writes ([markSent], [recordAttempt]) are best-effort: a Room/SQLCipher
 * hiccup (disk full, locked DB) is logged, never thrown — [drain] always returns a
 * [DrainResult], so a local-DB failure can never skip the coordinator's pull or a sibling
 * pair's sync (both documented step-isolated). A row whose SENT mark was lost self-heals:
 * the next pass re-drains into ALREADY_EXISTS and marks it then (doc id = message id).
 */
class MailboxOutboxDrainer(
    private val pendingRows: suspend (pairId: String, limit: Int) -> List<PendingOutboxRow>,
    private val markSent: suspend (messageId: String) -> Unit,
    private val recordAttempt: suspend (messageId: String, atEpochMillis: Long) -> Unit,
    private val write: suspend (partnerUid: String, messageId: String, data: Map<String, Any>) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    /** §14/§11 T1 sample: outbox write attempt → Firestore write confirmed, in millis. Fired
     *  once per successfully written message so the console sees a (bucketed) distribution,
     *  not one blended delivery number. */
    private val onSendLatency: (Long) -> Unit = {},
) {

    sealed interface DrainResult {
        /** Every queued event was delivered and marked SENT. */
        data class Sent(val eventsDelivered: Int) : DrainResult

        /** §9: rules rejected the write — authorization failure, do not retry. */
        data object PermissionDenied : DrainResult

        data class Failed(val reason: String) : DrainResult
    }

    suspend fun drain(
        pairId: String,
        authorUid: String,
        partnerUid: String,
    ): DrainResult {
        val rows = try {
            pendingRows(pairId, BATCH)
        } catch (e: Exception) {
            Log.w(TAG, "outbox read failed", e)
            return DrainResult.Failed(e.message ?: "outbox read failed")
        }
        var sent = 0
        for (row in rows) {
            val data = FirestoreSchema.mailboxMessageData(
                senderId = row.senderKeyId,
                senderUid = authorUid,
                pairId = pairId,
                payloadBase64 = Base64.getEncoder().encodeToString(row.envelopeBytes),
                keyEpoch = row.epoch,
            )
            val startedAt = now()
            try {
                write(partnerUid, row.messageId, data)
                onSendLatency(now() - startedAt)
            } catch (e: FirebaseFirestoreException) {
                if (e.code == FirebaseFirestoreException.Code.ALREADY_EXISTS) {
                    // A prior drain wrote the document but crashed before marking the row.
                    bookkeeping("mark SENT", row.messageId) { markSent(row.messageId) }
                    sent++
                    continue
                }
                bookkeeping("attempt log", row.messageId) { recordAttempt(row.messageId, now()) }
                return if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                    Log.w(TAG, "mailbox write denied (${row.messageId}) — left pending")
                    DrainResult.PermissionDenied
                } else {
                    DrainResult.Failed(e.message ?: "mailbox write failed")
                }
            } catch (e: Exception) {
                bookkeeping("attempt log", row.messageId) { recordAttempt(row.messageId, now()) }
                return DrainResult.Failed(e.message ?: "mailbox write failed")
            }
            bookkeeping("mark SENT", row.messageId) { markSent(row.messageId) }
            sent++
        }
        return DrainResult.Sent(sent)
    }

    /** The local-DB side of the drain must never out-throw the network side (class KDoc):
     *  a lost SENT mark or attempt row self-heals on the next pass, a thrown exception would
     *  skip the coordinator's pull and every remaining pair. */
    private suspend fun bookkeeping(what: String, messageId: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "$what failed for $messageId — self-heals on the next pass", e)
        }
    }

    companion object {
        private const val TAG = "MyExodusPartnerSync"
        private const val BATCH = 100
    }
}
