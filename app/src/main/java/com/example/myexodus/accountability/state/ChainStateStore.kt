package com.example.myexodus.accountability.state

import androidx.room3.withWriteTransaction
import com.example.myexodus.accountability.serialization.SignedEnvelope
import com.example.myexodus.accountability.serialization.encodeSigned
import com.example.myexodus.accountability.serialization.eventHash

/**
 * §7.3 State Manager: hash-chain validation (FR4: continuity + sequence monotonicity),
 * atomic append + head update (§8.3 single-transaction rule), and the transactional outbox.
 *
 * Signature verification is NOT here: it needs the sender's public key, which lives in the
 * enrollment layer (Phase 4). Callers must verify signatures before appendLocal/receiveRemote;
 * the store enforces chain structure, duplicate/fork/gap/expiry semantics (§9).
 */
class ChainStateStore(private val db: AccountabilityDatabase) {

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
    }

    suspend fun appendLocal(pairId: String, envelope: SignedEnvelope): AppendResult =
        validateAndAppend(pairId, envelope, ChainEvent.DIRECTION_SENT)

    suspend fun receiveRemote(pairId: String, envelope: SignedEnvelope): AppendResult =
        validateAndAppend(pairId, envelope, ChainEvent.DIRECTION_RECEIVED)

    /** Outbox: unsent local events oldest-first (§7.3). */
    suspend fun pendingSentEvents(pairId: String, limit: Int = 100): List<ChainEvent> =
        db.chainEventDao().pendingSentEvents(pairId, limit)

    /** Marks an outbox event as transmitted (sync_state SENT). */
    suspend fun markTransmitted(event: ChainEvent) {
        db.chainEventDao().update(event.copy(syncState = ChainEvent.SYNC_STATE_SENT))
    }

    /** §9 terminal state: PENDING_GAP events older than 7 days become PERMANENTLY_INCOMPLETE. */
    suspend fun expireGaps(pairId: String, nowEpochMillis: Long): Int =
        db.chainEventDao().expireGaps(pairId, nowEpochMillis - GAP_EXPIRY_MILLIS)

    /** Sequences awaiting backfill, oldest-first — consumed by the sync engines (§9 reverse channel). */
    suspend fun gapSequences(pairId: String): List<Long> =
        db.chainEventDao().gapSequences(pairId)

    suspend fun chainFor(pairId: String): List<ChainEvent> = db.chainEventDao().chainFor(pairId)

    /** This sender's own chain head: the last local event's sequence + hash (single-row read). */
    suspend fun localHead(pairId: String, senderKeyId: String): ChainHead? =
        db.chainHeadDao().get(pairId, senderKeyId)

    /** §10 bilateral deletion: wipes the pair's chain and head atomically (enrollment handled by caller). */
    suspend fun deletePairData(pairId: String) = db.withWriteTransaction {
        db.chainEventDao().deleteByPair(pairId)
        db.chainHeadDao().deleteByPair(pairId)
    }

    private suspend fun validateAndAppend(
        pairId: String,
        envelope: SignedEnvelope,
        direction: String,
    ): AppendResult = db.withWriteTransaction {
        val unsigned = envelope.unsigned
        val sender = unsigned.senderKeyId
        val hash = eventHash(unsigned)
        val head = db.chainHeadDao().get(pairId, sender)

        if (head == null) {
            return@withWriteTransaction when {
                unsigned.sequence != 1L -> insertGapOrConflict(pairId, envelope, direction, hash, unsigned.sequence, null)
                unsigned.previousEventHash != null -> AppendResult.Rejected
                else -> {
                    insertRow(pairId, envelope, direction, hash, ChainEvent.SYNC_STATE_PENDING)
                    db.chainHeadDao().insert(
                        ChainHead(pairId = pairId, senderKeyId = sender, lastSequence = 1, lastEventHash = hash),
                    )
                    AppendResult.Appended
                }
            }
        }

        // Forked chains are permanently blocked (§9) — even for gap rows.
        if (head.forked) return@withWriteTransaction AppendResult.Rejected

        when {
            unsigned.sequence == head.lastSequence -> if (sameAsStored(pairId, sender, unsigned.sequence, hash)) {
                AppendResult.Duplicate
            } else {
                flagFork(head)
            }

            unsigned.sequence == head.lastSequence + 1 -> {
                val stored = db.chainEventDao().getBySequence(pairId, sender, unsigned.sequence)
                when {
                    stored == null ->
                        if (unsigned.previousEventHash.contentEquals(head.lastEventHash)) {
                            appendAndAdvance(pairId, envelope, direction, hash, head)
                        } else {
                            flagFork(head)
                        }

                    stored.syncState == ChainEvent.SYNC_STATE_PENDING_GAP && stored.eventHash.contentEquals(hash) ->
                        fillGap(pairId, sender, stored, hash, head)

                    stored.eventHash.contentEquals(hash) -> AppendResult.Duplicate

                    else -> flagFork(head)
                }
            }

            unsigned.sequence > head.lastSequence + 1 -> insertGapOrConflict(pairId, envelope, direction, hash, unsigned.sequence, head)

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
    ): AppendResult {
        insertRow(pairId, envelope, direction, hash, ChainEvent.SYNC_STATE_PENDING)
        return cascadeFrom(pairId, head.senderKeyId, head.copy(lastSequence = head.lastSequence + 1, lastEventHash = hash))
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
        db.chainHeadDao().update(head)
        return AppendResult.Appended
    }

    private suspend fun fillGap(pairId: String, sender: String, stored: ChainEvent, hash: ByteArray, head: ChainHead): AppendResult {
        // The gap row's link to its predecessor was never validated at insertion time (there
        // was no predecessor yet). Refuse to advance the head over a broken link — the sender
        // claimed a different previous hash than the chain actually holds, so flag the fork.
        if (!stored.previousEventHash.contentEquals(head.lastEventHash)) return flagFork(head)
        // The previously gap-flagged event is now contiguous; store it normally and advance.
        db.chainEventDao().update(stored.copy(syncState = ChainEvent.SYNC_STATE_PENDING))
        return cascadeFrom(pairId, sender, head.copy(lastSequence = head.lastSequence + 1, lastEventHash = hash))
    }

    /** A conflicting event at any position flags the chain FORKED and blocks it (§9). */
    private suspend fun flagFork(head: ChainHead): AppendResult {
        db.chainHeadDao().update(head.copy(forked = true))
        return AppendResult.Fork
    }

    private suspend fun insertGapOrConflict(
        pairId: String,
        envelope: SignedEnvelope,
        direction: String,
        hash: ByteArray,
        sequence: Long,
        head: ChainHead?,
    ): AppendResult {
        val stored = db.chainEventDao().getBySequence(pairId, envelope.unsigned.senderKeyId, sequence)
        return when {
            stored == null -> {
                insertRow(pairId, envelope, direction, hash, ChainEvent.SYNC_STATE_PENDING_GAP)
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
    ) {
        val u = envelope.unsigned
        db.chainEventDao().insert(
            ChainEvent(
                pairId = pairId,
                senderKeyId = u.senderKeyId,
                epoch = u.epoch,
                sequence = u.sequence,
                previousEventHash = u.previousEventHash,
                eventHash = hash,
                envelope = encodeSigned(envelope),
                direction = direction,
                createdAtEpochMillis = u.createdAtEpochMillis,
                syncState = syncState,
            ),
        )
    }

    companion object {
        const val GAP_EXPIRY_MILLIS = 7L * 24 * 60 * 60 * 1000
    }
}
