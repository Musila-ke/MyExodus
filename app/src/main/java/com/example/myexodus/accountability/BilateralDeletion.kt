package com.example.myexodus.accountability

import android.util.Log
import com.example.myexodus.accountability.enrollment.EnrollmentDao
import com.example.myexodus.accountability.serialization.EventPayload
import com.example.myexodus.accountability.state.ChainStateStore
import com.example.myexodus.accountability.state.UnpairState
import com.example.myexodus.accountability.state.UnpairStateDao
import java.security.Signature
import java.util.UUID

/**
 * §10 bilateral crypto-shredding + AD-13a/b unpair protocol.
 *
 * **Initiator** ([initiate], then [resumePending] across later passes): the journal row is
 * written first, before the request event exists, pinned to the message id the event will
 * carry — a crash past that point cannot skip the shred, because the row (not the event) is
 * what later passes resume from. The request is then signed, encrypted, and chained while the
 * keys still live, and delivered post-shred from its journaled bytes; the local shred
 * (agreement-key destruction + pair wipe) runs *unconditionally and immediately* — never gated
 * on the partner, and re-run by [resumePending]
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
 * Revocation (Step 10): ending a pair is an authorization change, so it takes effect locally
 * first — every PENDING outbox envelope is `CANCELLED_BY_REVOCATION` and the enrollment falls
 * in the same wipe, then the pair document's status is flipped to `terminated` (the rules pin
 * members + active->terminated; registerPairing turns `terminated` into an AD-07 re-pair).
 * The flip is retried until it is acknowledged, and `revoked_at` journals the acknowledgement:
 * while it is absent the UI reports "revocation pending" rather than pretending the server has
 * revoked. A terminate denied because the partner got there first is an acknowledgement too —
 * the relationship is already terminated (a mutual unpair simply loses the race).
 *
 * Destruction is PAIR-scoped (§19.5 / ADR #106): each enrollment carries its own agreement
 * key alias, so shredding one pair destroys only that key and the other pairs keep working.
 * Pre-v12 enrollments carry no alias and share the device-level key (one key served all old
 * pairs); that key cannot be destroyed per-pair, so it dies with the LAST enrollment — which
 * is also what keeps the AD-22 wipe canary honest (after a full unpair no pairing key remains
 * to mistake for a wiped database).
 *
 * All boundary collaborators are function-typed seams (same pattern as the rotate lambda in
 * IdentityMigration), so the initiate/deliver/confirm ordering is unit-testable with fakes.
 */
