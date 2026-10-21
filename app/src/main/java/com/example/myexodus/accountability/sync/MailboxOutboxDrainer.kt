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
 * Failure semantics (§9 row 1, §8.5): a failed write leaves the row PENDING and
 * [recordAttempt] persists the attempt count, the error code, and the next due time from
 * [OutboxBackoff] — the 15-minute [PartnerSyncWorker] cadence re-runs the drain, and the due
 * filter skips rows still backing off. `PERMISSION_DENIED` is *also* left pending (deviating
 * from v1's terminal reading): the realistic causes are a pairing that is not active *yet*
 * and a replay of an already-delivered message (a crash between the write and the SENT mark —
 * under the rules' create-only gate that replay reads as a denied update, indistinguishable
 * from either other cause). Retrying is the only option that never drops a message; §8.7
 * retention (30 days) bounds the queue, and Step 10 cancels the relationship's PENDING rows
 * ([com.example.myexodus.accountability.state.ChainStateStore.cancelUnsentEnvelopes]) before
 * the unpair wipe clears them — so a permanently-rejected row cannot outlive its relationship.
 * Every row is re-authorized immediately before its write ([stillAuthorized], §8.2): a
 * revocation that commits after the batch read can never deliver what the loop already read.
 *
 * Local bookkeeping writes ([markSent], [recordAttempt]) are best-effort: a Room/SQLCipher
 * hiccup (disk full, locked DB) is logged, never thrown — [drain] always returns a
 * [DrainResult], so a local-DB failure can never skip the coordinator's pull or a sibling
 * pair's sync (both documented step-isolated). A row whose SENT mark was lost self-heals:
 * the next pass re-drains into ALREADY_EXISTS and marks it then (doc id = message id).
 */
class MailboxOutboxDrainer(
    private val pendingRows: suspend (pairId: String, nowEpochMillis: Long, limit: Int) -> List<PendingOutboxRow>,
    /** §8.2 pre-upload check: the pair is still enrolled and this row is still PENDING —
     *  false whenever a revocation raced the batch read. Read in one transaction by the
     *  production wiring ([com.example.myexodus.accountability.state.ChainStateStore.stillAuthorized]). */
    private val stillAuthorized: suspend (pairId: String, messageId: String) -> Boolean,
    private val markSent: suspend (messageId: String) -> Unit,
    private val recordAttempt: suspend (
        messageId: String,
        atEpochMillis: Long,
        nextAttemptAtEpochMillis: Long,
        errorCode: String?,
    ) -> Unit,
    /** §8.7 retention sweep: PENDING rows past their window become EXPIRED and lose their
     *  ciphertext — the delivery state is recorded, never a silent delete. Returns the count
     *  so the drain log can report an eviction (the Step 7 monitoring snapshot surfaces it
     *  durably). */
    private val expireDue: suspend (nowEpochMillis: Long) -> Int,
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
        val drainStartedAt = now()
        bookkeeping("expiry sweep", "all rows") {
            val evicted = expireDue(drainStartedAt)
            if (evicted > 0) {
                Log.w(TAG, "§8.7 retention evicted $evicted PENDING outbox row(s)")
            }
        }
        val rows = try {
            pendingRows(pairId, drainStartedAt, BATCH)
        } catch (e: Exception) {
            Log.w(TAG, "outbox read failed", e)
            return DrainResult.Failed(e.message ?: "outbox read failed")
        }
        var sent = 0
        for (row in rows) {
            // Step 10 §8.2: authorization is rechecked immediately before each write. The
            // batch read can predate a revocation by milliseconds; on an unknown/denied
            // recheck the row is skipped, never sent.
            val authorized = try {
                stillAuthorized(pairId, row.messageId)
            } catch (e: Exception) {
                Log.w(TAG, "authorization recheck failed for ${row.messageId} — not sending", e)
                false
            }
            if (!authorized) continue
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
                bookkeeping("attempt log", row.messageId) {
                    recordFailure(row, e.code.name, startedAt)
                }
                return if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                    Log.w(TAG, "mailbox write denied (${row.messageId}) — left pending")
                    DrainResult.PermissionDenied
                } else {
                    DrainResult.Failed(e.message ?: "mailbox write failed")
                }
            } catch (e: Exception) {
                bookkeeping("attempt log", row.messageId) {
                    recordFailure(row, TRANSPORT_ERROR, startedAt)
                }
                return DrainResult.Failed(e.message ?: "mailbox write failed")
            }
            bookkeeping("mark SENT", row.messageId) { markSent(row.messageId) }
            sent++
        }
        return DrainResult.Sent(sent)
    }

    /** §8.5: persists the failed attempt, its closed error code, and the backed-off next due
     *  time. [startedAt] is the attempt's start, so the delay counts from when the write was
     *  actually tried. */
    private suspend fun recordFailure(row: PendingOutboxRow, errorCode: String, startedAt: Long) {
        recordAttempt(
            row.messageId,
            startedAt,
            OutboxBackoff.nextAttemptAt(row.attemptCount + 1, startedAt, row.messageId),
            errorCode,
        )
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

        /** Closed code for a non-Firestore transport failure (offline, TLS, DNS) — the same
         *  vocabulary as the Firestore code names, never an exception message. */
        const val TRANSPORT_ERROR = "TRANSPORT"
    }
}
