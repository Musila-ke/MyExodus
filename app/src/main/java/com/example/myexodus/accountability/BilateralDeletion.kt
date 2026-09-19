package com.example.myexodus.accountability

import android.util.Log
import com.example.myexodus.accountability.enrollment.EnrollmentDao
import com.example.myexodus.accountability.serialization.EventPayload
import com.example.myexodus.accountability.state.ChainStateStore
import com.example.myexodus.accountability.state.Outbox
import com.example.myexodus.accountability.state.UnpairState
import com.example.myexodus.accountability.state.UnpairStateDao
import com.example.myexodus.accountability.sync.MailboxOutboxDrainer
import java.security.Signature
import java.util.UUID

/**
 * §10 bilateral crypto-shredding + AD-13a/b unpair protocol.
 *
 * **Initiator** ([initiate], then [resumePending] across later passes): the journal row is
 * written first, before the request event exists, pinned to the message id the event will
 * carry — a crash past that point cannot skip the shred, because the row (not the event) is
 * what later passes resume from. The request is then signed, encrypted, chained, and drained
 * while the keys still live; the local shred (agreement-key destruction + pair wipe) runs
 * *unconditionally and immediately* — never gated on the partner, and re-run by [resumePending]
 * whenever it finds a row whose shred never finished (AD-13b). Delivery and the partner's confirmation are tracked in
 * the [UnpairState] journal, which survives the wipe: the request's exact bytes are journaled
 * so a post-shred re-delivery needs no chain state, and the partner's AD-18b ack on that
 * message is the ONLY evidence this device accepts for "their copy is unrecoverable"
 * (REMOTE_SHRED_CONFIRMED -> TERMINAL). No ack within 7 days -> LOCAL_ONLY_TERMINATED, and the
 * UI must say the partner's erase could not be confirmed — delivery is never presented as
 * erasure (§17 line 655).
 *
 * **Responder** ([handleIncoming], invoked from the mailbox receive path after signature
 * verification): journal first (so a crash mid-shred still leaves an accurate record and an
 * owed ack), shred, then write the ack. The ack lands only after the shred completed, so it
 * cannot over-claim; a failed ack write is retried from the journal on later passes — the
 * responder's enrollment is already gone, so nothing else would ever retry it.
 *
 * Revocation: the pair document's status is flipped to `terminated` (the rules pin members +
 * active->terminated; the registerPairing function turns `terminated` into an AD-07 re-pair on
 * the next pairing). It is written once the request is out (or at the 7-day timeout — an
 * undelivered unpair must not leave the credential writable forever). The initiator of a
 * mutual unpair simply loses the race: a denied terminate is a no-op.
 *
 * Destruction is device-level (one agreement key serves all pairs), so shredding one pair
 * renders every pair's ciphertext unrecoverable — per §10.
 *
 * All boundary collaborators are function-typed seams (same pattern as the rotate lambda in
 * IdentityMigration), so the initiate/deliver/confirm ordering is unit-testable with fakes.
 */
