package com.example.myexodus.accountability.state

import android.app.Application
import android.content.Context
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.accountability.serialization.SignedEnvelope
import com.example.myexodus.accountability.serialization.UnsignedEnvelope
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

/**
 * Plan §8 Step 15 / AD-12 (§12 line 514, §17 line 635): both rotation thresholds fire, the
 * boundary is not-yet-due, and the prior epoch is usable only inside its 7-day grace.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SessionKeyManagerTest {

    private lateinit var context: Context
    private lateinit var dbName: String
    private lateinit var db: AccountabilityDatabase
    private lateinit var store: ChainStateStore
    private lateinit var manager: SessionKeyManager

    private val pairId = "pair-1"
    private val sender = "a".repeat(64)
    private val now = 1_700_000_000_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbName = "test_${System.nanoTime()}.db"
        db = Room.databaseBuilder(context, AccountabilityDatabase::class.java, dbName).build()
        store = ChainStateStore(db)
        manager = SessionKeyManager(store)
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(dbName)
    }

    private suspend fun seedHead(
        lastSequence: Long,
        keyEpoch: Int = 0,
        startedAt: Long? = now,
        startSequence: Long = 0,
    ) = db.chainHeadDao().insert(
        ChainHead(
            pairId = pairId,
            senderKeyId = sender,
            lastSequence = lastSequence,
            lastEventHash = ByteArray(32),
            keyEpoch = keyEpoch,
            keyEpochStartedAtEpochMillis = startedAt,
            keyEpochStartSequence = startSequence,
        ),
    )

    private suspend fun head() = db.chainHeadDao().get(pairId, sender)!!

    private fun envelope(sequence: Long, keyEpoch: Int, createdAt: Long = now) = SignedEnvelope(
        unsigned = UnsignedEnvelope(
            senderKeyId = sender,
            epoch = 0,
            sequence = sequence,
            previousEventHash = null,
            nonce = ByteArray(12),
            ciphertext = byteArrayOf(1),
            createdAtEpochMillis = createdAt,
            keyEpoch = keyEpoch,
        ),
        signature = ByteArray(8),
    )

    @Test
    fun rotateIfDue_at500Events_rotatesAndAnchorsTheNewEpoch() = runTest {
        seedHead(lastSequence = SessionKeyManager.ROTATE_AFTER_EVENTS, startedAt = now)
        assertEquals(1, manager.rotateIfDue(pairId, sender, now))
        val rotated = head()
        assertEquals(1, rotated.keyEpoch)
        assertEquals(now, rotated.keyEpochStartedAtEpochMillis)
        assertEquals(SessionKeyManager.ROTATE_AFTER_EVENTS, rotated.keyEpochStartSequence)
    }

    @Test
    fun rotateIfDue_justBelowBothThresholds_doesNotRotate() = runTest {
        seedHead(
            lastSequence = SessionKeyManager.ROTATE_AFTER_EVENTS - 1,
            startedAt = now - SessionKeyManager.ROTATE_AFTER_MILLIS + 1,
        )
        assertNull(manager.rotateIfDue(pairId, sender, now))
        assertEquals(0, head().keyEpoch)
    }

    @Test
    fun rotateIfDue_at30Days_rotatesEvenWithNoTraffic() = runTest {
        seedHead(lastSequence = 7, startedAt = now - SessionKeyManager.ROTATE_AFTER_MILLIS)
        assertEquals(1, manager.rotateIfDue(pairId, sender, now))
        assertEquals(1, head().keyEpoch)
    }

    @Test
    fun rotateIfDue_withoutAnAnchor_waitsForTheFirstStampedEvent() = runTest {
        // A pre-MIGRATION_8_9 head has no measurable age; rotating on a fabricated clock
        // would be claiming a threshold that was never observed.
        seedHead(lastSequence = 900, startedAt = null, startSequence = 900)
        assertNull(manager.rotateIfDue(pairId, sender, now))
        assertEquals(0, head().keyEpoch)
        assertNull(head().keyEpochStartedAtEpochMillis)
    }

    @Test
    fun rotateIfDue_secondRotationCountsFromThePreviousBoundary() = runTest {
        seedHead(lastSequence = 500, keyEpoch = 1, startedAt = now, startSequence = 500)
        // 500 events later the next epoch is due; the earlier epoch's traffic must not count.
        db.chainHeadDao().update(head().copy(lastSequence = 999, keyEpochStartedAtEpochMillis = now - 1000))
        assertNull(manager.rotateIfDue(pairId, sender, now))
        db.chainHeadDao().update(head().copy(lastSequence = 1000))
        assertEquals(2, manager.rotateIfDue(pairId, sender, now))
        assertEquals(1000L, head().keyEpochStartSequence)
    }

    @Test
    fun appendingAnEnvelope_anchorsADeferredEpochOnTheHead() = runTest {
        // v8 head shape: key_epoch set (or 0) but no anchor — the next event stamps one from
        // its signed creation time, identically on both devices.
        seedHead(lastSequence = 1, startedAt = null)
        store.appendLocal(pairId, envelope(sequence = 2, keyEpoch = 0).withPrevious(store))
        val h = head()
        assertEquals(2L, h.lastSequence)
        assertEquals(0, h.keyEpoch)
        assertEquals(now, h.keyEpochStartedAtEpochMillis)
    }

    @Test
    fun aLaterEpochOnAReceivedEnvelope_advancesAndAnchorsTheHead() = runTest {
        seedHead(lastSequence = 1, startedAt = now - 1000)
        val newer = now + 5
        store.appendLocal(pairId, envelope(sequence = 2, keyEpoch = 3, createdAt = newer).withPrevious(store))
        val h = head()
        assertEquals(3, h.keyEpoch)
        assertEquals(newer, h.keyEpochStartedAtEpochMillis)
        assertEquals(1L, h.keyEpochStartSequence)

        // Same-epoch traffic keeps the anchor; an even later epoch re-anchors at the boundary.
        store.appendLocal(pairId, envelope(sequence = 3, keyEpoch = 3, createdAt = newer + 1).withPrevious(store))
        assertEquals(newer, head().keyEpochStartedAtEpochMillis)
        assertTrue(manager.acceptsIncoming(head(), 4, newer + 2))
    }

    @Test
    fun acceptsIncoming_matchesTheEpochWindow() = runTest {
        seedHead(lastSequence = 10, keyEpoch = 2, startedAt = now - 1000)
        val h = head()
        assertTrue("no chain yet: whatever epoch the partner is on", manager.acceptsIncoming(null, 9, now))
        assertTrue("current epoch", manager.acceptsIncoming(h, 2, now))
        assertTrue("receiver catching up across missed epochs", manager.acceptsIncoming(h, 5, now))
        assertTrue("prior epoch inside the grace window", manager.acceptsIncoming(h, 1, now))
        assertFalse(
            "prior epoch after the grace window",
            manager.acceptsIncoming(h, 1, now - 1000 + SessionKeyManager.PRIOR_EPOCH_GRACE_MILLIS + 1),
        )
        assertFalse("older than the prior epoch", manager.acceptsIncoming(h, 0, now))
        assertTrue(
            "an unanchored head cannot have expired its grace",
            manager.acceptsIncoming(h.copy(keyEpochStartedAtEpochMillis = null), 1, now),
        )
    }

    /** The raw envelope must link to the existing head, so the append validates as contiguous. */
    private suspend fun SignedEnvelope.withPrevious(store: ChainStateStore): SignedEnvelope {
        val lastHash = store.localHead(pairId, sender)!!.lastEventHash
        return SignedEnvelope(unsigned.copy(previousEventHash = lastHash), signature)
    }
}
