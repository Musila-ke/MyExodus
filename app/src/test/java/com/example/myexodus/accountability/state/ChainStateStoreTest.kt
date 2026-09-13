package com.example.myexodus.accountability.state

import android.app.Application
import android.content.Context
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.accountability.serialization.SignedEnvelope
import com.example.myexodus.accountability.serialization.UnsignedEnvelope
import com.example.myexodus.accountability.serialization.decodeSigned
import com.example.myexodus.accountability.serialization.eventHash
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals(listOf(3L), store.gapSequences(pairId))
        assertEquals(ChainEvent.SYNC_STATE_PENDING_GAP, db.chainEventDao().chainFor(pairId).last().syncState)
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
        assertTrue(store.gapSequences(pairId).isEmpty())
        assertEquals(3, db.chainEventDao().chainFor(pairId).size)
    }

    @Test
    fun gap_becomesPermanentlyIncomplete_after7Days() = runTest {
        store.receiveRemote(pairId, envelope(1))
        val oldGap = SignedEnvelope(
            unsigned = UnsignedEnvelope(
                senderKeyId = sender,
                epoch = 0,
                sequence = 3,
                previousEventHash = eventHash(envelope(1).unsigned),
                nonce = ByteArray(12) { 3 },
                ciphertext = byteArrayOf(3, 2, 3),
                createdAtEpochMillis = NOW - 8L * 24 * 60 * 60 * 1000,
            ),
            signature = ByteArray(8),
        )
        store.receiveRemote(pairId, oldGap)
        assertEquals(1, store.expireGaps(pairId, NOW))
        val row = db.chainEventDao().getBySequence(pairId, sender, 3)
        assertEquals(ChainEvent.SYNC_STATE_PERMANENTLY_INCOMPLETE, row?.syncState)
    }

    @Test
    fun freshGap_doesNotExpire() = runTest {
        store.receiveRemote(pairId, envelope(1))
        store.receiveRemote(pairId, chained(3, envelope(1), seed = 3))
        assertEquals(0, store.expireGaps(pairId, NOW))
        assertEquals(ChainEvent.SYNC_STATE_PENDING_GAP, db.chainEventDao().getBySequence(pairId, sender, 3)?.syncState)
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
        assertEquals(listOf(3L), store.gapSequences(pairId))
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

    companion object {
        private const val NOW = 1_700_000_000_000L
    }
}
