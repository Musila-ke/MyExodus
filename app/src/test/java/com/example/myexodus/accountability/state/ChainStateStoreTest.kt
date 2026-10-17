package com.example.myexodus.accountability.state

import android.app.Application
import android.content.Context
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.accountability.serialization.SignedEnvelope
import com.example.myexodus.accountability.serialization.UnsignedEnvelope
import com.example.myexodus.accountability.serialization.decodeSigned
import com.example.myexodus.accountability.serialization.eventHash
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** §12 state-machine fault injection: fork detection, gap handling terminal states, atomicity. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ChainStateStoreTest {

    private lateinit var context: Context
    private lateinit var dbName: String
    private lateinit var db: AccountabilityDatabase
    private lateinit var store: ChainStateStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbName = "test_${System.nanoTime()}.db"
        db = Room.databaseBuilder(context, AccountabilityDatabase::class.java, dbName)
            .build()
        store = ChainStateStore(db)
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(dbName)
    }

    private val pairId = "pair-1"
    private val sender = "a".repeat(64)

    private fun envelope(
        sequence: Long,
        prev: ByteArray? = null,
        seed: Int = 0,
        senderKeyId: String = sender,
    ) = SignedEnvelope(
        unsigned = UnsignedEnvelope(
            senderKeyId = senderKeyId,
            epoch = 0,
            sequence = sequence,
            previousEventHash = prev,
            nonce = ByteArray(12) { seed.toByte() },
            ciphertext = byteArrayOf(seed.toByte(), 2, 3),
            createdAtEpochMillis = NOW,
        ),
        signature = ByteArray(8),
    )

    private fun chained(sequence: Long, previous: SignedEnvelope, seed: Int = sequence.toInt()) =
        envelope(sequence, eventHash(previous.unsigned), seed)

    private suspend fun head() = db.chainHeadDao().get(pairId, sender)

    @Test
    fun firstEvent_createsChainHead() = runTest {
        assertEquals(ChainStateStore.AppendResult.Appended, store.appendLocal(pairId, envelope(1)))
        val h = head()
        assertEquals(1L, h?.lastSequence)
        assertFalse(h!!.forked)
        assertEquals(1, db.chainEventDao().chainFor(pairId).size)
    }

    @Test
    fun subsequentEvents_chainContiguously() = runTest {
        val e1 = envelope(1)
        val e2 = chained(2, e1)
        val e3 = chained(3, e2)
        store.appendLocal(pairId, e1)
        store.appendLocal(pairId, e2)
        store.appendLocal(pairId, e3)
        assertEquals(3L, head()?.lastSequence)
        val events = db.chainEventDao().chainFor(pairId)
        assertEquals(listOf(1L, 2L, 3L), events.map { it.sequence })
        // Each stored row links to its predecessor's hash (chain continuity, FR4).
        assertTrue(events[1].previousEventHash.contentEquals(events[0].eventHash))
        assertTrue(events[2].previousEventHash.contentEquals(events[1].eventHash))
    }

    @Test
    fun restart_reloadsChainAndHead_continuesContiguously() = runTest {
        // §12 fault injection: a database restore (process death, fresh store instance) must
        // not lose chain state — the next event links to the surviving head.
        val e1 = envelope(1)
        val e2 = chained(2, e1)
        store.appendLocal(pairId, e1)
        store.appendLocal(pairId, e2)
        db.close()

        val reopened = Room.databaseBuilder(context, AccountabilityDatabase::class.java, dbName).build()
        try {
            val freshStore = ChainStateStore(reopened)
            assertEquals(2, freshStore.chainFor(pairId).size)
            val e3 = chained(3, freshStore.chainFor(pairId).last().let { decodeSigned(it.envelope) })
            assertEquals(ChainStateStore.AppendResult.Appended, freshStore.appendLocal(pairId, e3))
            assertEquals(3L, reopened.chainHeadDao().get(pairId, sender)?.lastSequence)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun duplicateDelivery_isIdempotent() = runTest {
        val e1 = envelope(1)
        store.receiveRemote(pairId, e1)
        assertEquals(ChainStateStore.AppendResult.Duplicate, store.receiveRemote(pairId, e1))
        assertEquals(1L, head()?.lastSequence)
        assertEquals(1, db.chainEventDao().chainFor(pairId).size)
    }

    @Test
    fun gap_isHeldAndDoesNotAdvanceHead() = runTest {
        store.receiveRemote(pairId, envelope(1))
        val e3 = chained(3, envelope(1), seed = 3)
        assertEquals(ChainStateStore.AppendResult.Gap, store.receiveRemote(pairId, e3))
        assertEquals(1L, head()?.lastSequence)
        assertEquals(listOf(3L), store.gapSequences(pairId, sender))
        assertEquals(ChainEvent.SYNC_STATE_PENDING_GAP, db.chainEventDao().chainFor(pairId).last().syncState)
        // AD-08 stage 1: the buffered orphan opened the repair in the same transaction.
        assertEquals(ChainHead.REPAIR_PENDING, head()?.repairState)
    }

    @Test
    fun gapFill_cascadeAdvancesContiguousEvents() = runTest {
        val e1 = envelope(1)
        val e2 = chained(2, e1)
        val e3 = chained(3, e2) // sender built a contiguous chain; the relay delivered 1, 3, then 2
        store.receiveRemote(pairId, e1)
        store.receiveRemote(pairId, e3) // PENDING_GAP
        assertEquals(ChainStateStore.AppendResult.Appended, store.receiveRemote(pairId, e2))
        // The previously gap-flagged event is absorbed without re-delivery.
        assertEquals(3L, head()?.lastSequence)
        assertTrue(store.gapSequences(pairId, sender).isEmpty())
        assertEquals(3, db.chainEventDao().chainFor(pairId).size)
        // AD-08 recovery: the cascade closed every gap, so the repair ended with it.
        assertEquals(ChainHead.REPAIR_NONE, head()?.repairState)
        assertNull(head()?.repairFirstRequestedAtEpochMillis)
    }

    @Test
    fun fork_sameSequenceDifferentHash_flagsAndBlocks() = runTest {
        val e1 = envelope(1)
        val e2 = chained(2, e1)
        store.receiveRemote(pairId, e1)
        store.receiveRemote(pairId, e2)
        val conflicting2 = chained(2, e1, seed = 99)
        assertEquals(ChainStateStore.AppendResult.Fork, store.receiveRemote(pairId, conflicting2))
        assertTrue(head()?.forked == true)
        // Blocked: no appends, no gap holds, no stale-acceptance on the forked chain.
        assertEquals(ChainStateStore.AppendResult.Rejected, store.receiveRemote(pairId, chained(3, e2, seed = 5)))
        assertEquals(ChainStateStore.AppendResult.Rejected, store.receiveRemote(pairId, envelope(1)))
        assertEquals(ChainStateStore.AppendResult.Rejected, store.receiveRemote(pairId, envelope(50)))
    }

    @Test
    fun prevHashMismatch_atHead_isFork() = runTest {
        val e1 = envelope(1)
        store.receiveRemote(pairId, e1)
        val broken2 = envelope(2, prev = ByteArray(32) { 7 }) // chains off a nonexistent hash
        assertEquals(ChainStateStore.AppendResult.Fork, store.receiveRemote(pairId, broken2))
        assertTrue(head()?.forked == true)
        // No partial write: the conflicting event never entered the table.
        assertEquals(1, db.chainEventDao().chainFor(pairId).size)
    }

    @Test
    fun staleOldEvent_matching_isDuplicate_conflicting_isFork() = runTest {
        val e1 = envelope(1)
        val e2 = chained(2, e1)
        store.receiveRemote(pairId, e1)
        store.receiveRemote(pairId, e2)
        assertEquals(ChainStateStore.AppendResult.Duplicate, store.receiveRemote(pairId, e1))
        val conflicting1 = envelope(1, seed = 99)
        assertEquals(ChainStateStore.AppendResult.Fork, store.receiveRemote(pairId, conflicting1))
        assertTrue(head()?.forked == true)
    }

    @Test
    fun newChain_firstEventWithPreviousHash_isRejected() = runTest {
        assertEquals(
            ChainStateStore.AppendResult.Rejected,
            store.receiveRemote(pairId, envelope(1, prev = ByteArray(32))),
        )
        assertNull(head())
    }

    @Test
    fun newChain_startingAtSequence3_isGap() = runTest {
        assertEquals(ChainStateStore.AppendResult.Gap, store.receiveRemote(pairId, envelope(3)))
        assertNull(head())
        assertEquals(listOf(3L), store.gapSequences(pairId, sender))
        // No head to anchor an AD-08b request on — the repair state cannot exist yet.
        assertTrue(store.repairHeads(pairId).isEmpty())
    }

    @Test
    fun outbox_returnsOnlyUnsignedSentEventsOldestFirst() = runTest {
        val e1 = envelope(1)
        val e2 = chained(2, e1)
        val e3 = chained(3, e2)
        store.appendLocal(pairId, e1)
        store.appendLocal(pairId, e2)
        store.receiveRemote(pairId, e3) // received: never in the outbox
        var outbox = store.pendingSentEvents(pairId)
        assertEquals(listOf(1L, 2L), outbox.map { it.sequence })
        assertEquals(ChainEvent.SYNC_STATE_PENDING, outbox[0].syncState)
        store.markTransmitted(outbox[0])
        outbox = store.pendingSentEvents(pairId)
        assertEquals(listOf(2L), outbox.map { it.sequence })
        assertEquals(ChainEvent.SYNC_STATE_SENT, db.chainEventDao().getBySequence(pairId, sender, 1)?.syncState)
    }

    @Test
    fun directions_areRecorded() = runTest {
        val e1 = envelope(1)
        store.appendLocal(pairId, e1)
        store.receiveRemote(pairId, chained(2, e1))
        val events = db.chainEventDao().chainFor(pairId)
        assertEquals(ChainEvent.DIRECTION_SENT, events[0].direction)
        assertEquals(ChainEvent.DIRECTION_RECEIVED, events[1].direction)
    }

    // --- Plan §8 Step 4 / AD-06b: the outbox half of the single-transaction append. ---

    @Test
    fun localAppend_writesOneOutboxRowCarryingTheStoredBytes_inChainOrder() = runTest {
        val e1 = envelope(1)
        val e2 = chained(2, e1)
        store.appendLocal(pairId, e1)
        store.appendLocal(pairId, e2)

        val pending = db.outboxDao().pendingFor(pairId, 10)
        val events = db.chainEventDao().chainFor(pairId)
        assertEquals(events.map { it.id }, pending.map { it.eventId })
        pending.forEachIndexed { i, row ->
            assertArrayEquals(events[i].envelope, row.envelopeBytes)
            assertTrue(row.messageId.isNotBlank() && row.messageId != pending.getOrNull(i - 1)?.messageId)
            assertEquals(Outbox.STATUS_PENDING, row.status)
            assertEquals(0, row.attemptCount)
            assertNull(row.lastAttemptAtEpochMillis)
            assertNull(row.destinationUid) // resolved at drain time (never persisted beyond it)
        }
    }

    /** Plan §7 item 9 / §6 step 3: the drain projection joins the event row for the mailbox
     *  metadata (`senderId` = chain-scoping key id, `keyEpoch`), returns only PENDING rows in
     *  chain order, and honors the batch limit. */
    @Test
    fun pendingForDrain_carriesTheMailboxMetadataInChainOrder_andSkipsSentRows() = runTest {
        val e1 = envelope(1)
        val e2 = chained(2, e1)
        store.appendLocal(pairId, e1)
        store.appendLocal(pairId, e2)

        val rows = db.outboxDao().pendingForDrain(pairId, 10)
        val events = db.chainEventDao().chainFor(pairId)
        assertEquals(db.outboxDao().pendingFor(pairId, 10).map { it.messageId }, rows.map { it.messageId })
        assertArrayEquals(events[0].envelope, rows[0].envelopeBytes)
        assertArrayEquals(events[1].envelope, rows[1].envelopeBytes)
        assertEquals(events[0].senderKeyId, rows[0].senderKeyId)
        assertEquals(events[1].epoch, rows[1].epoch)

        db.outboxDao().markSent(rows[0].messageId)
        val remaining = db.outboxDao().pendingForDrain(pairId, 10)
        assertEquals(listOf(rows[1].messageId), remaining.map { it.messageId })
        assertEquals(1, db.outboxDao().pendingForDrain(pairId, 1).size)
    }

    @Test
    fun duplicateLocalReappend_doesNotGrowTheOutbox() = runTest {
        val e1 = envelope(1)
        store.appendLocal(pairId, e1)
        assertEquals(ChainStateStore.AppendResult.Duplicate, store.appendLocal(pairId, e1))
        assertEquals(1, db.outboxDao().pendingFor(pairId, 10).size)
    }

    @Test
    fun remoteAppend_neverEnqueues() = runTest {
        store.receiveRemote(pairId, envelope(1, senderKeyId = "b".repeat(64)))
        assertTrue(db.outboxDao().pendingFor(pairId, 10).isEmpty())
    }

    @Test
    fun newChainHead_startsAtKeyEpochZero() = runTest {
        store.appendLocal(pairId, envelope(1))
        assertEquals(0, head()?.keyEpoch)
    }

    @Test
    fun deletePairData_clearsTheOutboxWithTheChain() = runTest {
        store.appendLocal(pairId, envelope(1))
        // A second pair's queued event must survive the wipe.
        store.appendLocal("pair-2", envelope(1, seed = 5))
        store.deletePairData(pairId)
        assertTrue(db.outboxDao().pendingFor(pairId, 10).isEmpty())
        assertEquals(1, db.outboxDao().pendingFor("pair-2", 10).size)
    }

    @Test
    fun wipeAllPairData_clearsEveryPairsChainRows_butKeepsTheJournalAndReplayMarkers() = runTest {
        // §8 Step 17 (AD-22): the enrollment-less wipe — no pairId to scope to, so the wipe is
        // global by definition. Journal rows must outlive it (ADR #49) and processed_events are
        // messageId-keyed replay protection, not pair data.
        store.appendLocal(pairId, envelope(1))
        store.appendLocal("pair-2", envelope(1, seed = 5))
        store.receiveRemote("pair-2", envelope(1, seed = 9, senderKeyId = "b".repeat(64)), "m1", NOW)
        db.unpairStateDao().upsert(
            UnpairState(
                pairId = "old-pair",
                role = UnpairState.ROLE_REQUESTER,
                partnerUid = "uid-2",
                messageId = "msg-1",
                senderKeyId = "k",
                delivered = true,
                ackSent = false,
                localShredded = true,
                requestedAtEpochMillis = NOW,
            ),
        )

        store.wipeAllPairData()

        assertTrue(db.chainEventDao().chainFor(pairId).isEmpty())
        assertTrue(db.chainEventDao().chainFor("pair-2").isEmpty())
        assertNull(db.chainHeadDao().get(pairId, sender))
        assertNull(db.chainHeadDao().get("pair-2", sender))
        assertTrue(db.outboxDao().pendingFor(pairId, 10).isEmpty())
        assertTrue(db.outboxDao().pendingFor("pair-2", 10).isEmpty())
        assertEquals("old-pair", db.unpairStateDao().all().single().pairId)
        assertTrue(db.processedEventDao().contains("m1"))
    }

    // --- Plan §7 item 10 / §8 Step 9 / AD-10b: processed_events dedup inside the transaction. ---

    @Test
    fun receiveRemote_processedMessageId_shortCircuitsBeforeAnyValidation() = runTest {
        val e1 = envelope(1)
        store.receiveRemote(pairId, e1, "m1", NOW)
        // A conflicting envelope under an already-processed messageId must be a plain
        // duplicate — validating it would have flagged a fork.
        val conflicting = envelope(1, seed = 99)
        assertEquals(ChainStateStore.AppendResult.Duplicate, store.receiveRemote(pairId, conflicting, "m1", NOW))
        assertFalse(head()!!.forked)
        assertEquals(1, db.chainEventDao().chainFor(pairId).size)
    }

    @Test
    fun receiveRemote_marksProcessedForAppendedGapAndChainDuplicateOnly() = runTest {
        val e1 = envelope(1)
        store.receiveRemote(pairId, e1, "m1", NOW)
        assertTrue(db.processedEventDao().contains("m1")) // committed with the append

        assertEquals(ChainStateStore.AppendResult.Gap, store.receiveRemote(pairId, envelope(3), "m3", NOW))
        assertTrue(db.processedEventDao().contains("m3"))

        // Fork: the event was not incorporated — no marker, so a re-delivery re-validates.
        val broken2 = envelope(2, prev = ByteArray(32) { 7 })
        assertEquals(ChainStateStore.AppendResult.Fork, store.receiveRemote(pairId, broken2, "m2", NOW))
        assertFalse(db.processedEventDao().contains("m2"))
        assertEquals(
            ChainStateStore.AppendResult.Rejected,
            store.receiveRemote(pairId, envelope(2, prev = ByteArray(32) { 7 }), "m2", NOW),
        )
        assertFalse(db.processedEventDao().contains("m2"))
    }

    // --- Plan §8 Step 11 / AD-08 + AD-08b: repair bookkeeping, escalation, tamper correlation. ---

    @Test
    fun markRepairRequested_anchorsTheWindowOnTheFirstRequest_andAdvancesTheLast() = runTest {
        store.appendLocal(pairId, envelope(1))

        store.markRepairRequested(pairId, sender, NOW)
        assertEquals(ChainHead.REPAIR_PENDING, head()?.repairState)
        assertEquals(NOW, head()?.repairFirstRequestedAtEpochMillis)
        assertEquals(NOW, head()?.repairLastRequestedAtEpochMillis)

        store.markRepairRequested(pairId, sender, NOW + DAY)
        assertEquals("the 30-day window anchors on the first request", NOW, head()?.repairFirstRequestedAtEpochMillis)
        assertEquals(NOW + DAY, head()?.repairLastRequestedAtEpochMillis)
    }

    @Test
    fun escalation_reachesTheNeutralTerminal_neverATamperVerdict() = runTest {
        store.receiveRemote(pairId, envelope(1))
        store.receiveRemote(pairId, chained(3, envelope(1), seed = 3))
        assertEquals(ChainHead.REPAIR_PENDING, head()?.repairState)

        store.markRecoveryExhausted(pairId, sender, NOW)

        assertEquals(ChainHead.REPAIR_RECOVERY_EXHAUSTED, head()?.repairState)
        assertEquals("the middle stage still accepts a late replay", listOf(3L), store.gapSequences(pairId, sender))
        assertFalse(head()!!.forked)

        store.markChainIncomplete(pairId, sender)

        assertEquals(ChainHead.REPAIR_CHAIN_INCOMPLETE, head()?.repairState)
        assertFalse("§9 row 457: the terminal state is neutral", head()!!.forked)
        assertTrue(store.gapSequences(pairId, sender).isEmpty())
        assertEquals(
            ChainEvent.SYNC_STATE_CHAIN_INCOMPLETE,
            db.chainEventDao().getBySequence(pairId, sender, 3)?.syncState,
        )
    }

    @Test
    fun lateReplay_duringRecoveryExhausted_stillHeals_andEndsTheRepair() = runTest {
        // The sender built 1→2→3; 2 was lost in transit, so the buffered 3 links to 2's hash.
        val e1 = envelope(1)
        val e2 = chained(2, e1)
        store.receiveRemote(pairId, e1)
        store.receiveRemote(pairId, chained(3, e2))
        store.markRepairRequested(pairId, sender, NOW)
        store.markRecoveryExhausted(pairId, sender, NOW + DAY)

        assertEquals(ChainStateStore.AppendResult.Appended, store.receiveRemote(pairId, e2))

        assertEquals(3L, head()?.lastSequence)
        assertEquals("a late replay inside the window heals the chain", ChainHead.REPAIR_NONE, head()?.repairState)
        assertNull(head()?.repairFirstRequestedAtEpochMillis)
        assertNull(head()?.repairLastRequestedAtEpochMillis)
    }

    @Test
    fun laterOrphan_doesNotRestartAnExhaustedRepair() = runTest {
        store.receiveRemote(pairId, envelope(1))
        store.receiveRemote(pairId, chained(3, envelope(1), seed = 3))
        store.markRecoveryExhausted(pairId, sender, NOW)
        store.markChainIncomplete(pairId, sender)

        assertEquals(ChainStateStore.AppendResult.Gap, store.receiveRemote(pairId, envelope(5)))

        assertEquals("the lapsed window cannot reopen", ChainHead.REPAIR_CHAIN_INCOMPLETE, head()?.repairState)
    }

    @Test
    fun conflictingEventDuringARepair_isTamperSuspect_andBlocksTheChain() = runTest {
        store.receiveRemote(pairId, envelope(1))
        store.receiveRemote(pairId, chained(3, envelope(1), seed = 3)) // opens the repair

        // The supplied "replay" for sequence 2 does not hash-link to the stored head: AD-08
        // hash verification failed → TAMPER_SUSPECTED, automatic trust blocked (§9 row 458).
        assertEquals(
            ChainStateStore.AppendResult.Fork,
            store.receiveRemote(pairId, envelope(2, prev = ByteArray(32) { 7 })),
        )

        assertEquals(ChainHead.REPAIR_TAMPER_SUSPECTED, head()?.repairState)
        assertTrue(head()?.forked == true)
        assertEquals(
            "blocked: even an honest-looking continuation is refused",
            ChainStateStore.AppendResult.Rejected,
            store.receiveRemote(pairId, envelope(50)),
        )
    }

    @Test
    fun gapFill_conflictingPredecessorLink_isTamperSuspect_notSilentAdvance() = runTest {
        // The fill-time link check (ChainStateStore.kt fillGap): a gap row is absorbed only if
        // its stored predecessor link matches the head that actually formed. Sender built
        // 1→2→3; only 3 arrives first, so it buffers as PENDING_GAP and opens the repair.
        val e1 = envelope(1)
        val e2 = chained(2, e1)
        store.receiveRemote(pairId, e1)
        store.receiveRemote(pairId, chained(3, e2))
        assertEquals(ChainHead.REPAIR_PENDING, head()?.repairState)

        // A conflicting sequence-2 that chains off e1 is structurally plausible, so it is
        // accepted and advances the head — the fork only becomes visible at fill time.
        assertEquals(
            ChainStateStore.AppendResult.Appended,
            store.receiveRemote(pairId, chained(2, e1, seed = 99)),
        )
        assertEquals(2L, head()?.lastSequence)

        // The re-delivered sequence-3's stored link (the real 2's hash) no longer matches the
        // head → AD-08 hash-verification failure during an active repair → TAMPER_SUSPECTED.
        assertEquals(ChainStateStore.AppendResult.Fork, store.receiveRemote(pairId, chained(3, e2)))
        assertEquals(ChainHead.REPAIR_TAMPER_SUSPECTED, head()?.repairState)
        assertTrue(head()!!.forked)
    }

    @Test
    fun flagTamperIfRepairing_isTrueOnlyWhileARepairIsActive() = runTest {
        store.appendLocal(pairId, envelope(1))
        assertEquals(false, store.flagTamperIfRepairing(pairId))
        assertFalse(head()!!.forked)

        store.receiveRemote(pairId, chained(3, envelope(1), seed = 3))
        assertEquals(true, store.flagTamperIfRepairing(pairId))
        assertEquals(ChainHead.REPAIR_TAMPER_SUSPECTED, head()?.repairState)
        assertTrue(head()!!.forked)

        // No longer an active repair: a later bad message is ordinary noise, not tamper.
        assertEquals(false, store.flagTamperIfRepairing(pairId))
    }

    @Test
    fun securitySignals_fireOnTamperAndIncomplete_neverForOrdinaryNoise() = runTest {
        val signals = mutableListOf<String>()
        val watched = ChainStateStore(db, onSecuritySignal = { signals += it })

        watched.appendLocal(pairId, envelope(1))
        watched.flagTamperIfRepairing(pairId) // no repair active → nothing to report

        watched.receiveRemote(pairId, chained(3, envelope(1), seed = 3)) // opens the repair
        watched.receiveRemote(pairId, envelope(2, prev = ByteArray(32) { 7 })) // hash mismatch → tamper

        watched.markChainIncomplete(pairId, sender)

        assertEquals(
            listOf(
                ChainStateStore.EVENT_CHAIN_TAMPER_SUSPECTED,
                ChainStateStore.EVENT_CHAIN_INCOMPLETE,
            ),
            signals,
        )
    }

    @Test
    fun concurrentAppends_forTheSameSequence_exactlyOneWins_noDuplicateSequence() = runTest {
        // §12: "fire concurrent coroutines attempting to create an event for the same sender;
        // verify exactly one succeeds per sequence number and no duplicate sequence is
        // produced." Room serializes writers; the loser reads the committed head.
        val results = listOf(
            async { store.appendLocal(pairId, envelope(1)) },
            async { store.appendLocal(pairId, envelope(1)) },
        ).awaitAll()

        assertEquals(1, results.count { it == ChainStateStore.AppendResult.Appended })
        assertEquals(1, results.count { it == ChainStateStore.AppendResult.Duplicate })
        assertEquals(1L, head()?.lastSequence)
        assertEquals("exactly one outbox row", 1, db.outboxDao().pendingFor(pairId, 10).size)
    }

    @Test
    fun twoSendersOnOnePair_buildIndependentChains_aForkOnOneDoesNotBlockTheOther() = runTest {
        val partner = "b".repeat(64)
        store.appendLocal(pairId, envelope(1))
        store.receiveRemote(pairId, envelope(1, senderKeyId = partner))

        // A conflicting replay on the partner's chain forks only that head (no repair active:
        // a genuine append-path hash mismatch).
        assertEquals(
            ChainStateStore.AppendResult.Fork,
            store.receiveRemote(pairId, envelope(2, prev = ByteArray(32) { 7 }, senderKeyId = partner)),
        )
        assertEquals(true, db.chainHeadDao().get(pairId, partner)?.forked)
        assertEquals(false, head()?.forked)

        // My own chain continues normally.
        assertEquals(
            ChainStateStore.AppendResult.Appended,
            store.appendLocal(pairId, chained(2, envelope(1))),
        )
        assertEquals(2L, head()?.lastSequence)
        assertEquals(1L, db.chainHeadDao().get(pairId, partner)?.lastSequence)
    }

    @Test
    fun outboxRow_survivesAReopen_pendingForTheNextDrain_theCrashConsistencyStandIn() = runTest {
        // §12's "kill the process between transaction commit and SyncWorker scheduling"
        // stand-in: the append transaction is durable, so a fresh handle on the same file
        // (the next launch) sees the row still PENDING — the next sync pass's drain is the
        // reconciliation. The real process-kill leg lives in the instrumented suite.
        store.appendLocal(pairId, envelope(1))
        db.close()
        db = Room.databaseBuilder(context, AccountabilityDatabase::class.java, dbName).build()

        assertEquals(1, db.outboxDao().pendingForDrain(pairId, 10).size)
    }

    @Test
    fun enqueueReplay_requeuesExactStoredBytesAfterTheAnchor_withFreshMessageIds() = runTest {
        val e1 = envelope(1)
        val e2 = chained(2, e1)
        val e3 = chained(3, e2)
        store.appendLocal(pairId, e1)
        store.appendLocal(pairId, e2)
        store.appendLocal(pairId, e3)
        val originals = db.outboxDao().pendingFor(pairId, 10).associateBy { it.eventId }
        originals.values.forEach { db.outboxDao().markSent(it.messageId) }

        assertEquals(2, store.enqueueReplay(pairId, sender, afterSequence = 1))

        val events = db.chainEventDao().chainFor(pairId)
        val rows = db.outboxDao().pendingFor(pairId, 10)
        assertEquals("exactly the events after the anchor, in chain order", listOf(events[1].id, events[2].id), rows.map { it.eventId })
        rows.forEachIndexed { i, row ->
            assertArrayEquals("byte-identical replays", events[i + 1].envelope, row.envelopeBytes)
            assertNotEquals("fresh messageId per replay", originals[events[i + 1].id]?.messageId, row.messageId)
        }

        rows.forEach { db.outboxDao().markSent(it.messageId) }
        assertEquals("the batch limit bounds one answer", 1, store.enqueueReplay(pairId, sender, afterSequence = 0, limit = 1))
        assertEquals(listOf(events[0].id), db.outboxDao().pendingFor(pairId, 10).map { it.eventId })
    }

    companion object {
        private const val NOW = 1_700_000_000_000L
        private const val DAY = 24L * 60 * 60 * 1000
    }
}
