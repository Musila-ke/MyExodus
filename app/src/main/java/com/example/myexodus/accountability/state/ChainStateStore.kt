package com.example.myexodus.accountability.state

import android.util.Log
import androidx.room3.withWriteTransaction
import com.example.myexodus.accountability.serialization.SignedEnvelope
import com.example.myexodus.accountability.serialization.UnsignedEnvelope
import com.example.myexodus.accountability.serialization.encodeSigned
import com.example.myexodus.accountability.serialization.eventHash
import java.util.UUID

/**
 * §7.3 State Manager: hash-chain validation (FR4: continuity + sequence monotonicity),
 * atomic append + head update (§8.3 single-transaction rule), and the transactional outbox.
 *
 * Signature verification is NOT here: it needs the sender's public key, which lives in the
 * enrollment layer (Phase 4). Callers must verify signatures before appendLocal/receiveRemote;
 * the store enforces chain structure, duplicate/fork/gap/expiry semantics (§9).
 */
class ChainStateStore(
    private val db: AccountabilityDatabase,
    /** §14 line 581 telemetry seam: fired once per security-relevant chain transition
     *  ([EVENT_CHAIN_TAMPER_SUSPECTED] / [EVENT_CHAIN_INCOMPLETE]). A function seam, not a
     *  telemetry dependency, so every JVM test constructs the store with the default no-op. */
    private val onSecuritySignal: (String) -> Unit = {},
) {

    sealed interface AppendResult {
        /** Event stored and the chain head advanced. */
        data object Appended : AppendResult

        /** Identical event already stored (QR/relay re-delivery); no-op. */
        data object Duplicate : AppendResult

        /** Sequence not contiguous; stored as PENDING_GAP (§9) pending backfill. */
        data object Gap : AppendResult

        /** Conflicting event; chain flagged FORKED and blocked (§9). */
        data object Fork : AppendResult

        /** Structurally invalid for this chain position (e.g. stale or first-with-previous-hash). */
        data object Rejected : AppendResult

        /**
         * Step 10: the pair has no live local enrollment, so its relationship is no longer
         * authorized. Checked inside the append transaction — a shred that commits between
         * event creation and this call wins, and no orphan chain/outbox row is written.
         */
        data object NotEnrolled : AppendResult
    }

    /** [messageId] lets a caller that must later address this message's mailbox document
     *  (the Step 13 unpair journal) pin the id instead of receiving a store-internal UUID.
     *  [eventId] is the Step 3 wire event id this envelope carries; it becomes the durable
     *  link to the sender's [com.example.myexodus.data.db.entity.PartnerEventJournal] row
     *  and is unique per (relationship, sender install). */
    suspend fun appendLocal(
        pairId: String,
        envelope: SignedEnvelope,
        messageId: String? = null,
        eventId: String? = null,
    ): AppendResult =
        db.withWriteTransaction {
            appendOutcome(pairId, envelope, ChainEvent.DIRECTION_SENT, messageId, eventId)
        }

    /**
     * Remote append with AD-10b dedup: [messageId] (when given) is the mailbox message's
     * document id — the chain append and the `processed_events` marker commit in ONE
     * transaction, so a crash between them can never double-process or lose the marker. A
     * messageId seen before is a no-op duplicate (§6 step 7: check before fetch/verify).
     */
    suspend fun receiveRemote(
        pairId: String,
        envelope: SignedEnvelope,
        messageId: String? = null,
        atEpochMillis: Long = 0L,
        eventId: String? = null,
    ): AppendResult = db.withWriteTransaction {
        if (messageId != null && db.processedEventDao().contains(messageId)) {
            return@withWriteTransaction AppendResult.Duplicate
        }
        val result = appendOutcome(pairId, envelope, ChainEvent.DIRECTION_RECEIVED, eventId = eventId)
        if (messageId != null && result in PROCESSED_OUTCOMES) {
            db.processedEventDao().insert(ProcessedEvent(messageId, atEpochMillis))
        }
        result
    }

    /** Outbox: unsent local events oldest-first (§7.3). */
    suspend fun pendingSentEvents(pairId: String, limit: Int = 100): List<ChainEvent> =
        db.chainEventDao().pendingSentEvents(pairId, limit)

    /** Marks an outbox event as transmitted (sync_state SENT). */
    suspend fun markTransmitted(event: ChainEvent) {
        db.chainEventDao().update(event.copy(syncState = ChainEvent.SYNC_STATE_SENT))
    }

    /** Sequences awaiting repair for [senderKeyId]'s chain, oldest-first (Step 11). */
    suspend fun gapSequences(pairId: String, senderKeyId: String): List<Long> =
        db.chainEventDao().gapSequences(pairId, senderKeyId)

    suspend fun chainFor(pairId: String): List<ChainEvent> = db.chainEventDao().chainFor(pairId)

    /** One stored event by chain position — the Step 11 replay anchor lookup (AD-08b). */
    suspend fun storedEvent(pairId: String, senderKeyId: String, sequence: Long): ChainEvent? =
        db.chainEventDao().getBySequence(pairId, senderKeyId, sequence)

    /** §8.9 (sender_install_id, relationship_id, event_id) uniqueness, read side: has this
     *  wire event already entered THIS sender's log for this pair? The reconcile queues use it
     *  as their "already delivered to this pair" check — the chain row itself is the link
     *  (no separate table that could disagree with it). */
    suspend fun hasEvent(pairId: String, senderKeyId: String, eventId: String): Boolean =
        db.chainEventDao().getByEventId(pairId, senderKeyId, eventId) != null

    /** The sequence [eventId] already occupies on this sender's chain, if any. Activity
     *  reconcile heals a crash between append and its link write through this: the event
     *  exists but the link table does not, and re-signing would deliver it twice. */
    suspend fun sequenceForEvent(pairId: String, senderKeyId: String, eventId: String): Long? =
        db.chainEventDao().getByEventId(pairId, senderKeyId, eventId)?.sequence

    /** This sender's own chain head: the last local event's sequence + hash (single-row read). */
    suspend fun localHead(pairId: String, senderKeyId: String): ChainHead? =
        db.chainHeadDao().get(pairId, senderKeyId)

    /**
     * AD-12 rotation (Step 15): advances this sender's epoch and anchors it at [atEpochMillis]
     * ([ChainHead.keyEpochStartSequence] = the last event of the closing epoch). Returns the
     * new epoch, or null when the head vanished mid-flight (unpair).
     */
    suspend fun advanceKeyEpoch(pairId: String, senderKeyId: String, atEpochMillis: Long): Int? =
        db.withWriteTransaction {
            val head = db.chainHeadDao().get(pairId, senderKeyId) ?: return@withWriteTransaction null
            val next = head.keyEpoch + 1
            db.chainHeadDao().update(
                head.copy(
                    keyEpoch = next,
                    keyEpochStartedAtEpochMillis = atEpochMillis,
                    keyEpochStartSequence = head.lastSequence,
                ),
            )
            next
        }

    /** Heads with a repair in flight or concluded (AD-08) — the coordinator's work list. */
    suspend fun repairHeads(pairId: String): List<ChainHead> = db.chainHeadDao().repairHeads(pairId)

    /** Step 11 requester side: records that a SyncRequest went out at [atEpochMillis]. The
     *  first request anchors the AD-08 30-day recovery window. */
    suspend fun markRepairRequested(pairId: String, senderKeyId: String, atEpochMillis: Long) =
        db.withWriteTransaction {
            val head = db.chainHeadDao().get(pairId, senderKeyId) ?: return@withWriteTransaction
            db.chainHeadDao().update(
                head.copy(
                    repairState = ChainHead.REPAIR_PENDING,
                    repairFirstRequestedAtEpochMillis = head.repairFirstRequestedAtEpochMillis ?: atEpochMillis,
                    repairLastRequestedAtEpochMillis = atEpochMillis,
                ),
            )
        }

    /** AD-08 stage 2: the 30-day window lapsed with no reply — stop requesting, start the
     *  promotion path to the terminal state. */
    suspend fun markRecoveryExhausted(pairId: String, senderKeyId: String, atEpochMillis: Long) =
        db.withWriteTransaction {
            val head = db.chainHeadDao().get(pairId, senderKeyId) ?: return@withWriteTransaction
            db.chainHeadDao().update(
                head.copy(
                    repairState = ChainHead.REPAIR_RECOVERY_EXHAUSTED,
                    repairLastRequestedAtEpochMillis = atEpochMillis,
                ),
            )
        }

    /** AD-08 stage 3 (§9 row 457): neutral terminal — the missing range is gone. Gap rows go
     *  terminal with the head; the chain is never conflated with TAMPER_SUSPECTED. */
    suspend fun markChainIncomplete(pairId: String, senderKeyId: String) = db.withWriteTransaction {
        db.chainEventDao().markGapsChainIncomplete(pairId, senderKeyId)
        val head = db.chainHeadDao().get(pairId, senderKeyId) ?: return@withWriteTransaction
        db.chainHeadDao().update(head.copy(repairState = ChainHead.REPAIR_CHAIN_INCOMPLETE))
        onSecuritySignal(EVENT_CHAIN_INCOMPLETE)
    }

    /**
     * §9 row 458 / §12 line 525: a message from a sender with a repair in flight failed
     * verification — that is the tamper signal (replays are byte-identical re-sends, so an
     * active repair is the only discriminator between a tampered replay and ordinary noise).
     * Sets TAMPER_SUSPECTED and flags the chain forked: further automatic trust is blocked
     * pending manual re-pair. Returns whether a repair was actually in flight (the caller's
     * only way to tell TAMPER_SUSPECTED from a plain SIGNATURE_INVALID).
     */
    suspend fun flagTamperIfRepairing(pairId: String): Boolean = db.withWriteTransaction {
        val heads = db.chainHeadDao().repairHeads(pairId)
            .filter { it.repairState in ChainHead.ACTIVE_REPAIR_STATES }
        for (head in heads) {
            Log.w(TAG, "TAMPER_SUSPECTED: message from pair=${head.pairId} sender=${head.senderKeyId} failed verification during repair")
            db.chainHeadDao().update(head.copy(forked = true, repairState = ChainHead.REPAIR_TAMPER_SUSPECTED))
        }
        if (heads.isNotEmpty()) onSecuritySignal(EVENT_CHAIN_TAMPER_SUSPECTED)
        heads.isNotEmpty()
    }

    /**
     * Step 11 responder side: re-publish this device's own stored events after
     * [afterSequence] as fresh outbox rows — exact stored bytes, new messageId (the byte
     * identity is what lets the requester's chain logic absorb them). Capped by
     * [REPLAY_BATCH_LIMIT]; the requester's 24h re-request cadence covers the remainder.
     */
    suspend fun enqueueReplay(
        pairId: String,
        senderKeyId: String,
        afterSequence: Long,
        limit: Int = REPLAY_BATCH_LIMIT,
    ): Int = db.withWriteTransaction {
        // Step 10: replays are deliveries too — never enqueue them for a revoked relationship.
        if (db.enrollmentDao().getByPairId(pairId) == null) return@withWriteTransaction 0
        val events = db.chainEventDao().sentEventsAfter(pairId, senderKeyId, afterSequence, limit)
        // A replay is a brand-new mailbox document, so its retention window starts now —
        // anchoring expiry to the original creation time would expire old repairs on arrival.
        val now = System.currentTimeMillis()
        for (event in events) {
            db.outboxDao().insert(
                Outbox(
                    messageId = UUID.randomUUID().toString(),
                    eventId = event.id,
                    envelopeBytes = event.envelope,
                    status = Outbox.STATUS_PENDING,
                    createdAtEpochMillis = now,
                    expiresAtEpochMillis = now + Outbox.RETENTION_MILLIS,
                ),
            )
        }
        events.size
    }

    /** Step 13 unpair: one message's outbox snapshot (status + exact bytes + mailbox-document
     *  metadata) — read before the wipe so the journal can outlive the row it describes. */
    suspend fun outboxSnapshot(messageId: String): OutboxRowSnapshot? = db.outboxDao().snapshotOf(messageId)

    /**
     * Step 10 §8.4: revocation removes unsent recipient envelopes. Marks every PENDING outbox
     * row of the pair `CANCELLED_BY_REVOCATION` in one transaction — the rows are then
     * undeliverable and un-rottable (the wipe deletes them moments later; the status exists so
     * a drain racing this transaction can never write what it read before it). Envelopes that
     * are already SENT are untouched: they left this device before authorization ended, and
     * ownership is never rerouted.
     */
    suspend fun cancelUnsentEnvelopes(pairId: String): Int = db.withWriteTransaction {
        db.outboxDao().cancelPendingByPair(pairId)
    }

    /**
     * §8.2 pre-upload recheck for the drainer: the pair still has its enrollment AND this exact
     * message is still PENDING. Read in one transaction so a revocation cannot land between the
     * two reads. False = deliver nothing; the caller leaves the row alone.
     */
    suspend fun stillAuthorized(pairId: String, messageId: String): Boolean = db.withWriteTransaction {
        db.enrollmentDao().getByPairId(pairId) != null && db.outboxDao().isStillPending(messageId) > 0
    }

    /**
     * §10 bilateral deletion: wipes the pair's authorization (its enrollment row) and its
     * chain, head, outbound, and approval rows in ONE transaction — enrollment first, so
     * [appendLocal]'s in-transaction guard observes the revocation no matter which transaction
     * wins the write lock (Step 10). The unpair journal deliberately survives — it is a
     * different table and must outlive the wipe.
     */
    suspend fun wipePair(pairId: String) = db.withWriteTransaction {
        db.enrollmentDao().getByPairId(pairId)?.let { db.enrollmentDao().delete(it) }
        db.outboxDao().deleteByPair(pairId) // before the events delete — the subquery reads events
        db.chainEventDao().deleteByPair(pairId)
        db.chainHeadDao().deleteByPair(pairId)
        db.approvalRequestDao().deleteByPair(pairId)
    }

    /**
     * Plan §8 Step 17 (AD-22): the wipe-detection counterpart of [wipePair] — with no
     * enrollment there is no pairId to scope to, and the pre-wipe wipe (without a shred) is by
     * definition the pair data's last owner. Clears exactly the pair-scoped tables the
     * enrollment would have owned; enrollments (none by detection's premise), the unpair
     * journal (ADR #49: outlives its pair by design) and processed_events (messageId-keyed,
     * pair-less replay protection) all survive.
     */
    suspend fun wipeAllPairData() = db.withWriteTransaction {
        db.outboxDao().deleteAll()
        db.chainEventDao().deleteAll()
        db.chainHeadDao().deleteAll()
        db.approvalRequestDao().deleteAll()
    }

    /** Transaction body — callers wrap it in [db.withWriteTransaction]. */
    private suspend fun appendOutcome(
        pairId: String,
        envelope: SignedEnvelope,
        direction: String,
        messageId: String? = null,
        eventId: String? = null,
    ): AppendResult {
        // §8.2/Step 10: authorization is checked before the envelope exists (EventCreator) and
        // again here, inside the writing transaction — the enrollment row is the local
        // authorization record, and its absence means the relationship was revoked (or never
        // existed). Writes serialize, so a shred that commits first is observed here.
        if (direction == ChainEvent.DIRECTION_SENT && db.enrollmentDao().getByPairId(pairId) == null) {
            return AppendResult.NotEnrolled
        }
        val unsigned = envelope.unsigned
        val sender = unsigned.senderKeyId
        val hash = eventHash(unsigned)
        // §8.9 (sender_install_id, relationship_id, event_id) uniqueness: an event that has
        // already entered this chain is idempotent when identical, and a different envelope
        // claiming the same event id is refused outright (never a fork — the chain itself is
        // not in conflict). The unique index is the multi-process backstop.
        if (eventId != null) {
            val stored = db.chainEventDao().getByEventId(pairId, sender, eventId)
            if (stored != null) {
                return if (stored.eventHash.contentEquals(hash)) AppendResult.Duplicate else AppendResult.Rejected
            }
        }
        val head = db.chainHeadDao().get(pairId, sender)

        if (head == null) {
            return when {
                unsigned.sequence != 1L -> insertGapOrConflict(
                    pairId, envelope, direction, hash, unsigned.sequence, null, eventId,
                )

                unsigned.previousEventHash != null -> AppendResult.Rejected
                else -> {
                    insertRow(pairId, envelope, direction, hash, ChainEvent.SYNC_STATE_PENDING, messageId, eventId)
                    db.chainHeadDao().insert(
                        ChainHead(
                            pairId = pairId,
                            senderKeyId = sender,
                            lastSequence = 1,
                            lastEventHash = hash,
                            // Step 15: the chain's first event anchors whatever epoch it is on.
                            keyEpoch = unsigned.keyEpoch,
                            keyEpochStartedAtEpochMillis = unsigned.createdAtEpochMillis,
                        ),
                    )
                    AppendResult.Appended
                }
            }
        }

        // Forked chains are permanently blocked (§9) — even for gap rows.
        if (head.forked) return AppendResult.Rejected

        return when {
            unsigned.sequence == head.lastSequence + 1 -> {
                val stored = db.chainEventDao().getBySequence(pairId, sender, unsigned.sequence)
                when {
                    stored == null ->
                        if (unsigned.previousEventHash.contentEquals(head.lastEventHash)) {
                            appendAndAdvance(pairId, envelope, direction, hash, head, messageId, eventId)
                        } else {
                            flagFork(head)
                        }

                    stored.syncState == ChainEvent.SYNC_STATE_PENDING_GAP && stored.eventHash.contentEquals(hash) ->
                        fillGap(pairId, envelope, stored, hash, head)

                    stored.eventHash.contentEquals(hash) -> AppendResult.Duplicate

                    else -> flagFork(head)
                }
            }

            unsigned.sequence > head.lastSequence + 1 -> insertGapOrConflict(
                pairId, envelope, direction, hash, unsigned.sequence, head, eventId,
            )

            else -> if (sameAsStored(pairId, sender, unsigned.sequence, hash)) {
                AppendResult.Duplicate
            } else {
                flagFork(head)
            }
        }
    }

    private suspend fun appendAndAdvance(
        pairId: String,
        envelope: SignedEnvelope,
        direction: String,
        hash: ByteArray,
        head: ChainHead,
        messageId: String? = null,
        eventId: String? = null,
    ): AppendResult {
        insertRow(pairId, envelope, direction, hash, ChainEvent.SYNC_STATE_PENDING, messageId, eventId)
        val advanced = head.withEpochOf(envelope.unsigned).copy(lastSequence = head.lastSequence + 1, lastEventHash = hash)
        return cascadeFrom(pairId, head.senderKeyId, advanced)
    }

    /**
     * Step 15: the head carries the highest epoch seen on this chain. An envelope under a
     * later epoch advances it and anchors the epoch at the envelope's signed creation time —
     * the same value the sender's own rotation wrote, so both devices agree on the epoch's
     * start without any clock reconciliation. An epoch-0 head from before MIGRATION_8_9 gets
     * its anchor from the next event it sees.
     */
    private fun ChainHead.withEpochOf(u: UnsignedEnvelope): ChainHead =
        if (u.keyEpoch > keyEpoch) {
            copy(
                keyEpoch = u.keyEpoch,
                keyEpochStartedAtEpochMillis = u.createdAtEpochMillis,
                keyEpochStartSequence = lastSequence,
            )
        } else {
            copy(keyEpochStartedAtEpochMillis = keyEpochStartedAtEpochMillis ?: u.createdAtEpochMillis)
        }

    /** Advances the head, then absorbs any contiguous stored gaps behind it (§9 backfill completion). */
    private suspend fun cascadeFrom(pairId: String, sender: String, newHead: ChainHead): AppendResult {
        var head = newHead
        while (true) {
            val next = db.chainEventDao().getBySequence(pairId, sender, head.lastSequence + 1) ?: break
            if (next.syncState != ChainEvent.SYNC_STATE_PENDING_GAP ||
                !next.previousEventHash.contentEquals(head.lastEventHash)
            ) break
            db.chainEventDao().update(next.copy(syncState = ChainEvent.SYNC_STATE_PENDING))
            head = head.copy(lastSequence = head.lastSequence + 1, lastEventHash = next.eventHash)
        }
        // Successful recovery: the replayed segment closed every gap — end the repair before
        // the head write lands, so the state and the chain can never disagree.
        if (head.repairState in ChainHead.ACTIVE_REPAIR_STATES &&
            db.chainEventDao().gapSequences(pairId, sender).isEmpty()
        ) {
            head = head.copy(
                repairState = ChainHead.REPAIR_NONE,
                repairFirstRequestedAtEpochMillis = null,
                repairLastRequestedAtEpochMillis = null,
            )
        }
        db.chainHeadDao().update(head)
        return AppendResult.Appended
    }

    /** AD-08 stage 1 entry — idempotent; only an untouched (NONE) head starts a repair. An
     *  exhausted/terminal head stays: a new gap must not restart a lapsed recovery window. */
    private suspend fun markRepairPending(head: ChainHead) {
        if (head.repairState != ChainHead.REPAIR_NONE) return
        db.chainHeadDao().update(head.copy(repairState = ChainHead.REPAIR_PENDING))
    }

    private suspend fun fillGap(
        pairId: String,
        envelope: SignedEnvelope,
        stored: ChainEvent,
        hash: ByteArray,
        head: ChainHead,
    ): AppendResult {
        // The gap row's link to its predecessor was never validated at insertion time (there
        // was no predecessor yet). Refuse to advance the head over a broken link — the sender
        // claimed a different previous hash than the chain actually holds, so flag the fork.
        if (!stored.previousEventHash.contentEquals(head.lastEventHash)) return flagFork(head)
        // The previously gap-flagged event is now contiguous; store it normally and advance.
        db.chainEventDao().update(stored.copy(syncState = ChainEvent.SYNC_STATE_PENDING))
        val advanced = head.withEpochOf(envelope.unsigned).copy(lastSequence = head.lastSequence + 1, lastEventHash = hash)
        return cascadeFrom(pairId, head.senderKeyId, advanced)
    }

    /** A conflicting event at any position flags the chain FORKED and blocks it (§9). While a
     *  repair is in flight this is the AD-08 hash-verification failure of a supplied replay
     *  segment, which escalates to TAMPER_SUSPECTED (§9 row 458) — never a benign case. */
    private suspend fun flagFork(head: ChainHead): AppendResult {
        val tampered = head.repairState in ChainHead.ACTIVE_REPAIR_STATES
        if (tampered) {
            Log.w(TAG, "TAMPER_SUSPECTED: replay for pair=${head.pairId} sender=${head.senderKeyId} failed hash verification during repair")
            onSecuritySignal(EVENT_CHAIN_TAMPER_SUSPECTED)
        }
        db.chainHeadDao().update(
            head.copy(
                forked = true,
                repairState = if (tampered) ChainHead.REPAIR_TAMPER_SUSPECTED else head.repairState,
            ),
        )
        return AppendResult.Fork
    }

    private suspend fun insertGapOrConflict(
        pairId: String,
        envelope: SignedEnvelope,
        direction: String,
        hash: ByteArray,
        sequence: Long,
        head: ChainHead?,
        eventId: String? = null,
    ): AppendResult {
        val stored = db.chainEventDao().getBySequence(pairId, envelope.unsigned.senderKeyId, sequence)
        return when {
            stored == null -> {
                insertRow(pairId, envelope, direction, hash, ChainEvent.SYNC_STATE_PENDING_GAP, null, eventId)
                // AD-08 stage 1: a buffered orphan starts the repair path — same transaction,
                // so no pass can observe a gap without its REPAIR_PENDING marker. A headless
                // namespace cannot be marked: AD-08b requests need an attested head to anchor
                // (sequence + hash) on, and the re-delivered predecessors create the head.
                head?.let { markRepairPending(it) }
                AppendResult.Gap
            }

            stored.eventHash.contentEquals(hash) -> AppendResult.Duplicate

            // A conflicting event with no established chain is simply invalid — nothing to fork.
            head == null -> AppendResult.Rejected

            else -> flagFork(head)
        }
    }

    private suspend fun sameAsStored(pairId: String, sender: String, sequence: Long, hash: ByteArray): Boolean =
        db.chainEventDao().getBySequence(pairId, sender, sequence)?.eventHash?.contentEquals(hash) == true

    private suspend fun insertRow(
        pairId: String,
        envelope: SignedEnvelope,
        direction: String,
        hash: ByteArray,
        syncState: String,
        messageId: String? = null,
        wireEventId: String? = null,
    ) {
        val u = envelope.unsigned
        val row = ChainEvent(
            pairId = pairId,
            senderKeyId = u.senderKeyId,
            eventId = wireEventId,
            epoch = u.epoch,
            sequence = u.sequence,
            previousEventHash = u.previousEventHash,
            eventHash = hash,
            envelope = encodeSigned(envelope),
            direction = direction,
            createdAtEpochMillis = u.createdAtEpochMillis,
            syncState = syncState,
        )
        val rowId = db.chainEventDao().insert(row)
        // AD-06b: local events get their outbox row in this same transaction — messageId is
        // generated once here (or caller-pinned) and the stored bytes are the ones every
        // retry transmits.
        if (direction == ChainEvent.DIRECTION_SENT) {
            db.outboxDao().insert(
                Outbox(
                    messageId = messageId ?: UUID.randomUUID().toString(),
                    eventId = rowId,
                    envelopeBytes = row.envelope,
                    status = Outbox.STATUS_PENDING,
                    createdAtEpochMillis = u.createdAtEpochMillis,
                    // §8.7: retention starts at creation, so a row is never retried past the
                    // relay's own 30-day ciphertext cap.
                    expiresAtEpochMillis = u.createdAtEpochMillis + Outbox.RETENTION_MILLIS,
                ),
            )
        }
    }

    companion object {
        private const val TAG = "MyExodusChainStore"

        /** §14 line 581: the two §9 outcomes that are counted separately — a rising
         *  TAMPER_SUSPECTED count is a security signal, a rising CHAIN_INCOMPLETE count is a
         *  product/retention signal, and they must never be blended into one number. */
        const val EVENT_CHAIN_TAMPER_SUSPECTED = "chain_tamper_suspected"
        const val EVENT_CHAIN_INCOMPLETE = "chain_incomplete"

        /** Step 11 replay bound: one repair answer per request; the re-request cadence covers
         *  anything longer (a 30-day window is ~30 chances to catch up). */
        const val REPLAY_BATCH_LIMIT = 100

        /**
         * Outcomes whose message is fully consumed and gets marked in `processed_events`
         * (AD-10b). Fork/Rejected are deliberately NOT marked — the event was not incorporated,
         * so the message stays subject to re-delivery (and stays un-acked) rather than being
         * silently forgotten.
         */
        private val PROCESSED_OUTCOMES = setOf(AppendResult.Appended, AppendResult.Duplicate, AppendResult.Gap)
    }
}
