package com.example.myexodus.accountability.state

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * §8 Step 13 / AD-13a/b: the unpair journal — one row per unpaired pair, surviving the local
 * wipe (the chain tables die with the pairing; this row is what the UI reports from afterwards).
 *
 * Both roles journal: the REQUESTER row is the AD-13b confirmation tracker (`delivered` = the
 * request reached the partner's mailbox, `confirmed_at` = the partner's AD-18b ack proved its
 * device processed the deletion and erased its copy), the RESPONDER row records that this
 * device erased its data on the partner's request and tracks the ack it owes back (`ack_sent`).
 *
 * AD-13b language discipline: the UI may claim remote erasure only from [confirmedAtEpochMillis]
 * — never from [delivered]. Delivery proves the mailbox write, not that the partner executed
 * anything; a delivery-shaped claim would be exactly the overstatement §17 line 655 forbids.
 */
@Entity(tableName = "unpair_state")
data class UnpairState(
    @PrimaryKey @ColumnInfo(name = "pair_id") val pairId: String,
    @ColumnInfo(name = "role") val role: String,
    /** The partner's uid — REQUESTER rows only, for the mailbox write + ack poll. Deliberately
     *  never persisted elsewhere on-device; after the wipe this is the only copy left. */
    @ColumnInfo(name = "partner_uid") val partnerUid: String?,
    /** The deletion request's mailbox document id (the ack-poll address). Empty only in the
     *  unreachable Duplicate-create corner (see [com.example.myexodus.accountability.BilateralDeletion]). */
    @ColumnInfo(name = "message_id") val messageId: String,
    /** The request's mailbox-document metadata: the wipe takes the outbox row (and the
     *  enrollment that held the sender key id), so a post-shred re-delivery reads the exact
     *  stored bytes and their document fields from here. Null on RESPONDER rows. */
    @ColumnInfo(name = "sender_key_id") val senderKeyId: String? = null,
    @ColumnInfo(name = "key_epoch") val keyEpoch: Int = 0,
    @ColumnInfo(name = "request_envelope") val requestEnvelope: ByteArray = ByteArray(0),
    /** REQUESTER: the request envelope is written to the partner's mailbox. RESPONDER: true —
     *  the request's arrival IS the delivery. Gates the revocation write, never a user claim. */
    @ColumnInfo(name = "delivered") val delivered: Boolean,
    /** RESPONDER: this device's AD-18b ack for the received deletion message is written. */
    @ColumnInfo(name = "ack_sent") val ackSent: Boolean,
    /** AD-13b repair flag: the local shred (key destroy + pair wipe) completed. The journal
     *  row is written BEFORE the request event exists, so a crash mid-initiate must still
     *  find its pending row and finish the shred — that is what makes the shred unskippable. */
    @ColumnInfo(name = "local_shredded") val localShredded: Boolean = false,
    @ColumnInfo(name = "requested_at") val requestedAtEpochMillis: Long,
    /** Set when the partner's device ack was observed — THE remote-erasure evidence (AD-13b). */
    @ColumnInfo(name = "confirmed_at") val confirmedAtEpochMillis: Long? = null,
    /** Step 10: when the server acknowledged the relationship exit (`/pairings/{id}` reads
     *  `terminated`, or it was already terminated/gone). Null = server-side revocation is
     *  still pending — the local revocation is in force regardless, and the UI must say so. */
    @ColumnInfo(name = "revoked_at") val revokedAtEpochMillis: Long? = null,
) {

    /** The phase this row reports at [nowEpochMillis] — derived, never stored: the 7-day
     *  timeout must hold even if the app never ran at the exact boundary moment.
     *
     *  [UnpairPhase.AWAITING_REVOCATION] outranks confirmation inside the 7-day window on
     *  purpose: the local exit is real, but until the server has revoked the relationship the
     *  card must not imply the relationship state is settled — and the row must keep driving
     *  the retry. Past 7 days the established honesty boundary wins: the row reports what could
     *  not be confirmed instead of pending forever. */
    fun phaseAt(nowEpochMillis: Long): UnpairPhase {
        val elapsed = nowEpochMillis - requestedAtEpochMillis
        return when {
            role == ROLE_RESPONDER -> UnpairPhase.TERMINAL
            revokedAtEpochMillis == null && elapsed < CONFIRM_TIMEOUT_MILLIS ->
                UnpairPhase.AWAITING_REVOCATION

            confirmedAtEpochMillis != null -> UnpairPhase.TERMINAL
            elapsed >= CONFIRM_TIMEOUT_MILLIS -> UnpairPhase.LOCAL_ONLY_TERMINATED
            else -> UnpairPhase.AWAITING_CONFIRMATION
        }
    }

    /** The terminal card may be dismissed (and the row deleted) only once the wait is over and
     *  — requesters — the server-side revocation is acknowledged: deleting the journal row
     *  earlier would strand the retry that only this row drives. Responder side, the owed ack
     *  is the gate. Single source for the UI read model and the dismissal guard. */
    fun isDismissible(nowEpochMillis: Long): Boolean = when {
        role == ROLE_RESPONDER -> ackSent
        else -> when (phaseAt(nowEpochMillis)) {
            UnpairPhase.TERMINAL, UnpairPhase.LOCAL_ONLY_TERMINATED -> true
            UnpairPhase.AWAITING_CONFIRMATION, UnpairPhase.AWAITING_REVOCATION -> false
        }
    }

    companion object {
        const val ROLE_REQUESTER = "REQUESTER"
        const val ROLE_RESPONDER = "RESPONDER"

        /** AD-13b: no confirmation within 7 days -> LOCAL_ONLY_TERMINATED, reported as such. */
        const val CONFIRM_TIMEOUT_MILLIS = 7L * 24 * 60 * 60 * 1000
    }
}

/**
 * AD-13a's protocol states as the UI distinguishes them (§12 / §17: `TERMINAL` and
 * `LOCAL_ONLY_TERMINATED` "reported distinctly and accurately").
 */
enum class UnpairPhase {
    /** `LOCAL_SHRED_COMPLETE`: this device erased its data; the partner's device has not confirmed. */
    AWAITING_CONFIRMATION,

    /**
     * Step 10: the local revocation is in force (nothing new can be sent or fetched for the
     * relationship), but the server has not yet acknowledged the relationship exit — offline,
     * or the flip has not landed. Reported honestly as pending and retried every pass.
     */
    AWAITING_REVOCATION,

    /** `REMOTE_SHRED_CONFIRMED`: the partner's own device confirmed it erased its copy. */
    TERMINAL,

    /** AD-13b: 7 days, no confirmation — "could not confirm", never an erasure claim. */
    LOCAL_ONLY_TERMINATED,
}
