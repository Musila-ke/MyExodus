package com.example.myexodus.accountability.sync

import android.util.Log
import com.example.myexodus.accountability.serialization.EventPayload
import com.example.myexodus.accountability.serialization.SignedEnvelope
import com.example.myexodus.accountability.state.ChainStateStore
import java.security.PublicKey
import java.util.Base64

/**
 * One mailbox document's receive pipeline (§7 item 10 / §8 Step 9, plan §6 step 7). The order
 * below is load-bearing, straight from the plan:
 *
 *  1. AD-10b dedup FIRST — a messageId already in `processed_events` is a no-op before any
 *     fetch ([load] is never invoked) and before any mutation or notification;
 *  2. missing document (already cleaned up, or an invalid wake id) -> no-op;
 *  3. a document carrying this device's own senderKeyId is skipped — it could never legally
 *     appear in this mailbox (senders write to the *partner's* mailbox), and appending it
 *     would corrupt our own chain namespace;
 *  4. verify-then-decrypt via [process]. A failed signature is logged as SIGNATURE_INVALID,
 *     never acked, and never marked processed — the message stays subject to re-delivery
 *     rather than being silently dropped; an epoch outside the accepted window is
 *     UNKNOWN_KEY_EPOCH (§8 Step 15) with the same not-acked/no-apply handling;
 *  5. the chain commit via [append] carries the AD-10b marker in the same DB transaction;
 *  6. consumed messages are acked (AD-18b — the cleanup job waits on every installation's
 *     ack), and only a newly-appended event renders the generic notification;
 *  7. an appended `sync_request` answers the partner's repair ask ([respondToRequest], Step
 *     11); a verification failure while that partner has a repair in flight is the AD-08
 *     tamper signal ([flagTamperIfRepairing]) rather than ordinary noise — replays are
 *     byte-identical re-sends, so an active repair is the only available discriminator.
 *
 * `deletion_request` is the one exception to step 5's append-then-ack order (§8 Step 13 /
 * AD-13b): its dedicated path verifies, shreds, and only then acks — appending and marking
 * it processed would both be wasted on a wiped pair, and an ack that preceded the shred (or
 * a processed marker a crash could strand) would let the initiator's device claim a remote
 * erase that never happened. Chain position is deliberately ignored — the signature is what
 * authenticates a deletion intent; the ack is written by the shred path itself
 * ([onDeletionRequest]), never by this class.
 *
 * Nothing here throws: a mailbox full of malformed documents must never crash the sync run.
 */
