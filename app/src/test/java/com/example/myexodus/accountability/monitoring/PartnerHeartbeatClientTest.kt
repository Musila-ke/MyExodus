package com.example.myexodus.accountability.monitoring

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.accountability.sync.PartnerSyncSignals
import com.example.myexodus.data.db.ActivityLogKeyManager
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.DatabaseHealthStore
import com.example.myexodus.data.db.DatabaseKeyManager
import com.example.myexodus.data.db.KeyFailure
import com.example.myexodus.data.db.RecoveryManager
import com.example.myexodus.data.db.RulesStoreKeyManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §7 Step 7 / §8.10 liveness client: persisted-before-send counters, the 15-minute floor plus
 * jitter, the stale-counter resync (never a lockout), the uid-scoped namespace, and the
 * once-per-transition App Standby explanation. The server side of the same contract lives in
 * `functions/test/heartbeat-logic.test.js`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PartnerHeartbeatClientTest {

    private lateinit var context: Context
    private lateinit var factory: AppRulesStoreFactory
    private lateinit var recovery: RecoveryManager

    private var nowMs = START
    private var uid = "uid-a"
    private var installId: String? = "install-abcdefgh"
    private var bucket: StandbyBucket? = null
    private var standbyReads = 0
    private val sent = mutableListOf<Long>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val health = DatabaseHealthStore()
        recovery = RecoveryManager(
            context, FakeKeyManager(), ActivityLogKeyManager(context), RulesStoreKeyManager(context), health,
        )
        recovery.resetAllData()
        factory = AppRulesStoreFactory(context, recovery, health)
    }

    @After
    fun tearDown() {
        recovery.resetAllData()
    }

    private fun client(
        reply: (counter: Long) -> HeartbeatReply = { HeartbeatReply(true, it, 1) },
        jitter: Long = 0,
        sendFails: Boolean = false,
    ) = PartnerHeartbeatClient(
        storeFactory = factory,
        uid = { uid },
        installId = { installId },
        send = { _, counter ->
            sent += counter
            if (sendFails) throw IllegalStateException("radio down")
            reply(counter)
        },
        standbyBucket = { standbyReads++; bucket },
        jitterMillis = { jitter },
        now = { nowMs },
    )

    private suspend fun row() = factory.open()!!.partnerLivenessDao().liveness(installId!!)

    @Test
    fun firstCheckIn_persistsCounterBeforeSend_andRecordsAcceptance() = runTest {
        val outcome = client().checkIn()

        assertEquals(PartnerHeartbeatClient.Outcome.ACCEPTED, outcome)
        assertEquals(listOf(1L), sent)
        val stored = row()!!
        assertEquals(1L, stored.counter)
        assertEquals(1L, stored.lastAcceptedCounter)
        assertEquals(nowMs, stored.lastAcceptedAtMs)
        assertEquals(nowMs, stored.lastSentAtMs)
        assertEquals("uid-a", stored.uid)
    }

    @Test
    fun withinTheWindow_skipsWithoutSending() = runTest {
        val client = client()
        client.checkIn()
        nowMs += PartnerHeartbeatClient.MIN_INTERVAL_MILLIS - 1

        assertEquals(PartnerHeartbeatClient.Outcome.SKIPPED, client.checkIn())
        assertEquals("one send inside one window", listOf(1L), sent)
        assertEquals(1L, row()!!.counter)
    }

    @Test
    fun atTheWindowBoundary_sendsTheNextCounter() = runTest {
        val client = client()
        client.checkIn()
        nowMs += PartnerHeartbeatClient.MIN_INTERVAL_MILLIS

        assertEquals(PartnerHeartbeatClient.Outcome.ACCEPTED, client.checkIn())
        assertEquals(listOf(1L, 2L), sent)
        assertEquals(2L, row()!!.lastAcceptedCounter)
    }

    /** Plan §7 Step 7 jitter: the periodic beat lands 15 min + [0, 2 min) — it can only
     *  stretch the floor, never shorten it. */
    @Test
    fun jitterOnlyStretchesTheFloor() = runTest {
        val client = client(jitter = PartnerHeartbeatClient.JITTER_MAX_MILLIS)
        client.checkIn()

        nowMs += PartnerHeartbeatClient.MIN_INTERVAL_MILLIS +
            PartnerHeartbeatClient.JITTER_MAX_MILLIS - 1
        assertEquals(PartnerHeartbeatClient.Outcome.SKIPPED, client.checkIn())
        assertEquals(listOf(1L), sent)

        nowMs += 1
        assertEquals(PartnerHeartbeatClient.Outcome.ACCEPTED, client.checkIn())
        assertEquals(listOf(1L, 2L), sent)
    }

    @Test
    fun forcedCheckIn_bypassesTheWindow() = runTest {
        val client = client()
        client.checkIn()
        nowMs += 1_000

        assertEquals(PartnerHeartbeatClient.Outcome.ACCEPTED, client.checkIn(force = true))
        assertEquals("service start / material change is exempt", listOf(1L, 2L), sent)
    }

    @Test
    fun failedSend_burnsTheNumber_andTheNextSendNeverReusesIt() = runTest {
        val client = client(sendFails = true)

        assertEquals(PartnerHeartbeatClient.Outcome.FAILED, client.checkIn())
        val burned = row()!!
        assertEquals("persisted before the call, kept on failure", 1L, burned.counter)
        assertEquals(0L, burned.lastAcceptedCounter)
        assertEquals("nothing was accepted", null, client.lastAcceptedAt())

        nowMs += PartnerHeartbeatClient.MIN_INTERVAL_MILLIS
        val next = client(reply = { HeartbeatReply(true, it, 1) })
        assertEquals(PartnerHeartbeatClient.Outcome.ACCEPTED, next.checkIn())
        assertEquals("counter 1 is never reused", listOf(1L, 2L), sent)
    }

    /** §8.10: a client that lost its counter (restore, same FID) resumes from the server's
     *  number — the server's stale answer is a normal response, not a permanent lockout. */
    @Test
    fun staleServerCounter_resyncsOntoTheServer() = runTest {
        val client = client(reply = { HeartbeatReply(false, 5, 3) })

        assertEquals(PartnerHeartbeatClient.Outcome.RESYNCED, client.checkIn())
        val stored = row()!!
        assertEquals(5L, stored.counter)
        assertEquals(5L, stored.lastAcceptedCounter)
        assertEquals("a rejected beat is never liveness", 0L, stored.lastAcceptedAtMs)
        assertNull(client.lastAcceptedAt())

        nowMs += PartnerHeartbeatClient.MIN_INTERVAL_MILLIS
        client.checkIn()
        assertEquals("resume above the server's counter", listOf(1L, 6L), sent)
    }

    /** §8.10: a refused beat carries the server's counter forward but never unproves the
     *  acceptance before it — `lastAcceptedAt()` is the reader later steps trust. */
    @Test
    fun rateLimitedReply_neverErasesThePriorAcceptance() = runTest {
        val client = client(
            reply = { counter ->
                if (counter == 2L) HeartbeatReply(false, 1, 1) else HeartbeatReply(true, counter, 1)
            },
        )
        assertEquals(PartnerHeartbeatClient.Outcome.ACCEPTED, client.checkIn())
        assertEquals(nowMs, client.lastAcceptedAt())

        assertEquals(PartnerHeartbeatClient.Outcome.RESYNCED, client.checkIn(force = true))
        assertEquals("the earlier acceptance is still the truth", nowMs, client.lastAcceptedAt())
        assertEquals(1L, row()!!.lastAcceptedCounter)

        nowMs += PartnerHeartbeatClient.MIN_INTERVAL_MILLIS
        assertEquals(PartnerHeartbeatClient.Outcome.ACCEPTED, client.checkIn())
        assertEquals("the burned number is never reused", listOf(1L, 2L, 3L), sent)
    }

    /** §8.10 / §9: the namespace is (uid, installId). An anonymous identity rotation is a new
     *  server document, so the counter restarts at 1 instead of colliding with the old uid. */
    @Test
    fun uidRotation_startsAFreshCounterNamespace() = runTest {
        val client = client()
        client.checkIn()
        nowMs += PartnerHeartbeatClient.MIN_INTERVAL_MILLIS
        client.checkIn()
        assertEquals(listOf(1L, 2L), sent)

        uid = "uid-b"
        nowMs += PartnerHeartbeatClient.MIN_INTERVAL_MILLIS
        client.checkIn()

        assertEquals("a new uid is a new namespace, not a stale counter", listOf(1L, 2L, 1L), sent)
        assertEquals("uid-b", row()!!.uid)
    }

    @Test
    fun unresolvedIdentity_skipsWithoutTouchingTheStore() = runTest {
        uid = ""
        installId = null

        assertEquals(PartnerHeartbeatClient.Outcome.SKIPPED, client().checkIn())
        assertEquals(emptyList<Long>(), sent)
    }

    /** §7 Step 7 names startup as a standby read point: a cold WorkManager-only process must
     *  not wait out the 6-hour interval before it can explain a constrained bucket. */
    @Test
    fun startup_inspectsStandby_oncePerProcess_evenInsideTheInterval() = runTest {
        bucket = StandbyBucket.WORKING_SET
        val first = client()
        first.checkIn()
        assertEquals("the first check-in of a process is a startup read", 1, standbyReads)

        nowMs += PartnerHeartbeatClient.MIN_INTERVAL_MILLIS
        first.checkIn()
        assertEquals("a later pass stays inside the 6-hour interval", 1, standbyReads)

        val restarted = client()
        nowMs += PartnerHeartbeatClient.MIN_INTERVAL_MILLIS
        restarted.checkIn()
        assertEquals("a new process is a new startup", 2, standbyReads)
    }

    @Test
    fun standbyTransition_isExplainedOnce_andRearmsAfterRecovery() = runTest {
        val notices = mutableListOf<StandbyBucket>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            PartnerSyncSignals.standbyNotice.collect { notices += it }
        }
        val client = client()
        bucket = StandbyBucket.RARE
        client.checkIn(force = true)
        assertEquals("a constrained group is named once", listOf(StandbyBucket.RARE), notices)

        client.checkIn(force = true)
        assertEquals("repetition is not a transition", listOf(StandbyBucket.RARE), notices)

        bucket = StandbyBucket.ACTIVE
        client.checkIn(force = true)
        assertEquals("recovery emits nothing", listOf(StandbyBucket.RARE), notices)

        bucket = StandbyBucket.RESTRICTED
        client.checkIn(force = true)
        assertEquals(
            "the next decline re-arms the explanation",
            listOf(StandbyBucket.RARE, StandbyBucket.RESTRICTED),
            notices,
        )
    }

    @Test
    fun parseHeartbeatReply_rejectsMalformedAnswers() {
        assertEquals(
            HeartbeatReply(accepted = true, serverCounter = 4, generation = 2),
            parseHeartbeatReply(mapOf("accepted" to true, "serverCounter" to 4, "generation" to 2)),
        )
        assertNull(parseHeartbeatReply(null))
        assertNull(parseHeartbeatReply("not a map"))
        assertNull(parseHeartbeatReply(mapOf("accepted" to true, "generation" to 2)))
        assertNull(parseHeartbeatReply(mapOf("accepted" to true, "serverCounter" to 4)))
        assertNull(
            "negative counters are unusable",
            parseHeartbeatReply(mapOf("accepted" to true, "serverCounter" to -1L, "generation" to 1)),
        )
        assertNull(
            "negative generations are unusable",
            parseHeartbeatReply(mapOf("accepted" to true, "serverCounter" to 1, "generation" to -1)),
        )
    }

    private class FakeKeyManager : DatabaseKeyManager {
        override fun getOrCreateDek(): ByteArray = ByteArray(32)
        override fun classifyFailure(e: Throwable): KeyFailure = KeyFailure.DatabaseIssue
        override fun resetKeyMaterial() {}
    }

    private companion object {
        const val START = 1_800_000_000_000L
    }
}
