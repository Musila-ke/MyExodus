package com.example.myexodus.accountability.sync

import android.util.Log
import com.example.myexodus.accountability.state.UncheckedSentRow

/**
 * Plan §8 Step 18 / §9 row 461 (AD-23): the sending client's view of a relay rejection. The
 * sender's mailbox write *succeeds* (the rules allow it and the relay is what decides), so the
 * rejection exists only as the `relayStatus` marker the relay merges onto the sender's own
 * message document — which the author-read rules clause now lets the sender observe. This
 * class is that observation: it reads the marker back for sent rows and records it, so a
 * server-side rate-limit rejection is a distinct state from a network failure (which never
 * reaches this class — it leaves the row `PENDING` for the drainer to retry).
 *
 * Due window (ADR #53): a row is due while never checked, or checked within the last
 * [SETTLE_MILLIS] — a partner wake can start a sync pass seconds after a drain, before the
 * relay has run, so an early "no marker yet" must not be final, and the re-checks make the
 * read-back self-healing. An idle gap past the window finally closes a marker-less row.
 *
 * A failed read (network, permission) leaves the row due for the next pass — this step can
 * never fail the sync it runs in. Collaborators are function-typed seams (house pattern, see
 * [MailboxOutboxDrainer]); nothing here is Firestore- or Room-specific.
 */
class MailboxRejectionChecker(
    private val dueRows: suspend (pairId: String, settleBeforeEpochMillis: Long, limit: Int) -> List<UncheckedSentRow>,
    private val relayStatusOf: suspend (partnerUid: String, messageId: String) -> String?,
    private val stampCheck: suspend (messageId: String, rejection: String?, atEpochMillis: Long) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /** Returns how many rows were found rejected this pass (0 when nothing was due). */
    suspend fun check(partnerUid: String, pairId: String, limit: Int = BATCH): Int {
        val rows = try {
            dueRows(pairId, now() - SETTLE_MILLIS, limit)
        } catch (e: Exception) {
            Log.w(TAG, "rejection-check read failed", e)
            return 0
        }
        var rejected = 0
        for (row in rows) {
            val status = try {
                relayStatusOf(partnerUid, row.messageId)
            } catch (e: Exception) {
                Log.w(TAG, "relay status read failed (${row.messageId}) — left due", e)
                continue // Still due next pass; a network failure is not a rejection.
            }
            stampCheck(row.messageId, status, now())
            if (status != null) {
                Log.w(TAG, "relay rejected ${row.messageId}: $status")
                rejected++
            }
        }
        return rejected
    }

    private companion object {
        const val TAG = "MyExodusPartnerSync"
        const val BATCH = 50

        /** The re-check window (ADR #53), not a one-shot settle: longer than any believable
         *  relay latency, and the horizon past which an idle gap stops a marker-less row from
         *  costing reads. */
        const val SETTLE_MILLIS = 60 * 60 * 1000L
    }
}
