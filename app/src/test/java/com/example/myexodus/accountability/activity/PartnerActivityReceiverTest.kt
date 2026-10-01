package com.example.myexodus.accountability.activity

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.accountability.serialization.ActivityStateAfter
import com.example.myexodus.accountability.serialization.EventPayload
import com.example.myexodus.data.db.ActivityLogKeyManager
import com.example.myexodus.data.db.AppRulesStore
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.DatabaseHealthStore
import com.example.myexodus.data.db.DatabaseKeyManager
import com.example.myexodus.data.db.KeyFailure
import com.example.myexodus.data.db.RecoveryManager
import com.example.myexodus.data.db.RulesStoreKeyManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Receive-side projection (plan §8.10, Appendix A.1) against a real Room store: each appended
 * activity event applies its whole `stateAfter` wholesale, the monotonic guard drops a replayed
 * or out-of-order snapshot, and a malformed / future-schema / non-activity body is declined
 * silently — the chain already holds it (FR-17), only the partner card declines to apply it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PartnerActivityReceiverTest {

    private lateinit var context: Context
    private lateinit var recovery: RecoveryManager
    private lateinit var store: AppRulesStore
    private lateinit var receiver: PartnerActivityReceiver

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val healthStore = DatabaseHealthStore()
        recovery = RecoveryManager(
            context, FakeKeyManager(), ActivityLogKeyManager(context), RulesStoreKeyManager(context), healthStore,
        )
        recovery.resetAllData()
        val factory = AppRulesStoreFactory(context, recovery, healthStore)
        store = checkNotNull(factory.open())
        receiver = PartnerActivityReceiver(factory)
    }

    @After
    fun tearDown() {
        store.close()
        recovery.resetAllData()
    }

    @Test
    fun validSnapshot_isProjectedWholesale() = runBlocking {
        receiver.applyAppended(PAIR, snapshot(days = 30, relapses = 1, milestones = listOf(7, 30), coaching = 2), sequence = 4L)

        val projection = projection()
        assertEquals(30, projection.daysFree)
        assertEquals(1, projection.relapseCount)
        assertEquals("7,30", projection.milestonesReached)
        assertEquals(2, projection.coachingProgress)
        assertEquals(NOW, projection.lastEventOccurredAtMs)
        assertEquals(4L, projection.lastSequence)
        assertNull("a state event pins no message", projection.pinnedMessageText)
    }

    /** §4.6: wholesale application is what makes redelivery safe — a duplicate re-applies an
     *  identical snapshot, never a delta. */
    @Test
    fun duplicateDelivery_reappliesIdentically() = runBlocking {
        receiver.applyAppended(PAIR, snapshot(days = 7, relapses = 2), sequence = 2L)
        receiver.applyAppended(PAIR, snapshot(days = 7, relapses = 2), sequence = 2L)

        assertEquals(7, projection().daysFree)
        assertEquals(2, projection().relapseCount)
        assertEquals(2L, projection().lastSequence)
    }

    /** A late replay of an *older* snapshot must never roll the card backwards (the mailbox can
     *  deliver out of order; the guard is the sequence, not arrival time). */
    @Test
    fun lowerSequence_isIgnored() = runBlocking {
        receiver.applyAppended(PAIR, snapshot(days = 30), sequence = 9L)
        receiver.applyAppended(PAIR, snapshot(days = 3), sequence = 8L)

        assertEquals("the older snapshot is dropped", 30, projection().daysFree)
        assertEquals(9L, projection().lastSequence)
    }

    @Test
    fun malformedBody_isDeclinedWithoutTouchingTheProjection() = runBlocking {
        receiver.applyAppended(PAIR, snapshot(days = 5), sequence = 1L)

        receiver.applyAppended(PAIR, EventPayload(type = EventPayload.TYPE_STREAK_UPDATED, text = "", protocolVersion = 1, schemaVersion = 1), sequence = 2L)

        assertEquals("stateAfter-less body (FR-17) leaves the card alone", 5, projection().daysFree)
        assertEquals(1L, projection().lastSequence)
    }

    @Test
    fun futureSchema_isDeclinedButTheChainEventIsNotOurProblem() = runBlocking {
        receiver.applyAppended(PAIR, snapshot(days = 5), sequence = 1L)
        val future = snapshot(days = 99).copy(schemaVersion = EventPayload.ACTIVITY_SCHEMA_VERSION + 1)

        receiver.applyAppended(PAIR, future, sequence = 2L)

        assertEquals(5, projection().daysFree)
    }

    @Test
    fun nonActivityType_isLeftUntouched() = runBlocking {
        receiver.applyAppended(PAIR, EventPayload(type = EventPayload.TYPE_SYNC_REQUEST, text = ""), sequence = 1L)

        assertNull("control events never create a partner card", store.partnerActivityDao().projection(PAIR))
    }

    // --- Req 15 motivational message ----------------------------------------------------------

    @Test
    fun motivationalMessage_pinsTheTextAlongsideItsSnapshot_inOneApply() = runBlocking {
        receiver.applyAppended(PAIR, message("Proud of you."), sequence = 3L)

        val projection = projection()
        assertEquals("Proud of you.", projection.pinnedMessageText)
        assertEquals(NOW, projection.pinnedMessageAtMs)
        assertEquals("the snapshot rides along", 12, projection.daysFree)
    }

    /** §10.6: the composer-side bound is not a receiver-side guarantee — an oversized or blank
     *  body is refused at the boundary while its snapshot still applies (the message is the
     *  optional part, never the state). */
    @Test
    fun oversizedOrBlankMessage_isRefused_butItsSnapshotStillApplies() = runBlocking {
        receiver.applyAppended(PAIR, message("x".repeat(EventPayload.MAX_ACTIVITY_TEXT_LENGTH + 1)), sequence = 1L)
        receiver.applyAppended(PAIR, message("   "), sequence = 2L)

        val projection = projection()
        assertNull(projection.pinnedMessageText)
        assertEquals("both snapshots applied", 12, projection.daysFree)
        assertEquals(2L, projection.lastSequence)
    }

    @Test
    fun messageText_isNormalizedAndTrimmedBeforePinning() = runBlocking {
        receiver.applyAppended(PAIR, message("  Keep going  "), sequence = 1L)

        assertEquals("Keep going", projection().pinnedMessageText)
    }

    /** Req 15 home card: newest pinned message first across pairs (#12 — the card pages). */
    @Test
    fun pinnedMessages_returnNewestFirstAcrossPairs() = runBlocking {
        receiver.applyAppended("pair-a", message("older", atMs = NOW), sequence = 1L)
        receiver.applyAppended("pair-b", message("newer", atMs = NOW + 60_000), sequence = 1L)

        val rows = checkNotNull(store.partnerActivityDao().observePinnedMessages().first())

        assertEquals(listOf("pair-b", "pair-a"), rows.map { it.pairId })
        assertEquals(listOf("newer", "older"), rows.map { it.pinnedMessageText })
    }

    // --- Harness ------------------------------------------------------------------------------

    /** A missing row is itself the failure every caller cares about, so this asserts it exists. */
    private suspend fun projection() =
        checkNotNull(store.partnerActivityDao().projection(PAIR)) { "no projection row for $PAIR" }

    private fun snapshot(
        days: Int = 12,
        relapses: Int = 0,
        milestones: List<Int> = emptyList(),
        coaching: Int = 0,
    ) = activity(
        EventPayload.TYPE_STREAK_UPDATED,
        "",
        stateAt(NOW).copy(
            daysFree = days,
            relapseCount = relapses,
            milestonesReached = milestones,
            coachingProgress = coaching,
        ),
    )

    private fun message(text: String, atMs: Long = NOW) =
        activity(EventPayload.TYPE_MOTIVATIONAL_MESSAGE, text, stateAt(atMs))

    private fun activity(type: String, text: String, state: ActivityStateAfter) = EventPayload(
        type = type,
        text = text,
        protocolVersion = EventPayload.ACTIVITY_PROTOCOL_VERSION,
        schemaVersion = EventPayload.ACTIVITY_SCHEMA_VERSION,
        stateAfter = state,
    )

    private fun stateAt(atMs: Long) = ActivityStateAfter(
        daysFree = 12,
        relapseCount = 0,
        milestonesReached = emptyList(),
        coachingProgress = 0,
        eventOccurredAtMs = atMs,
    )

    private class FakeKeyManager : DatabaseKeyManager {
        override fun getOrCreateDek(): ByteArray = ByteArray(32)
        override fun classifyFailure(e: Throwable): KeyFailure = KeyFailure.DatabaseIssue
        override fun resetKeyMaterial() {}
    }

    private companion object {
        const val PAIR = "pair-1"
        const val NOW = 1_800_000_000_000L
    }
}
