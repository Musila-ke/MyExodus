package com.example.myexodus.accountability.delivery

import android.app.Application
import android.content.Context
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.accountability.crypto.EventCipher
import com.example.myexodus.accountability.enrollment.EnrollmentRecord
import com.example.myexodus.accountability.serialization.AccountabilityEventCategory
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
import com.example.myexodus.data.db.entity.PartnerEventJournal
import java.util.Base64
import java.util.UUID
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
 * Phase 7 §13.3/§13.4 producer switch: staged flag polarity, stable legacy identity, per-pair
 * consent scoping, and the crash-window dispositions (an outbox row that already exists keeps
 * its signed path; a recorder that cannot record leaves the journal row for the next pass).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PartnerRuleChangeProducerTest {

    private lateinit var context: Context
    private lateinit var dbName: String
    private lateinit var db: AccountabilityDatabase
    private lateinit var outbox: ServiceEventOutbox
    private lateinit var factory: AppRulesStoreFactory

    private val cek = ByteArray(32) { (it + 1).toByte() }
    private val pairOne = enrollment(1)
    private val pairTwo = enrollment(2)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbName = "test_${System.nanoTime()}.db"
        db = Room.databaseBuilder(context, AccountabilityDatabase::class.java, dbName).build()
        outbox = ServiceEventOutbox(db)
        // The vault gate stays closed here on purpose: only the v1-row fallback reads the
        // rules store, and its degrade path is exactly what that test wants.
        val healthStore = DatabaseHealthStore()
        factory = AppRulesStoreFactory(
            context,
            RecoveryManager(
                context,
                FakeKeyManager(),
                ActivityLogKeyManager(context),
                RulesStoreKeyManager(context),
                healthStore,
            ),
            healthStore,
            vaultAccessAllowed = { false },
        )
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun stagedFlagOff_keepsTheLegacyTransport_andRecordsNothing() = runBlocking {
        val row = ruleRow(change = RuleChange.ASSIGNED)

        val transport = producer(listOf(pairOne, pairTwo), staged = { false }).transport(row)

        assertEquals(PartnerRuleChangeTransport.LEGACY, transport)
        assertNull(outbox.event(row.eventId))
    }

    @Test
    fun stagedFlagOn_recordsOneSignedCopy_underTheJournalIdentity_andRoutePair() = runBlocking {
        val ruleId = UUID.randomUUID()
        val row = ruleRow(ruleId = ruleId, change = RuleChange.DELETED)
        val transport = producer(listOf(pairOne, pairTwo), staged = { true })
            .transport(row)

        assertEquals(PartnerRuleChangeTransport.SIGNED, transport)
        val stored = checkNotNull(outbox.event(row.eventId))
        assertEquals("the journal UUID is the signed identity", row.eventId, stored.eventId)
        assertEquals(PartnerEventKind.RULE_CHANGE_ATTEMPT.wire, stored.kind)
        assertEquals(PendingPartnerEvent.STATUS_PENDING, stored.status)
        assertEquals("""{"ruleChange":"DELETED"}""", stored.safeMetadataJson)
        assertEquals("pair-1", stored.recipientPairId)

        val envelopes = ExistingE2eeRecipientEncryptor.decode(
            checkNotNull(stored.recipientEnvelopesBlob),
        )
        assertEquals(
            "only the routed pairing's agreement key is a recipient",
            listOf(ExistingE2eeRecipientEncryptor.recipientKeyIdOf(pairOne.partnerAgreementPubKey)),
            envelopes.map { it.recipientEncryptionKeyId },
        )
        val envelope = envelopes.single()
        val plaintext = EventCipher().decryptWithAad(
            cek,
            PartnerEventEnvelopeAad.of(
                stored.eventId,
                stored.installationId,
                stored.ownerUid,
                PartnerEventKind.RULE_CHANGE_ATTEMPT,
                envelope.recipientEncryptionKeyId,
            ),
            Base64.getUrlDecoder().decode(envelope.payload),
        )
        assertEquals(
            PartnerRuleChangePayload.Decoded(
                ruleId = ruleId,
                change = RuleChange.DELETED,
                identity = RuleIdentity("Instagram", RuleTargetType.APP),
                occurredAtMinute = MINUTE,
            ),
            PartnerRuleChangePayload.decodeOrNull(plaintext),
        )
    }

    @Test
    fun crashWindow_existingPendingRow_staysSigned_withoutRecordingAgain() = runBlocking {
        val row = ruleRow(change = RuleChange.CREATED)
        insertOutboxRow(row.eventId)

        val transport = producer(listOf(pairOne), staged = { error("must not re-read the flag") })
            .transport(row)

        assertEquals(PartnerRuleChangeTransport.SIGNED, transport)
        assertEquals(PARTIAL_ROW_SEQUENCE, outbox.event(row.eventId)?.sequence)
    }

    @Test
    fun anAlreadyAcknowledgedCopy_reportsAcknowledged_soTheRowCanBeCleared() = runBlocking {
        val row = ruleRow(change = RuleChange.DISABLED)
        insertOutboxRow(row.eventId, status = PendingPartnerEvent.STATUS_ACKED)

        val transport = producer(listOf(pairOne), staged = { error("must not re-read the flag") })
            .transport(row)

        assertEquals(PartnerRuleChangeTransport.ACKNOWLEDGED, transport)
    }

    @Test
    fun recorderCannotRecord_defers_andLeavesTheJournalRowForTheNextPass() = runBlocking {
        val row = ruleRow(change = RuleChange.UPDATED)

        val transport = producer(listOf(pairOne), staged = { true }, currentUid = null)
            .transport(row)

        assertEquals(PartnerRuleChangeTransport.DEFERRED, transport)
        assertNull(outbox.event(row.eventId))
    }

    @Test
    fun v1RowWithoutIdentity_stillMigrates_withTheNeutralName() = runBlocking {
        val ruleId = UUID.randomUUID()
        val row = PartnerEventJournal(
            eventId = UUID.randomUUID().toString(),
            category = AccountabilityEventCategory.RULE_CHANGED.wire,
            occurredAtMinute = MINUTE,
            ruleId = ruleId.toString(),
            ruleChange = RuleChange.ASSIGNED.wire,
            routePairId = pairOne.pairId,
            schemaVersion = 1,
            createdAtMs = 1_000,
            expiresAtMs = 1_000 + PartnerEventJournal.RETENTION_MILLIS,
        )

        val transport = producer(listOf(pairOne), staged = { true }).transport(row)

        assertEquals(PartnerRuleChangeTransport.SIGNED, transport)
        val stored = checkNotNull(outbox.event(row.eventId))
        val envelope = ExistingE2eeRecipientEncryptor.decode(
            checkNotNull(stored.recipientEnvelopesBlob),
        ).single()
        val decoded = PartnerRuleChangePayload.decodeOrNull(
            EventCipher().decryptWithAad(
                cek,
                PartnerEventEnvelopeAad.of(
                    stored.eventId,
                    stored.installationId,
                    stored.ownerUid,
                    PartnerEventKind.RULE_CHANGE_ATTEMPT,
                    envelope.recipientEncryptionKeyId,
                ),
                Base64.getUrlDecoder().decode(envelope.payload),
            ),
        )!!
        assertEquals(ruleId, decoded.ruleId)
        assertEquals(
            "the rule row is unreadable here, so the neutral name is used — never a guess",
            RuleIdentity("Restriction rule", RuleTargetType.APP),
            decoded.identity,
        )
    }

    private fun producer(
        enrollments: List<EnrollmentRecord>,
        staged: suspend () -> Boolean,
        currentUid: String? = "uid-1",
    ) = PartnerRuleChangeProducer(
        outbox = outbox,
        recorder = PartnerEventRecorder(
            outbox = outbox,
            protector = FakeProtector(),
            encryptor = ExistingE2eeRecipientEncryptor(
                enrollments = { enrollments },
                deriveCek = { cek },
                cipher = EventCipher(),
            ),
            clock = object : PartnerDeliveryClock {
                override fun wallClockMs(): Long = 1_000L
                override fun elapsedRealtimeMs(): Long = 500L
            },
            ownerUid = { currentUid },
        ),
        storeFactory = factory,
        staged = staged,
    )

    private fun ruleRow(
        ruleId: UUID = UUID.randomUUID(),
        change: RuleChange = RuleChange.ASSIGNED,
    ) = PartnerEventJournal.forRuleChange(
        eventId = UUID.randomUUID().toString(),
        ruleId = ruleId,
        change = change,
        routePairId = pairOne.pairId,
        occurredAtMinute = MINUTE,
        createdAtMs = 1_000,
        identity = RuleIdentity("Instagram", RuleTargetType.APP),
    )

    private suspend fun insertOutboxRow(
        eventId: String,
        status: String = PendingPartnerEvent.STATUS_PENDING,
    ) {
        db.serviceEventOutboxDao().insertEvent(
            PendingPartnerEvent(
                eventId = eventId,
                installationId = "inst-x",
                ownerUid = "uid-1",
                sequence = PARTIAL_ROW_SEQUENCE,
                schemaVersion = PartnerEventProtocol.VERSION,
                kind = PartnerEventKind.RULE_CHANGE_ATTEMPT.wire,
                safeMetadataJson = """{"ruleChange":"CREATED"}""",
                deviceOccurredAtMs = 10,
                status = status,
                createdLocalAtMs = 10,
            ),
        )
    }

    private fun enrollment(keySeed: Int) = EnrollmentRecord(
        pairId = "pair-$keySeed",
        mySenderKeyId = "a".repeat(64),
        partnerSigningKeyId = "b".repeat(64),
        partnerSigningPubKey = byteArrayOf(keySeed.toByte()),
        partnerAgreementPubKey = byteArrayOf(keySeed.toByte()),
        createdAtEpochMillis = 1,
    )

    private class FakeKeyManager : DatabaseKeyManager {
        override fun getOrCreateDek(): ByteArray = ByteArray(32)
        override fun classifyFailure(e: Throwable): KeyFailure = KeyFailure.DatabaseIssue
        override fun resetKeyMaterial() {}
    }

    private class FakeProtector : ServicePayloadProtector {
        override fun seal(aad: ByteArray, plaintext: ByteArray): ByteArray =
            aad + byteArrayOf(0) + plaintext.reversedArray()

        override fun open(aad: ByteArray, sealed: ByteArray): ByteArray {
            val prefix = aad + byteArrayOf(0)
            check(sealed.size >= prefix.size && sealed.copyOfRange(0, prefix.size).contentEquals(prefix))
            return sealed.copyOfRange(prefix.size, sealed.size).reversedArray()
        }
    }

    private companion object {
        const val MINUTE = 29_000_001L
        const val PARTIAL_ROW_SEQUENCE = 777L
    }
}
