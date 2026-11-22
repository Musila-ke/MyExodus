package com.example.myexodus.accountability.delivery

import android.app.Application
import android.content.Context
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.accountability.activity.PartnerActivityReceiver
import com.example.myexodus.accountability.enrollment.EnrollmentRecord
import com.example.myexodus.accountability.monitoring.MonitoringSnapshotReceiver
import com.example.myexodus.accountability.monitoring.PartnerPermissionLossReporter
import com.example.myexodus.accountability.plan.PartnerPlanReceiver
import com.example.myexodus.accountability.serialization.ActivityStateAfter
import com.example.myexodus.accountability.serialization.EventPayload
import com.example.myexodus.accountability.serialization.encodePayload
import com.example.myexodus.accountability.state.AccountabilityDatabase
import com.example.myexodus.data.db.ActivityLogKeyManager
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.DatabaseHealthStore
import com.example.myexodus.data.db.DatabaseKeyManager
import com.example.myexodus.data.db.KeyFailure
import com.example.myexodus.data.db.RecoveryManager
import com.example.myexodus.data.db.RulesStoreKeyManager
import com.example.myexodus.data.db.entity.PartnerEventJournal
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Owner battery 2026-11-12: a staged `BLOCK_ATTEMPT` delivered while app blocking fired must
 * leave the same Rules & blocks trail the legacy chain path leaves — staged deliveries write no
 * chain rows, so without this the notification had no row behind it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PartnerObservationProjectionTest {

    private lateinit var context: Context
    private lateinit var dbName: String
    private lateinit var db: AccountabilityDatabase
    private lateinit var factory: AppRulesStoreFactory
    private lateinit var recovery: RecoveryManager
    private val nowMs = 1_800_000_000_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbName = "test_${System.nanoTime()}.db"
        db = Room.databaseBuilder(context, AccountabilityDatabase::class.java, dbName).build()
        val health = DatabaseHealthStore()
        recovery = RecoveryManager(
            context, FakeKeyManager(), ActivityLogKeyManager(context), RulesStoreKeyManager(context), health,
        )
        recovery.resetAllData()
        factory = AppRulesStoreFactory(context, recovery, health)
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(dbName)
        recovery.resetAllData()
    }

    @Test
    fun deliveredBlockAttempt_becomesTheHistoryRow_andRedeliveryDedupes() = runBlocking {
        db.enrollmentDao().upsert(enrollment())
        val document = document(kind = PartnerEventKind.BLOCK_ATTEMPT.wire)
        val payload = payload("blocked:com.x|Morning batch", appLabel = "Calculator")

        projection().onDelivered(document, payload)
        projection().onDelivered(document, payload)

        val row = rows().single()
        assertEquals("evt-1", row.eventId)
        assertEquals("blocked:com.x|Morning batch", row.text)
        assertEquals("Calculator", row.appLabel)
        assertEquals(nowMs, row.occurredAtMs)
        assertEquals(nowMs + PartnerEventJournal.RETENTION_MILLIS, row.expiresAtMs)
    }

    @Test
    fun deliveredBlockAttempt_keepsTheDeviceOccurrenceTime_notTheDeliveryTime() = runBlocking {
        db.enrollmentDao().upsert(enrollment())
        // The block happened hours before the deferred staging: the feed row must sort at
        // the block instant, never after newer rule events that were delivered first.
        val blockedAt = nowMs - 6 * 60 * 60 * 1000L
        projection().onDelivered(
            document(
                kind = PartnerEventKind.BLOCK_ATTEMPT.wire,
                deviceOccurredAtMs = blockedAt,
            ),
            payload("blocked:com.x", appLabel = "Calculator"),
        )

        assertEquals(blockedAt, rows().single().occurredAtMs)
    }

    @Test
    fun deliveredConsentRevoke_becomesTheFeedRow() = runBlocking {
        db.enrollmentDao().upsert(enrollment())
        val revokedAt = nowMs - 60 * 60 * 1000L

        projection().onDelivered(
            document(kind = PartnerEventKind.ACCOUNTABILITY_UPDATE.wire),
            revoke(EventPayload.TYPE_AVAILABILITY_SHARING_STOPPED),
        )
        projection().onDelivered(
            document(
                eventId = "evt-2",
                kind = PartnerEventKind.ACCOUNTABILITY_UPDATE.wire,
                deviceOccurredAtMs = revokedAt,
            ),
            revoke(EventPayload.TYPE_STREAK_SHARING_STOPPED, habitLabel = "No porn"),
        )

        val rows = factory.open()!!.partnerSharingAlertDao().recent("pair-1", nowMs, 100)
            .sortedBy { it.eventId }
        assertEquals(listOf("evt-1", "evt-2"), rows.map { it.eventId })
        assertEquals(EventPayload.TYPE_AVAILABILITY_SHARING_STOPPED, rows[0].kind)
        assertEquals(null, rows[0].label)
        assertEquals("No porn", rows[1].label)
        // No device time on the wire (a pre-field backend): the delivery time stands in.
        assertEquals(nowMs, rows[0].occurredAtMs)
        assertEquals(revokedAt, rows[1].occurredAtMs)
        assertEquals(nowMs + PartnerEventJournal.RETENTION_MILLIS, rows[0].expiresAtMs)
    }

    @Test
    fun deliveredRelapseAndMilestone_becomeFeedRows_withSenderOccurrenceTime() = runBlocking {
        db.enrollmentDao().upsert(enrollment())
        val relapseAt = nowMs - 6 * 60 * 60 * 1000L

        projection().onDelivered(
            document(kind = PartnerEventKind.ACCOUNTABILITY_UPDATE.wire),
            activity(
                EventPayload.TYPE_RELAPSE_RECORDED,
                habitLabel = "No porn", endedDays = 12, occurredAtMs = relapseAt,
            ),
        )
        projection().onDelivered(
            document(eventId = "evt-2", kind = PartnerEventKind.ACCOUNTABILITY_UPDATE.wire),
            activity(EventPayload.TYPE_MILESTONE_REACHED, habitLabel = "No porn", milestoneDay = 7),
        )
        // A redelivered document must not duplicate the row.
        projection().onDelivered(
            document(eventId = "evt-2", kind = PartnerEventKind.ACCOUNTABILITY_UPDATE.wire),
            activity(EventPayload.TYPE_MILESTONE_REACHED, habitLabel = "No porn", milestoneDay = 7),
        )

        val rows = activityRows().sortedBy { it.eventId }
        assertEquals(listOf("evt-1", "evt-2"), rows.map { it.eventId })
        assertEquals(EventPayload.TYPE_RELAPSE_RECORDED, rows[0].kind)
        assertEquals("No porn", rows[0].habitLabel)
        assertEquals(12, rows[0].endedDays)
        assertEquals(null, rows[0].milestoneDay)
        // A backdated relapse keeps the sender's own occurrence time, not the arrival time.
        assertEquals(relapseAt, rows[0].occurredAtMs)
        assertEquals(EventPayload.TYPE_MILESTONE_REACHED, rows[1].kind)
        assertEquals(7, rows[1].milestoneDay)
        assertEquals(nowMs, rows[1].occurredAtMs)
        assertEquals(nowMs + PartnerEventJournal.RETENTION_MILLIS, rows[1].expiresAtMs)
    }

    @Test
    fun dailyStreakSnapshot_neverWritesAnActivityRow() = runBlocking {
        db.enrollmentDao().upsert(enrollment())

        projection().onDelivered(
            document(kind = PartnerEventKind.ACCOUNTABILITY_UPDATE.wire),
            activity(EventPayload.TYPE_STREAK_UPDATED, habitLabel = "No porn"),
        )

        assertTrue(activityRows().isEmpty())
    }

    @Test
    fun otherKinds_neverWriteAnAttemptRow() = runBlocking {
        db.enrollmentDao().upsert(enrollment())

        projection().onDelivered(
            document(kind = PartnerEventKind.ACCOUNTABILITY_UPDATE.wire),
            payload("blocked:com.x"),
        )
        projection().onDelivered(
            document(kind = PartnerEventKind.BLOCK_ATTEMPT.wire, eventId = null),
            payload("blocked:com.x"),
        )

        assertTrue(rows().isEmpty())
    }

    private fun projection() = PartnerObservationProjection(
        context = context,
        storeFactory = factory,
        enrollmentDao = db.enrollmentDao(),
        activityReceiver = PartnerActivityReceiver(factory),
        planReceiver = PartnerPlanReceiver(factory),
        monitoringReceiver = MonitoringSnapshotReceiver(factory),
        permissionLoss = PartnerPermissionLossReporter(factory, db.enrollmentDao(), { _, _, _ -> }),
        now = { nowMs },
    )

    private fun document(
        eventId: String? = "evt-1",
        kind: String,
        deviceOccurredAtMs: Long? = null,
    ) = PartnerMailboxDocument(
        messageId = "msg-1",
        eventId = eventId,
        senderInstallationId = "install-partner",
        senderOwnerUid = "partner-uid",
        kind = kind,
        recipientKeyId = "b".repeat(64),
        payload = "ciphertext",
        encoding = "EXISTING_E2EE",
        pairId = "pair-1",
        createdAtMs = nowMs,
        senderSequence = 7,
        deviceOccurredAtMs = deviceOccurredAtMs,
    )

    private fun payload(text: String, appLabel: String? = null) = encodePayload(
        EventPayload(type = EventPayload.TYPE_ACTIVITY, text = text, appLabel = appLabel),
    )

    private fun revoke(type: String, habitLabel: String? = null) = encodePayload(
        EventPayload(
            type = type,
            text = "",
            protocolVersion = EventPayload.ACTIVITY_PROTOCOL_VERSION,
            schemaVersion = EventPayload.ACTIVITY_SCHEMA_VERSION,
            stateAfter = habitLabel?.let {
                ActivityStateAfter(
                    daysFree = 3, relapseCount = 0, milestonesReached = emptyList(),
                    coachingProgress = 0, eventOccurredAtMs = nowMs, habitLabel = it,
                )
            },
        ),
    )

    private fun activity(
        type: String,
        habitLabel: String? = null,
        endedDays: Int? = null,
        milestoneDay: Int? = null,
        occurredAtMs: Long = nowMs,
    ) = encodePayload(
        EventPayload(
            type = type,
            text = "",
            protocolVersion = EventPayload.ACTIVITY_PROTOCOL_VERSION,
            schemaVersion = EventPayload.ACTIVITY_SCHEMA_VERSION,
            stateAfter = ActivityStateAfter(
                daysFree = 7, relapseCount = if (endedDays != null) 1 else 0,
                milestonesReached = milestoneDay?.let { listOf(it) } ?: emptyList(),
                coachingProgress = 0, eventOccurredAtMs = occurredAtMs,
                habitLabel = habitLabel, endedDays = endedDays, milestoneDay = milestoneDay,
            ),
        ),
    )

    private fun enrollment() = EnrollmentRecord(
        pairId = "pair-1",
        mySenderKeyId = "a".repeat(64),
        partnerSigningKeyId = "b".repeat(64),
        partnerSigningPubKey = byteArrayOf(1),
        partnerAgreementPubKey = byteArrayOf(1),
        createdAtEpochMillis = 1,
        partnerLabel = "Alex",
    )

    private suspend fun rows() =
        factory.open()!!.partnerAttemptAlertDao().recent("pair-1", nowMs, 100)

    private suspend fun activityRows() =
        factory.open()!!.partnerActivityAlertDao().recent("pair-1", nowMs, 100)

    private class FakeKeyManager : DatabaseKeyManager {
        override fun getOrCreateDek(): ByteArray = ByteArray(32)
        override fun classifyFailure(e: Throwable): KeyFailure = KeyFailure.DatabaseIssue
        override fun resetKeyMaterial() {}
    }
}
