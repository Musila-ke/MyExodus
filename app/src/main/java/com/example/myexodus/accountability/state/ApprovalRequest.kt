package com.example.myexodus.accountability.state

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * §8 Step 12 disable-approval state machine, persisted per request on BOTH devices:
 *
 * ```
 * REQUESTED → APPROVED | REJECTED | EXPIRED (72h) | CONSUMED
 * ```
 *
 * One row per request event, keyed by the request's canonical event id
 * (`<pairId>:<senderKeyId>:<sequence>` — the envelope's chain position, stable across mailbox
 * re-drains and repair replays, unlike the messageId). The pairId is load-bearing, not cosmetic:
 * the signing key is device-level (shared by every pair) and every pair's chain starts at
 * sequence 1, so a pair-less id collides across pairs on this primary key — the second pair's
 * RESPONDER row would hit the IGNORE path and silently never appear (found on-device 2026-09-29,
 * ADR #120). [role] says which side of the pair this device is
 * on: the REQUESTER row is created when its request event is chained, the RESPONDER row when
 * the request is received and verified. Every transition is a compare-and-set guarded on
 * `status = 'REQUESTED'` (the plan's exactly-once rule), so a replayed or racing response can
 * never move a row twice — §9: rejected responses are logged, not applied.
 *
 * [expiresAtEpochMillis] is the 72h deadline. On the RESPONDER it is anchored on the mailbox
 * document's Firestore server timestamp (AD-16: the security-relevant expiry decision uses the
 * server clock, not the device's); on the requester it is the local wall-clock anchor — display
 * only, since a manipulated requester clock cannot mint a response.
 */
@Entity(tableName = "approval_requests")
data class ApprovalRequest(
    @PrimaryKey @ColumnInfo(name = "request_event_id") val requestEventId: String,
    @ColumnInfo(name = "pair_id") val pairId: String,
    @ColumnInfo(name = "role") val role: String,
    @ColumnInfo(name = "status") val status: String,
    /** The epoch the request was created under — compared against the response's AD-07 epoch. */
    @ColumnInfo(name = "pairing_epoch") val pairingEpoch: Int,
    /** The requester's message to the partner (request only; empty on a response row). */
    @ColumnInfo(name = "message") val message: String,
    @ColumnInfo(name = "created_at") val createdAtEpochMillis: Long,
    @ColumnInfo(name = "expires_at") val expiresAtEpochMillis: Long,
    @ColumnInfo(name = "decided_at") val decidedAtEpochMillis: Long? = null,
) {
    companion object {
        const val ROLE_REQUESTER = "REQUESTER"
        const val ROLE_RESPONDER = "RESPONDER"

        const val STATUS_REQUESTED = "REQUESTED"
        const val STATUS_APPROVED = "APPROVED"
        const val STATUS_REJECTED = "REJECTED"
        const val STATUS_EXPIRED = "EXPIRED"
        const val STATUS_CONSUMED = "CONSUMED"

        /** §8 Step 12: expiry after 72h (server-timestamp-anchored on the responder, AD-16). */
        const val TTL_MILLIS = 72L * 60 * 60 * 1000

        /** Canonical id of the request's chain event — both sides derive it from the envelope.
         *  pairId scopes it: device-level signing keys + per-pair chains starting at sequence 1
         *  make (senderKeyId, sequence) non-unique across a device's pairs. */
        fun eventIdOf(pairId: String, senderKeyId: String, sequence: Long): String =
            "$pairId:$senderKeyId:$sequence"
    }
}