class MailboxMessageHandler(
    private val processed: suspend (messageId: String) -> Boolean,
    private val process: (partnerSigningKey: PublicKey, keyForEpoch: (Int) -> ByteArray?, envelopeBytes: ByteArray) -> MailboxEventProcessor.EnvelopeResult,
    private val append: suspend (pairId: String, messageId: String, signed: SignedEnvelope, atEpochMillis: Long) -> ChainStateStore.AppendResult,
    /** Writes the AD-18b ack for [messageId] from [recipientUid]'s device. */
    private val ack: suspend (recipientUid: String, messageId: String) -> Unit,
    /** §10/AD-13b receive half: shred this device's pair data for a verified deletion request.
     *  True = the local erase completed (the handler then owes nothing more; the ack rode the
     *  same call); false = it failed and the message must stay unacked and re-deliverable. */
    private val onDeletionRequest: suspend (pairId: String, messageId: String) -> Boolean = { _, _ -> false },
    private val notify: (payloadType: String) -> Unit = {},
    /** Step 11 responder: enqueue a replay of our chain after the requested anchor. */
    private val respondToRequest: suspend (pairId: String, expectedPreviousSequence: Long, expectedPreviousHash: ByteArray) -> Unit = { _, _, _ -> },
    /** Step 11 tamper correlation: true when a repair was in flight and is now blocked. */
    private val flagTamperIfRepairing: suspend (pairId: String) -> Boolean = { false },
    /** Fired only on [Kind.APPENDED] — the feature flows that need the decoded payload plus
     *  the mailbox document's server timestamp (Step 13 approvals anchor expiry on it, AD-16). */
    private val onAppended: suspend (pairId: String, senderKeyId: String, sequence: Long, payload: EventPayload, serverTsEpochMillis: Long?) -> Unit = { _, _, _, _, _ -> },
    private val now: () -> Long = System::currentTimeMillis,
) {

    enum class Kind {
        /** Already in `processed_events` (AD-10b) — no fetch, no-op. */
        ALREADY_PROCESSED,

        /** Mailbox document gone (cleanup/cap) or the wake id addresses nothing. */
        MISSING,

        /** Our own chain-scoping sender id — never ours to apply. */
        SELF,

        /** Document carried no usable payload (malformed by a non-conforming writer). */
        NO_PAYLOAD,

        /** Signature verification failed (SIGNATURE_INVALID §10) — not acked, retryable. */
        BAD_SIGNATURE,

        /** Signature failed while a repair for this partner was in flight — §9 row 458. */
        TAMPER_SUSPECTED,

        /** No acceptable session key for the envelope's epoch (§8 Step 15) — logged, never
         *  applied, never acked (the event was not incorporated), never called tamper. */
        UNKNOWN_KEY_EPOCH,

        /** Malformed/undecryptable envelope or plaintext. */
        CORRUPT,

        /** Stored and the chain head advanced. */
        APPENDED,

        /** Already part of the chain (another transport delivered it first). */
        DUPLICATE,

        /** Buffered as PENDING_GAP; repair (item 11) resolves it. */
        GAP,

        /** Chain flagged FORKED — blocked, never acked. */
        FORK,

        /** Structurally invalid at this chain position. */
        REJECTED,

        /** Verified `deletion_request`: the pair was shredded and the ack written (§10). */
        DELETION_REQUESTED,

        /** Verified `deletion_request` whose local shred failed — unacked, re-deliverable. */
        DELETION_FAILED,
    }

    data class Result(val kind: Kind, val payloadType: String? = null)

    suspend fun handle(
        pairId: String,
        recipientUid: String,
        mySenderKeyId: String,
        partnerSigningKey: PublicKey,
        keyForEpoch: (Int) -> ByteArray?,
        messageId: String,
        load: suspend () -> MailboxDocument?,
    ): Result {
        if (processed(messageId)) return Result(Kind.ALREADY_PROCESSED)

        val doc = load() ?: return Result(Kind.MISSING)
        if (doc.senderId == mySenderKeyId) return Result(Kind.SELF)

        val bytes = doc.payloadBase64
            ?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
            ?: return Result(Kind.NO_PAYLOAD)

        return when (val outcome = process(partnerSigningKey, keyForEpoch, bytes)) {
            is MailboxEventProcessor.EnvelopeResult.InvalidSignature -> {
                Log.w(TAG, "SIGNATURE_INVALID messageId=$messageId pairId=$pairId")
                val tampered = runCatching { flagTamperIfRepairing(pairId) }
                    .onFailure { Log.w(TAG, "tamper flag failed pairId=$pairId", it) }
                    .getOrDefault(false)
                Result(if (tampered) Kind.TAMPER_SUSPECTED else Kind.BAD_SIGNATURE)
            }

            is MailboxEventProcessor.EnvelopeResult.UnknownKeyEpoch -> {
                Log.w(TAG, "UNKNOWN_KEY_EPOCH messageId=$messageId pairId=$pairId")
                Result(Kind.UNKNOWN_KEY_EPOCH)
            }

            is MailboxEventProcessor.EnvelopeResult.Corrupt -> {
                Log.w(TAG, "CORRUPT messageId=$messageId pairId=$pairId")
                Result(Kind.CORRUPT)
            }

            is MailboxEventProcessor.EnvelopeResult.Valid -> {
                if (outcome.payloadType == EventPayload.TYPE_DELETION_REQUEST) {
                    val shredded = runCatching { onDeletionRequest(pairId, messageId) }
                        .onFailure { Log.w(TAG, "deletion shred failed messageId=$messageId pairId=$pairId", it) }
                        .getOrDefault(false)
                    if (!shredded) Log.w(TAG, "DELETION_FAILED messageId=$messageId pairId=$pairId")
                    return Result(
                        if (shredded) Kind.DELETION_REQUESTED else Kind.DELETION_FAILED,
                        outcome.payloadType,
                    )
                }
                val kind = when (append(pairId, messageId, outcome.signed, now())) {
                    ChainStateStore.AppendResult.Appended -> Kind.APPENDED
                    ChainStateStore.AppendResult.Duplicate -> Kind.DUPLICATE
                    ChainStateStore.AppendResult.Gap -> Kind.GAP
                    ChainStateStore.AppendResult.Fork -> Kind.FORK
                    ChainStateStore.AppendResult.Rejected -> Kind.REJECTED
                }
                if (kind in ACKED_KINDS) {
                    runCatching { ack(recipientUid, messageId) }
                        .onFailure { Log.w(TAG, "ack failed messageId=$messageId", it) }
                }
                if (kind == Kind.APPENDED) {
                    runCatching {
                        onAppended(
                            pairId,
                            outcome.signed.unsigned.senderKeyId,
                            outcome.signed.unsigned.sequence,
                            outcome.payload,
                            doc.timestampEpochMillis,
                        )
                    }.onFailure { Log.w(TAG, "append hook failed messageId=$messageId", it) }
                    if (outcome.payloadType == EventPayload.TYPE_SYNC_REQUEST) {
                        answerRepairRequest(pairId, messageId, outcome.payload)
                    } else {
                        runCatching { notify(outcome.payloadType) }
                            .onFailure { Log.w(TAG, "notify failed messageId=$messageId", it) }
                    }
                }
                Result(kind, outcome.payloadType)
            }
        }
    }

    /** AD-08b: reply only to a request that carries both anchor fields; a request without them
     *  is malformed by the sender and cannot be answered provably, so it is a logged no-op. */
    private suspend fun answerRepairRequest(pairId: String, messageId: String, payload: EventPayload) {
        val sequence = payload.expectedPreviousSequence
        val hash = payload.expectedPreviousHash
        if (sequence == null || hash == null) {
            Log.w(TAG, "sync_request without anchor fields messageId=$messageId pairId=$pairId")
            return
        }
        runCatching { respondToRequest(pairId, sequence, hash) }
            .onFailure { Log.w(TAG, "repair response failed messageId=$messageId", it) }
    }

    companion object {
        private const val TAG = "MyExodusMailboxHandler"

        /** Consumed messages. FORK/REJECTED are excluded: the event was not incorporated, so
         *  it stays un-acked (bounded by the 30-day cap) and retryable. */
        private val ACKED_KINDS = setOf(Kind.APPENDED, Kind.DUPLICATE, Kind.GAP)
    }
}

/**
 * A `mailboxes/{recipientUid}/messages/{messageId}` document, decoded to the fields the
 * pipeline needs. Every field is nullable-tolerant: a document written by a non-conforming
 * client is data, not a contract.
 */
data class MailboxDocument(
    val messageId: String,
    val senderId: String?,
    val payloadBase64: String?,
    val timestampEpochMillis: Long?,
)