class BilateralDeletion(
    private val creator: EventCreator,
    private val enrollmentDao: EnrollmentDao,
    private val store: ChainStateStore,
    private val journal: UnpairStateDao,
    /** Destroys the pair's own agreement key (the enrollment's alias); failures propagate so
     *  the journal row stays unshredded and the erase is honestly retried, never claimed. */
    private val destroyPairAgreementKey: (alias: String) -> Unit,
    /** Destroys the legacy device-level agreement key — only ever called once no enrollment
     *  remains (it is the shared key of every pre-v12 pair, and the AD-22 wipe canary). */
    private val destroyDeviceAgreementKey: () -> Unit,
    /** Flips `/pairings/{pairId}` status to `terminated`. True = the server-side relationship
     *  exit is acknowledged: the write landed, or the pair is already terminated / no longer
     *  accessible to this account (the partner may have terminated first — an exit either way).
     *  False = not acknowledged (offline, attestation, or the pair still reads active) — the
     *  caller journals nothing and retries next pass. */
    private val terminatePair: suspend (pairId: String) -> Boolean,
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
    /** Clears the pair's partner-view rows from the rules store (projections, plans, sharing
     *  consent). They outlive ChainStateStore.wipePair, and AD-07's deterministic pairId would
     *  otherwise show the ended session's data under a re-pair. Best effort: the chain wipe is
     *  the authorization boundary; a miss self-heals via the orphan sweep while unenrolled. */
    private val prunePartnerActivity: suspend (pairId: String) -> Unit = {},
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
     *
     * A null [authorUid]/[partnerUid] (ADR #114) is §9's "partner unreachable" row: the pair
     * document is gone or unreadable by this account, so no request can ever be delivered —
     * no event is created, the row keeps an empty message id and envelope, and the local
     * shred still runs. The 7-day window lapses to LOCAL_ONLY_TERMINATED, never a claim.
     */
    suspend fun initiate(
        pairId: String,
        authorUid: String?,
        partnerUid: String?,
        signature: Signature,
    ): DeletionResult {
        // Superseded row: a live enrollment for an already-shredded pair means the row
        // describes an earlier session (re-pair reuses the pairId — AD-07). Drop it — the
        // same cleanup [resumePending] applies — so the tap acts on the live pair, not the
        // old row.
        val stored = journal.get(pairId)
        val superseded = stored != null && stored.localShredded &&
            enrollmentDao.getByPairId(pairId) != null
        if (superseded) journal.delete(pairId)
        val existing = if (superseded) null else stored

        // A row that already represents a finished (or crashed) attempt continues — never
        // restarts and never dies. Three shapes qualify: the request is already on the wire
        // (the envelope is its creation's record), the partner's request already arrived
        // (never clobber a responder row), or the enrollment is already gone (the shred ran;
        // the row is its only report, so a stray re-tap must not delete it).
        if (existing != null && (
                existing.requestEnvelope.isNotEmpty() ||
                    existing.role == UnpairState.ROLE_RESPONDER ||
                    enrollmentDao.getByPairId(pairId) == null
                )
        ) {
            shredIfPending(existing)
            if (existing.role == UnpairState.ROLE_RESPONDER) resumeResponder(pairId)
            else resumeRequester(pairId)
            return DeletionResult.Deleted
        }

        // Nothing to erase and nothing to notify: the tap is for a pair this device never
        // held — fail it cleanly.
        if (enrollmentDao.getByPairId(pairId) == null) {
            journal.delete(pairId)
            return DeletionResult.Failed("not enrolled")
        }

        val reachable = authorUid != null && partnerUid != null
        val messageId = existing?.messageId?.takeIf { it.isNotEmpty() }
            ?: if (reachable) UUID.randomUUID().toString() else ""
        if (reachable && runCatching { store.outboxSnapshot(messageId) }.getOrNull() == null) {
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
            // Best effort by design: the request is the partner's notification, never a
            // precondition for erasing locally (AD-13b). A chain that refuses an append
            // (gap/fork) — or a crypto-dead pair whose key is already gone (the 2026-09-28
            // multi-pair incident) — must not trap the user in a partnership they are ending.
            // Without an event the journal keeps an empty envelope: no delivery, so the 7-day
            // timeout reports LOCAL_ONLY_TERMINATED instead of claiming the partner erased.
            val created = runCatching {
                creator.create(
                    pairId,
                    EventPayload(type = EventPayload.TYPE_DELETION_REQUEST, text = ""),
                    signature,
                    messageId,
                )
            }.getOrElse { e ->
                Log.w(TAG, "deletion request not created — the local shred runs regardless", e)
                null
            }
            if (created is EventCreator.CreateResult.Failed) {
                Log.w(TAG, "deletion request not chained (${created.reason}) — the local shred runs regardless")
            }
        }

        // A crashed earlier attempt can leave the row without the event, or the event
        // without the row's fill: the outbox row is the arbiter of both. Absent entirely,
        // the empty fill still resumes honestly — no delivery, so the timeout, not a claim.
        val snapshot = if (messageId.isEmpty()) null
        else runCatching { store.outboxSnapshot(messageId) }.getOrNull()
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

        // Step 10: revocation cancels every unsent envelope — the queued events are NOT
        // rerouted and NOT flushed first. Only the deletion request itself is delivered, from
        // the journaled bytes above, by [resumeRequester].
        shredPair(pairId)

        resumeRequester(pairId)
        return DeletionResult.Deleted
    }

    /** AD-13b crash repair: a journaled unpair whose shred never completed must still erase —
     *  re-running is idempotent (a pair-key destroy + an idempotent pair wipe). */
    private suspend fun shredIfPending(row: UnpairState) {
        if (row.localShredded) return
        shredPair(row.pairId)
    }

    /**
     * §10 + §19.5 (ADR #106): erase exactly one pair. Its own ECDH key is destroyed first (the
     * shredding lever — a failure here propagates so the journal row stays unshredded and the
     * erase is retried, never claimed), then its rows, then the journal is marked. The
     * device-level key — the shared key of every pre-v12 pair, and the AD-22 wipe canary —
     * dies only with the last enrollment: destroying it mid-life would make the remaining
     * legacy pairs undecryptable (the 2026-09-28 defect), and for the last enrollment it must
     * die before the wipe or a crash between the steps reads as a local wipe.
     */
    private suspend fun shredPair(pairId: String) {
        // Step 10 §8.4: authorization ends before delivery does. Cancelling first means any
        // drain that already read these rows cannot send them (it rechecks inside the write
        // lock), and nothing queued for the departing partner leaves after this point.
        store.cancelUnsentEnvelopes(pairId)
        val record = enrollmentDao.getByPairId(pairId)
        if (record?.myAgreementKeyAlias != null) destroyPairAgreementKey(record.myAgreementKeyAlias)
        // The device-level key dies BEFORE the last enrollment's wipe: the AD-22 canary reads
        // *device key present + no enrollment* as a local wipe, so destroying it after the wipe
        // would turn a crash between the two steps into a false "Pairing data was lost" report.
        if (record != null && enrollmentDao.all().size == 1) destroyDeviceAgreementKey()
        // Enrollment row and pair rows fall together, enrollment first (Step 10 §8.2): the
        // local authorization record is the thing every write path checks.
        store.wipePair(pairId)
        // The partner-view rows live in the rules store, not the chain store — without this
        // the ended session's data survives into a re-pair (same pairId, AD-07). Best effort:
        // the authorization boundary is already down.
        runCatching { prunePartnerActivity(pairId) }
            .onFailure { Log.w(TAG, "partner activity prune failed for $pairId", it) }
        // Crash-resume where the wipe already ran (record == null): nothing left to derive
        // from, so the shared key must still go.
        if (enrollmentDao.all().isEmpty()) destroyDeviceAgreementKey()
        journal.markShredded(pairId)
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
            shredPair(pairId)
        } catch (e: Exception) {
            Log.w(TAG, "incoming unpair shred failed pairId=$pairId — request stays unacked", e)
            return false
        }
        resumeResponder(pairId)
        return true
    }

    /**
     * OPD #43: the partner's account was deleted and the server terminated this pair. This
     * device runs the responder half without a mailbox message — the deletion request is the
     * pair document's own `terminationReason` (the E2EE keys died with the account, so no
     * mailbox payload for that event can exist; plan §17's server-originated notices live in
     * `system_events`). Journal first, then shred; no AD-18b ack is owed because no message
     * was received ([UnpairState.isDismissible] treats the empty message id as nothing to ack).
     * Returns false when the shred failed — the enrollment survives, so the next pass retries.
     */
    suspend fun handleAccountDeleted(pairId: String): Boolean {
        if (enrollmentDao.getByPairId(pairId) == null) return true
        journal.upsert(
            UnpairState(
                pairId = pairId,
                role = UnpairState.ROLE_RESPONDER,
                partnerUid = null,
                messageId = "",
                delivered = true,
                ackSent = false,
                requestedAtEpochMillis = now(),
            ),
        )
        return try {
            shredPair(pairId)
            true
        } catch (e: Exception) {
            Log.w(TAG, "account-deleted shred failed pairId=$pairId — retried next pass", e)
            false
        }
    }

    /**
     * The per-pass continuation of every in-flight unpair, run by the sync coordinator BEFORE
     * its enrollment early-return (a post-shred device has no enrollment, yet its unpair is
     * exactly what still needs network work: re-delivery, the owed ack, the ack poll, the
     * 7-day timeout).
     *
     * Returns true when any journal row changed — the coordinator carries it into the pass
     * report so one-shot screens (the unpair card, the Partner dashboard) re-read in real
     * time instead of staring at the pre-advance state (owner UX ruling 2026-10-01).
     */
    suspend fun resumePending(): Boolean {
        val before = runCatching { journal.all() }.getOrDefault(emptyList())
        for (row in before) {
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
        val after = runCatching { journal.all() }.getOrDefault(emptyList())
        // Compare the row columns this function mutates — a data-class equals would compare
        // requestEnvelope by array reference and report every re-read as a change.
        return before.map { it.progressSignature() } != after.map { it.progressSignature() }
    }

    private fun UnpairState.progressSignature(): String =
        "$pairId|$role|$delivered|$ackSent|$localShredded|$confirmedAtEpochMillis|$revokedAtEpochMillis"

    /** Deletes the journal row behind the terminal card — guarded by [UnpairState.isDismissible]
     *  so an early dismissal can never strand the initiator's confirmation. */
    suspend fun dismiss(pairId: String) {
        val row = journal.get(pairId) ?: return
        if (!row.isDismissible(now())) return
        journal.delete(pairId)
    }

    /** The initiator's continuation. The ack is polled first: it proves the partner processed
     *  the request no matter what the delivery bookkeeping believes (a crash between the
     *  mailbox write and the SENT mark is indistinguishable from a denial). A row with no
     *  partner uid (ADR #114, unreachable) has no poll and no delivery to attempt — it still
     *  reaches the 7-day timeout, so the revocation attempt rides along and the terminal
     *  state is reported. */
    private suspend fun resumeRequester(pairId: String) {
        val row = journal.get(pairId) ?: return
        val partnerUid = row.partnerUid
        var confirmed = row.confirmedAtEpochMillis != null
        var delivered = row.delivered

        if (!confirmed && partnerUid != null && row.messageId.isNotEmpty() &&
            runCatching { pollPartnerAck(partnerUid, row.messageId) }.getOrDefault(false)
        ) {
            journal.markConfirmed(pairId, now())
            confirmed = true
        }

        // A confirmed request needs no delivery (the ack is proof they processed it), and a
        // delivered one is never re-sent — the journal keeps the bytes only for the retry
        // window between shred and first successful write.
        if (!confirmed && !delivered && partnerUid != null &&
            row.messageId.isNotEmpty() && row.requestEnvelope.isNotEmpty() &&
            runCatching { deliverRequest(partnerUid, row) }.getOrDefault(false)
        ) {
            journal.markDelivered(pairId)
            delivered = true
        }

        // Step 10: the server-side revocation is retried until acknowledged. Delivered = the
        // attempt is made (offline stays not-delivered, so the UI can honestly say "pending");
        // an undelivered request still flips the pair at the 7-day timeout, so ending a
        // partnership never leaves the credential writable forever. Once acknowledged,
        // [terminate] journals the fact and there is nothing left to retry.
        if (row.revokedAtEpochMillis == null &&
            (confirmed || delivered || now() - row.requestedAtEpochMillis >= UnpairState.CONFIRM_TIMEOUT_MILLIS)
        ) {
            terminate(pairId)
        }
    }

    private suspend fun resumeResponder(pairId: String) {
        val row = journal.get(pairId) ?: return
        if (row.ackSent) return
        // OPD #43: a server-originated termination delivers no mailbox message, so there is
        // nothing to acknowledge — the row is already terminal.
        if (row.messageId.isEmpty()) return
        if (runCatching { ackReceived(row.messageId) }.getOrDefault(false)) {
            journal.markAckSent(pairId)
            terminate(pairId)
        }
    }

    /** Step 10: journals the server-side acknowledgement that ends the relationship. A false
     *  return leaves `revoked_at` null — the UI keeps saying "pending" and the next pass
     *  retries, forever if need be (there is no timeout on revocation itself). */
    private suspend fun terminate(pairId: String) {
        val acknowledged = runCatching { terminatePair(pairId) }
            .onFailure { Log.w(TAG, "pair termination failed pairId=$pairId (retried next pass)", it) }
            .getOrDefault(false)
        if (acknowledged) {
            journal.markRevoked(pairId, now())
        } else {
            Log.w(TAG, "server-side revocation not acknowledged pairId=$pairId (retried next pass)")
        }
    }

    companion object {
        const val TAG = "MyExodusDeletion"
    }
}
