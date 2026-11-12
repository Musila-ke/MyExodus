package com.example.myexodus.accountability.rulecontrol

import android.app.Application
import android.content.Context
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.accountability.delivery.PartnerEventKind
import com.example.myexodus.accountability.delivery.PartnerMailboxDocument
import com.example.myexodus.accountability.delivery.PartnerRuleChangePayload
import com.example.myexodus.accountability.enrollment.EnrollmentRecord
import com.example.myexodus.accountability.serialization.RuleChange
import com.example.myexodus.accountability.serialization.RuleIdentity
import com.example.myexodus.accountability.serialization.RuleTargetType
import com.example.myexodus.accountability.state.AccountabilityDatabase
import com.example.myexodus.data.db.ActivityLogKeyManager
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.DatabaseHealthStore
import com.example.myexodus.data.db.DatabaseKeyManager
import com.example.myexodus.data.db.KeyFailure
import com.example.myexodus.data.db.RecoveryManager
import com.example.myexodus.data.db.RulesStoreKeyManager
import com.example.myexodus.data.db.VaultLockedException
import com.example.myexodus.data.db.entity.PartnerEventJournal
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §13.4 recipient half of the staged rule transport: a delivered signed `RULE_CHANGE_ATTEMPT`
 * projects into exactly the feed row and notification the legacy chain path wrote, redelivery is
 * a no-op, and a closed vault propagates so the mailbox document stays unreceipted until the
 * normal foreground unlock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PartnerRuleChangeProjectionTest {

    private lateinit var context: Context
    private lateinit var dbName: String
    private lateinit var db: AccountabilityDatabase
    private lateinit var factory: AppRulesStoreFactory
    private lateinit var recovery: RecoveryManager
    private var nowMs = 1_800_000_000_000L
    private val ruleId = UUID.fromString("11111111-2222-4333-8444-555555555555")
    private val notifications = mutableListOf<List<Any?>>()

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
    fun deliveredRuleChange_becomesTheFeedRow_andNotifiesOnce() = runBlocking {
        db.enrollmentDao().upsert(enrollment())
        val document = document(eventId = UUID.randomUUID().toString())

        projection().onDelivered(document, payload(RuleChange.ASSIGNED))

        val row = rows("pair-1").single()
        assertEquals(document.eventId, row.eventId)
        assertEquals(ruleId.toString(), row.ruleId)
        assertEquals(RuleChange.ASSIGNED.wire, row.ruleChange)
        assertEquals("Instagram", row.ruleLabel)
        assertEquals(RuleTargetType.APP.wire, row.ruleTargetType)
        assertEquals(MINUTE, row.occurredAtMinute)
        assertEquals(nowMs, row.receivedAtMs)
        assertEquals(nowMs + PartnerEventJournal.RETENTION_MILLIS, row.expiresAtMs)
        assertEquals(
            listOf(listOf("pair-1", "Alex", RuleChange.ASSIGNED, IDENTITY)),
            notifications,
        )
    }

    @Test
    fun redelivery_isANoOp_andNeverNotifiesTwice() = runBlocking {
        db.enrollmentDao().upsert(enrollment())
        val document = document(eventId = UUID.randomUUID().toString())
        val payload = payload(RuleChange.DELETED)

        projection().onDelivered(document, payload)
        projection().onDelivered(document, payload)

        assertEquals(1, rows("pair-1").size)
        assertEquals(1, notifications.size)
    }

    @Test
    fun otherKinds_andMalformedDocuments_areIgnoredWithoutTouchingTheStore() = runBlocking {
        db.enrollmentDao().upsert(enrollment())
        val locked = projection(vaultAccessAllowed = { false })

        locked.onDelivered(document(kind = PartnerEventKind.BLOCK_ATTEMPT.wire), payload(RuleChange.CREATED))
        locked.onDelivered(document().copy(pairId = null), payload(RuleChange.CREATED))
        locked.onDelivered(document().copy(eventId = null), payload(RuleChange.CREATED))
        locked.onDelivered(document(), byteArrayOf(0x7f))

        assertTrue(rows("pair-1").isEmpty())
        assertTrue(notifications.isEmpty())
    }

    @Test
    fun closedVault_propagates_soTheMailboxDocumentStaysUnreceipted() {
        runBlocking { db.enrollmentDao().upsert(enrollment()) }
        val locked = projection(vaultAccessAllowed = { false })

        assertThrows(VaultLockedException::class.java) {
            runBlocking { locked.onDelivered(document(), payload(RuleChange.UPDATED)) }
        }
        runBlocking { assertTrue(rows("pair-1").isEmpty()) }
        assertTrue(notifications.isEmpty())
    }

    private fun projection(vaultAccessAllowed: () -> Boolean = { true }) = PartnerRuleChangeProjection(
        storeFactory = if (vaultAccessAllowed()) {
            factory
        } else {
            AppRulesStoreFactory(context, recovery, DatabaseHealthStore(), vaultAccessAllowed = vaultAccessAllowed)
        },
        enrollmentDao = db.enrollmentDao(),
        notify = { pairId, partnerName, change, identity ->
            notifications += listOf(pairId, partnerName, change, identity)
        },
        now = { nowMs },
    )

    private fun document(
        eventId: String? = UUID.randomUUID().toString(),
        kind: String = PartnerEventKind.RULE_CHANGE_ATTEMPT.wire,
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
    )

    private fun payload(change: RuleChange) =
        PartnerRuleChangePayload.encode(ruleId, change, IDENTITY, MINUTE)

    private fun enrollment() = EnrollmentRecord(
        pairId = "pair-1",
        mySenderKeyId = "a".repeat(64),
        partnerSigningKeyId = "b".repeat(64),
        partnerSigningPubKey = byteArrayOf(1),
        partnerAgreementPubKey = byteArrayOf(1),
        createdAtEpochMillis = 1,
        partnerLabel = "Alex",
    )

    private suspend fun rows(pairId: String) =
        factory.open()!!.partnerRuleChangeDao().recent(pairId, nowMs, 100)

    private class FakeKeyManager : DatabaseKeyManager {
        override fun getOrCreateDek(): ByteArray = ByteArray(32)
        override fun classifyFailure(e: Throwable): KeyFailure = KeyFailure.DatabaseIssue
        override fun resetKeyMaterial() {}
    }

    private companion object {
        const val MINUTE = 29_000_001L
        val IDENTITY = RuleIdentity(label = "Instagram", targetType = RuleTargetType.APP)
    }
}
