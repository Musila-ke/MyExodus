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

    private fun document(eventId: String? = "evt-1", kind: String) = PartnerMailboxDocument(
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
    )

    private fun payload(text: String, appLabel: String? = null) = encodePayload(
        EventPayload(type = EventPayload.TYPE_ACTIVITY, text = text, appLabel = appLabel),
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

    private class FakeKeyManager : DatabaseKeyManager {
        override fun getOrCreateDek(): ByteArray = ByteArray(32)
        override fun classifyFailure(e: Throwable): KeyFailure = KeyFailure.DatabaseIssue
        override fun resetKeyMaterial() {}
    }
}