class BilateralDeletion(
    private val creator: EventCreator,
    private val drainOutbox: suspend (pairId: String, authorUid: String, partnerUid: String) -> MailboxOutboxDrainer.DrainResult,
    private val enrollmentDao: EnrollmentDao,
    private val store: ChainStateStore,
    private val journal: UnpairStateDao,
    private val destroyAgreementKey: () -> Unit,
    /** Flips `/pairings/{pairId}` status to `terminated`; already-terminated / no longer a
     *  member is a no-op, not a failure (the partner may have terminated first). */
    private val terminatePair: suspend (pairId: String) -> Unit,
    /** Writes the journaled request bytes to the partner's mailbox at the journaled messageId,
     *  with the journaled document metadata (sender key id + key epoch).
     *  True = the document is there (a fresh write, ALREADY_EXISTS, or a denied replay — the
     *  drainer's documented ambiguity; either way re-trying is pointless and the confirmation
     *  evidence is the ack, never this flag). */
    private val deliverRequest: suspend (partnerUid: String, row: UnpairState) -> Boolean,
    /** True when the partner's device left an AD-18b ack on [messageId] (the AD-13b evidence). */
    private val pollPartnerAck: suspend (partnerUid: String, messageId: String) -> Boolean,
    /** Writes this device's own AD-18b ack for the deletion message it received. */
    private val ackReceived: suspend (messageId: String) -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) {

    sealed interface DeletionResult {
        /** Local shred complete (unconditional, AD-13b); delivery + confirmation continue via
         *  [resumePending] and are reported by the journal-backed UI state. */
        data object Deleted : DeletionResult

        data class Failed(val reason: String) : DeletionResult
    }

    /**
     * Initiator half. The journal row is written BEFORE the request event exists (pinned to
     * the id the event will carry), so a crash anywhere past that point — pre-delivery, mid-
     * shred — still finds a row that resumes the protocol and, above all, cannot skip the
     * local shred (AD-13b's "unconditional"). Re-entering with a row already in place
     * continues that attempt rather than starting a second one.
     */
    suspend fun initiate(
        pairId: String,
        authorUid: String,
        partnerUid: String,
        signature: Signature,
    ): DeletionResult {
        val existing = journal.get(pairId)

        // The request already exists on the wire (the journal's envelope is its creation's
        // record) — this tap just continues the protocol.
        if (existing != null && existing.role == UnpairState.ROLE_REQUESTER &&
            existing.requestEnvelope.isNotEmpty()
        ) {
            shredIfPending(existing)
            resumeRequester(pairId)
            return DeletionResult.Deleted
        }

        // The partner's request already arrived (crash mid-shred on the responder path):
        // never clobber their row — this tap stands in for finishing it.
        if (existing != null && existing.role == UnpairState.ROLE_RESPONDER) {
            shredIfPending(existing)
            resumeResponder(pairId)
            return DeletionResult.Deleted
        }

        val messageId = existing?.messageId?.takeIf { it.isNotEmpty() }
            ?: UUID.randomUUID().toString()
        if (runCatching { store.outboxSnapshot(messageId) }.getOrNull() == null) {
            journal.upsert(
                UnpairState(
                    pairId = pairId,
                    role = UnpairState.ROLE_REQUESTER,
                    partnerUid = partnerUid,
                    messageId = messageId,
                    delivered = false,
                    ackSent = false,
                    requestedAtEpochMillis = existing?.requestedAtEpochMillis ?: now(),
                ),
            )
            val created = creator.create(
                pairId,
                EventPayload(type = EventPayload.TYPE_DELETION_REQUEST, text = ""),
                signature,
                messageId,
            )
            if (created is EventCreator.CreateResult.Failed) {
                // Nothing happened: the row (and any earlier crash's remnant) goes with it.
                journal.delete(pairId)
                return DeletionResult.Failed(created.reason)
            }
        }

        // A crashed earlier attempt can leave the row without the event, or the event
        // without the row's fill: the outbox row is the arbiter of both. Absent entirely,
        // the empty fill still resumes honestly — no delivery, so the timeout, not a claim.
        val snapshot = runCatching { store.outboxSnapshot(messageId) }.getOrNull()
        journal.upsert(
            UnpairState(
                pairId = pairId,
                role = UnpairState.ROLE_REQUESTER,
                partnerUid = partnerUid,
                messageId = messageId,
                senderKeyId = snapshot?.senderKeyId,
                keyEpoch = snapshot?.epoch ?: 0,
                requestEnvelope = snapshot?.envelopeBytes ?: ByteArray(0),
                delivered = existing?.delivered ?: false,
                ackSent = false,
                localShredded = existing?.localShredded ?: false,
                requestedAtEpochMillis = existing?.requestedAtEpochMillis ?: now(),
            ),
        )

        drainAllPending(pairId, authorUid, partnerUid)
        if (snapshot != null && store.outboxSnapshot(messageId)?.status == Outbox.STATUS_SENT) {
            journal.markDelivered(pairId)
        }

        // AD-13b: unconditional and immediate — no drain outcome blocks the shred.
        destroyAgreementKey()
        wipePair(pairId)
        journal.markShredded(pairId)

        resumeRequester(pairId)
        return DeletionResult.Deleted
    }

    /** AD-13b crash repair: a journaled unpair whose shred never completed must still erase —
     *  re-running is idempotent (one device-level key destroy + an idempotent pair wipe). */
    private suspend fun shredIfPending(row: UnpairState) {
        if (row.localShredded) return
        destroyAgreementKey()
        wipePair(row.pairId)
        journal.markShredded(row.pairId)
    }

    /**
     * Responder half: a verified DELETION_REQUEST arrived. Returns true when the local shred
     * completed (the ack may still be owed — [resumePending] retries it); false when the shred
     * itself failed, leaving no ack (the request stays subject to re-delivery).
     */
    suspend fun handleIncoming(pairId: String, messageId: String): Boolean {
        journal.upsert(
            UnpairState(
                pairId = pairId,
                role = UnpairState.ROLE_RESPONDER,
                partnerUid = null, // never persisted for the responder — the ack path needs only our own uid
                messageId = messageId,
                requestEnvelope = ByteArray(0),
                delivered = true, // the request's arrival IS its delivery
                ackSent = false,
                requestedAtEpochMillis = now(),
            ),
        )
        try {
            destroyAgreementKey()
            wipePair(pairId)
            journal.markShredded(pairId)
        } catch (e: Exception) {
            Log.w(TAG, "incoming unpair shred failed pairId=$pairId — request stays unacked", e)
            return false
        }
        resumeResponder(pairId)
        return true
    }

    /**
     * The per-pass continuation of every in-flight unpair, run by the sync coordinator BEFORE
     * its enrollment early-return (a post-shred device has no enrollment, yet its unpair is
     * exactly what still needs network work: re-delivery, the owed ack, the ack poll, the
     * 7-day timeout).
     */
    suspend fun resumePending() {
        for (row in runCatching { journal.all() }.getOrDefault(emptyList())) {
            runCatching {
                // Re-paired: the same two devices produce the same pairId (the epoch, not the
                // id, distinguishes sessions — AD-07), so a live enrollment for an already-
                // shredded row means this row describes a superseded unpair. An UNshredded
                // row with a live pair is the opposite case — a crash mid-initiate — and its
                // shred is still owed before anything else runs.
                if (row.localShredded && enrollmentDao.getByPairId(row.pairId) != null) {
                    journal.delete(row.pairId)
                    return@runCatching
                }
                shredIfPending(row)
                if (row.role == UnpairState.ROLE_RESPONDER) resumeResponder(row.pairId) else resumeRequester(row.pairId)
            }.onFailure { Log.w(TAG, "unpair resume failed pairId=${row.pairId}", it) }
        }
    }

    /** Deletes the journal row behind the terminal card — guarded by [UnpairState.isDismissible]
     *  so an early dismissal can never strand the initiator's confirmation. */
    suspend fun dismiss(pairId: String) {
        val row = journal.get(pairId) ?: return
        if (!row.isDismissible(now())) return
        journal.delete(pairId)
    }

    /** The initiator's continuation. The ack is polled first: it proves the partner processed
     *  the request no matter what the delivery bookkeeping believes (a crash between the
     *  mailbox write and the SENT mark is indistinguishable from a denial). */
    private suspend fun resumeRequester(pairId: String) {
        val row = journal.get(pairId) ?: return
        val partnerUid = row.partnerUid ?: return

        if (row.confirmedAtEpochMillis == null && row.messageId.isNotEmpty() &&
            runCatching { pollPartnerAck(partnerUid, row.messageId) }.getOrDefault(false)
        ) {
            journal.markConfirmed(pairId, now())
            terminate(pairId)
            return
        }

        var delivered = row.delivered
        if (!delivered && row.messageId.isNotEmpty() && row.requestEnvelope.isNotEmpty()) {
            if (runCatching { deliverRequest(partnerUid, row) }.getOrDefault(false)) {
                journal.markDelivered(pairId)
                delivered = true
            }
        }
        if (delivered || now() - row.requestedAtEpochMillis >= UnpairState.CONFIRM_TIMEOUT_MILLIS) {
            terminate(pairId)
        }
    }

    private suspend fun resumeResponder(pairId: String) {
        val row = journal.get(pairId) ?: return
        if (row.ackSent) return
        if (runCatching { ackReceived(row.messageId) }.getOrDefault(false)) {
            journal.markAckSent(pairId)
            terminate(pairId)
        }
    }

    /** Drains until the outbox is empty, not just until the request itself is sent: the wipe
     *  deletes pending outbox rows, so anything not delivered first is lost forever. Bounded:
     *  Req 27 caps the chain at 1000 events, so 40 batches (50+ each) is headroom. */
    private suspend fun drainAllPending(pairId: String, authorUid: String, partnerUid: String) {
        for (batch in 0 until MAX_DRAIN_BATCHES) {
            when (val drained = runCatching { drainOutbox(pairId, authorUid, partnerUid) }.getOrNull()) {
                is MailboxOutboxDrainer.DrainResult.Sent -> if (drained.eventsDelivered == 0) break
                // Denied/failed: stop this pass; [resumePending] retries the request itself.
                null, is MailboxOutboxDrainer.DrainResult.Failed,
                is MailboxOutboxDrainer.DrainResult.PermissionDenied -> break
            }
        }
    }

    private suspend fun terminate(pairId: String) {
        runCatching { terminatePair(pairId) }
            .onFailure { Log.w(TAG, "pair termination failed pairId=$pairId (retried next pass)", it) }
    }

    private suspend fun wipePair(pairId: String) {
        store.deletePairData(pairId)
        enrollmentDao.getByPairId(pairId)?.let { enrollmentDao.delete(it) }
    }

    companion object {
        const val TAG = "MyExodusDeletion"

        /** Drain cap: 40 batches x 50 events covers 2x the Req 27 chain cap of 1000. */
        const val MAX_DRAIN_BATCHES = 40
    }
}
