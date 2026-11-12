package com.example.myexodus.accountability.delivery

import android.app.Application
import android.content.Context
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.accountability.crypto.EventCipher
import com.example.myexodus.accountability.enrollment.EnrollmentRecord
import com.example.myexodus.accountability.serialization.AccountabilityEventCategory
import com.example.myexodus.accountability.serialization.RuleChange
import com.example.myexodus.accountability.state.AccountabilityDatabase
import java.util.Base64
import kotlinx.coroutines.runBlocking
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

/**
 * Partner-delivery plan Phase 3 / §6.1 / §8.1: owner-bound capture, AES source retention,
 * existing-E2EE recipient envelopes, payload bounds, acknowledgement erasure, stale-key
 * regeneration and queue capacity. Every path runs against a real Room file database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PartnerEventRecorderTest {

    private lateinit var context: Context
    private lateinit var dbName: String
    private lateinit var db: AccountabilityDatabase
    private lateinit var outbox: ServiceEventOutbox
    private lateinit var clock: FakeClock
    private var currentUid: String? = "uid-1"

    private val cek = ByteArray(32) { (it + 1).toByte() }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbName = "test_${System.nanoTime()}.db"
        db = Room.databaseBuilder(context, AccountabilityDatabase::class.java, dbName).build()
        outbox = ServiceEventOutbox(db)
        clock = FakeClock()
        currentUid = "uid-1"
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun record_storesAnOwnerBoundPendingRow_thatTheExistingE2eeRoundTrips() = runBlocking {
        val enrollment = enrollment(7)
        val recorder = recorder(records = mutableListOf(enrollment))
        val payload = "sensitive block detail".toByteArray()

        val result = recorder.record(
            PartnerEventKind.BLOCK_ATTEMPT,
            mapOf("category" to AccountabilityEventCategory.BLOCKED_APP_ATTEMPT.wire),
            payload,
        ) as PartnerEventRecorder.RecordResult.Recorded

        assertEquals(1L, result.sequence)
        assertEquals(PendingPartnerEvent.STATUS_PENDING, result.status)
        val row = checkNotNull(outbox.event(result.eventId))
        assertEquals("uid-1", row.ownerUid)
        assertEquals(1L, row.sequence)
        assertEquals("{\"category\":\"BLOCKED_APP_ATTEMPT\"}", row.safeMetadataJson)
        assertEquals(PendingPartnerEvent.WIRE_NOT_SENT, row.wireOutcome)
        assertNotEquals("the stored source is sealed, never plaintext", payload.toList(), row.temporaryLocalCiphertext!!.toList())

        val protector = FakeProtector()
        assertArrayEquals(
            "the sealed source opens back to the original detail",
            payload,
            protector.open(
                PartnerEventRecorder.sourceSealAad(
                    result.eventId, row.installationId, "uid-1", PartnerEventKind.BLOCK_ATTEMPT,
                ),
                checkNotNull(row.temporaryLocalCiphertext),
            ),
        )

        val envelopes = ExistingE2eeRecipientEncryptor.decode(checkNotNull(row.recipientEnvelopesBlob))
        val keyId = ExistingE2eeRecipientEncryptor.recipientKeyIdOf(enrollment.partnerAgreementPubKey)
        assertEquals(listOf(keyId), envelopes.map { it.recipientEncryptionKeyId })
        assertEquals(listOf("sensitive block detail"), decrypt(envelopes, row, PartnerEventKind.BLOCK_ATTEMPT))

        val event = PartnerEventV1(
            eventId = result.eventId,
            installationId = row.installationId,
            ownerUid = "uid-1",
            sequence = row.sequence,
            kind = PartnerEventKind.BLOCK_ATTEMPT,
            deviceOccurredAtMs = row.deviceOccurredAtMs,
            recipientEnvelopes = envelopes,
            metadata = mapOf("category" to AccountabilityEventCategory.BLOCKED_APP_ATTEMPT.wire),
        )
        assertNull("the committed event satisfies the §8.2 envelope rules", PartnerEventProtocol.validate(event))
        assertNull(
            PartnerEventProtocol.validateSubmission(
                PartnerEventWireV1(keyId, event, "A".repeat(86)),
            ),
        )
        assertEquals("PENDING rows are the Phase 4 due set", listOf(result.eventId), outbox.dueRows("uid-1", 0, 10).map { it.eventId })
    }

    @Test
    fun sequenceIsPerOwner_andASecondAccountGetsItsOwnInstallation() = runBlocking {
        val recorder = recorder(records = mutableListOf(enrollment(1)))

        val first = recorder.record(PartnerEventKind.CHECK_IN) as PartnerEventRecorder.RecordResult.Recorded
        val second = recorder.record(PartnerEventKind.CHECK_IN) as PartnerEventRecorder.RecordResult.Recorded
        assertEquals(listOf(1L, 2L), listOf(first.sequence, second.sequence))

        currentUid = "uid-2"
        val other = recorder.record(PartnerEventKind.CHECK_IN) as PartnerEventRecorder.RecordResult.Recorded
        assertEquals("a new account starts its own sequence", 1L, other.sequence)
        assertNotEquals(
            checkNotNull(outbox.event(first.eventId)).installationId,
            checkNotNull(outbox.event(other.eventId)).installationId,
        )
        assertEquals(2, outbox.dueRows("uid-1", 0, 10).size)
        assertEquals(1, outbox.dueRows("uid-2", 0, 10).size)
    }

    @Test
    fun record_withoutAnAuthorizedRecipientKey_waitsInsteadOfSubmittingIncompleteCiphertext() = runBlocking {
        val recorder = recorder(records = mutableListOf())

        val result = recorder.record(PartnerEventKind.CHECK_IN, sensitivePayload = "x".toByteArray())
            as PartnerEventRecorder.RecordResult.Recorded

        assertEquals(PendingPartnerEvent.STATUS_WAITING_FOR_RECIPIENT_KEY, result.status)
        val row = checkNotNull(outbox.event(result.eventId))
        assertNull(row.recipientEnvelopesBlob)
        assertTrue("the row is never handed to the sender", outbox.dueRows("uid-1", 0, 10).isEmpty())
    }

    @Test
    fun record_rejectsDisallowedMetadata_beforeAnyStorage() = runBlocking {
        val recorder = recorder(records = mutableListOf(enrollment(1)))

        val result = recorder.record(PartnerEventKind.CHECK_IN, mapOf("note" to "free text"))

        assertEquals(
            PartnerEventRecorder.RecordResult.Rejected(PartnerEventIssue.METADATA_INVALID),
            result,
        )
        assertTrue(outbox.dueRows("uid-1", 0, 10).isEmpty())
    }

    @Test
    fun record_failsClosedWithoutASignedInOwner() = runBlocking {
        currentUid = null
        val result = recorder(records = mutableListOf(enrollment(1))).record(PartnerEventKind.CHECK_IN)
        assertEquals(PartnerEventRecorder.RecordResult.NoAccount, result)
        assertTrue(outbox.dueRows("uid-1", 0, 10).isEmpty())
    }

    @Test
    fun oversizedDetail_isReplacedByTheMarker_whileTheSealedSourceKeepsTheOriginal() = runBlocking {
        val enrollment = enrollment(3)
        val recorder = recorder(records = mutableListOf(enrollment))
        val payload = ByteArray(20 * 1024) { it.toByte() }

        val result = recorder.record(PartnerEventKind.CHECK_IN, sensitivePayload = payload)
            as PartnerEventRecorder.RecordResult.Recorded

        val row = checkNotNull(outbox.event(result.eventId))
        assertEquals(PendingPartnerEvent.STATUS_PENDING, result.status)
        assertEquals(PendingPartnerEvent.ERROR_PAYLOAD_TOO_LARGE, row.lastErrorCode)
        val envelopes = ExistingE2eeRecipientEncryptor.decode(checkNotNull(row.recipientEnvelopesBlob))
        assertEquals(
            listOf(String(ExistingE2eeRecipientEncryptor.OMITTED_DETAIL_MARKER)),
            decrypt(envelopes, row, PartnerEventKind.CHECK_IN),
        )
        val event = PartnerEventV1(
            eventId = result.eventId,
            installationId = row.installationId,
            ownerUid = "uid-1",
            sequence = row.sequence,
            kind = PartnerEventKind.CHECK_IN,
            deviceOccurredAtMs = row.deviceOccurredAtMs,
            recipientEnvelopes = envelopes,
        )
        assertNull("the marker keeps the event inside §8.2's 16 KiB bound", PartnerEventProtocol.validate(event))
        assertArrayEquals(
            "the local diagnostic retains the full source for regeneration",
            payload,
            FakeProtector().open(
                PartnerEventRecorder.sourceSealAad(
                    result.eventId, row.installationId, "uid-1", PartnerEventKind.CHECK_IN,
                ),
                checkNotNull(row.temporaryLocalCiphertext),
            ),
        )
    }

    @Test
    fun acknowledgement_isLimitedToAcceptedOutcomes_andErasesEveryCiphertext() = runBlocking {
        val recorder = recorder(records = mutableListOf(enrollment(1)))
        val recorded = recorder.record(PartnerEventKind.CHECK_IN, sensitivePayload = "detail".toByteArray())
            as PartnerEventRecorder.RecordResult.Recorded

        assertFalse("RETRYABLE may have been accepted — never erase on it", outbox.acknowledge(recorded.eventId, PartnerEventResultCode.RETRYABLE, 50))
        val retryable = checkNotNull(outbox.event(recorded.eventId))
        assertEquals(PendingPartnerEvent.STATUS_PENDING, retryable.status)
        assertTrue(retryable.temporaryLocalCiphertext != null && retryable.recipientEnvelopesBlob != null)

        assertTrue(outbox.acknowledge(recorded.eventId, PartnerEventResultCode.ACCEPTED, 100))
        val acked = checkNotNull(outbox.event(recorded.eventId))
        assertEquals(PendingPartnerEvent.STATUS_ACKED, acked.status)
        assertEquals(PendingPartnerEvent.WIRE_ACCEPTED, acked.wireOutcome)
        assertEquals(100L, acked.ackedServerAtMs)
        assertNull(acked.temporaryLocalCiphertext)
        assertNull(acked.recipientEnvelopesBlob)
        assertNull(acked.signedEnvelope)
        assertTrue("ACKED rows leave the due set", outbox.dueRows("uid-1", 0, 10).isEmpty())
    }

    @Test
    fun staleKeyRegeneration_replacesCiphertextForTheNewRecipient_andKeepsTheIdentity() = runBlocking {
        val records = mutableListOf(enrollment(1))
        val recorder = recorder(records = records)
        val recorded = recorder.record(PartnerEventKind.CHECK_IN, sensitivePayload = "detail".toByteArray())
            as PartnerEventRecorder.RecordResult.Recorded
        val before = checkNotNull(outbox.event(recorded.eventId))

        records.clear()
        records += enrollment(9)
        assertEquals(
            PartnerEventRecorder.RegenerationResult.Regenerated,
            recorder.regenerateRecipientEnvelopes(recorded.eventId, recipientKeyRevision = 2),
        )

        val after = checkNotNull(outbox.event(recorded.eventId))
        assertEquals("identity fields are untouched", before.eventId, after.eventId)
        assertEquals(before.sequence, after.sequence)
        assertEquals(before.deviceOccurredAtMs, after.deviceOccurredAtMs)
        assertEquals(2, after.recipientKeyRevision)
        val newKey = ExistingE2eeRecipientEncryptor.recipientKeyIdOf(byteArrayOf(9))
        val envelopes = ExistingE2eeRecipientEncryptor.decode(checkNotNull(after.recipientEnvelopesBlob))
        assertEquals(listOf(newKey), envelopes.map { it.recipientEncryptionKeyId })
        assertEquals(listOf("detail"), decrypt(envelopes, after, PartnerEventKind.CHECK_IN))
    }

    @Test
    fun regeneration_isClosedOnceAcceptanceWasPossible() = runBlocking {
        val recorder = recorder(records = mutableListOf(enrollment(1)))
        val retryable = insertRow(wireOutcome = PendingPartnerEvent.WIRE_RETRYABLE)
        val accepted = insertRow(wireOutcome = PendingPartnerEvent.WIRE_ACCEPTED, sequence = 101)

        assertEquals(
            PartnerEventRecorder.RegenerationResult.NotPermitted,
            recorder.regenerateRecipientEnvelopes(retryable.eventId),
        )
        assertEquals(
            PartnerEventRecorder.RegenerationResult.NotPermitted,
            recorder.regenerateRecipientEnvelopes(accepted.eventId),
        )
    }

    @Test
    fun regeneration_requiresOwnershipAndALiveSealedSource() = runBlocking {
        val protector = FakeProtector()
        val recorder = recorder(records = mutableListOf(enrollment(1)), protector = protector)

        val foreign = insertRow(ownerUid = "uid-2")
        assertEquals(
            PartnerEventRecorder.RegenerationResult.NotPermitted,
            recorder.regenerateRecipientEnvelopes(foreign.eventId),
        )

        val sourceGone = insertRow(sequence = 101)
        assertEquals(
            PartnerEventRecorder.RegenerationResult.SourceUnavailable,
            recorder.regenerateRecipientEnvelopes(sourceGone.eventId),
        )

        val staleEventId = "00000000-0000-4000-8000-000000000001"
        val source = protector.seal(
            PartnerEventRecorder.sourceSealAad(staleEventId, "inst-x", "uid-1", PartnerEventKind.CHECK_IN),
            "detail".toByteArray(),
        )
        insertRow(
            eventId = staleEventId,
            wireOutcome = PendingPartnerEvent.WIRE_RECIPIENT_KEY_STALE,
            source = source,
            sequence = 102,
        )
        assertEquals(
            "RECIPIENT_KEY_STALE is the one outcome that reopens the ciphertext window",
            PartnerEventRecorder.RegenerationResult.Regenerated,
            recorder.regenerateRecipientEnvelopes(staleEventId),
        )
    }

    @Test
    fun regeneration_opensTheSealedSourceWithTheRowsOwnSchemaVersion() = runBlocking {
        val protector = FakeProtector()
        val recorder = recorder(records = mutableListOf(enrollment(1)), protector = protector)
        val eventId = "00000000-0000-4000-8000-000000000002"
        val source = protector.seal(
            PartnerEventRecorder.sourceSealAad(
                eventId, "inst-x", "uid-1", PartnerEventKind.CHECK_IN, schemaVersion = 2,
            ),
            "detail".toByteArray(),
        )
        insertRow(
            eventId = eventId,
            wireOutcome = PendingPartnerEvent.WIRE_RECIPIENT_KEY_STALE,
            source = source,
            sequence = 103,
            schemaVersion = 2,
        )
        assertEquals(
            "the seal AAD must bind the row's stored schema version, not the running app's",
            PartnerEventRecorder.RegenerationResult.Regenerated,
            recorder.regenerateRecipientEnvelopes(eventId),
        )
    }

    @Test
    fun sealFailure_degradesRegeneration_butNeverDelivery() = runBlocking {
        val protector = FakeProtector().apply { failSeal = true }
        val recorder = recorder(records = mutableListOf(enrollment(1)), protector = protector)

        val recorded = recorder.record(PartnerEventKind.CHECK_IN, sensitivePayload = "detail".toByteArray())
            as PartnerEventRecorder.RecordResult.Recorded

        val row = checkNotNull(outbox.event(recorded.eventId))
        assertEquals("the accountability event is still recorded and sendable", PendingPartnerEvent.STATUS_PENDING, row.status)
        assertEquals(PartnerEventRecorder.ERROR_LOCAL_SEAL_FAILED, row.lastErrorCode)
        assertNull(row.temporaryLocalCiphertext)
        assertEquals(
            PartnerEventRecorder.RegenerationResult.SourceUnavailable,
            recorder.regenerateRecipientEnvelopes(recorded.eventId),
        )
    }

    @Test
    fun capacityExceeded_raisesAPersistentIncident_andNeverOverwritesAnUnacknowledgedRow() = runBlocking {
        val smallOutbox = ServiceEventOutbox(db, ServiceOutboxLimits(maxUnacknowledgedRows = 1))
        val recorder = PartnerEventRecorder(smallOutbox, FakeProtector(), encryptorFor(mutableListOf(enrollment(1))), clock, { currentUid })

        val first = recorder.record(PartnerEventKind.CHECK_IN, sensitivePayload = "one".toByteArray())
            as PartnerEventRecorder.RecordResult.Recorded
        val second = recorder.record(PartnerEventKind.CHECK_IN, sensitivePayload = "two".toByteArray())

        assertTrue(second is PartnerEventRecorder.RecordResult.CapacityExceeded)
        val incident = checkNotNull(smallOutbox.health())
        assertEquals(ServiceOutboxHealth.QUEUE_CAPACITY_EXCEEDED, incident.incidentCode)
        assertEquals(1, incident.pendingRowCount)
        val kept = checkNotNull(smallOutbox.event(first.eventId))
        assertEquals("the existing row was never overwritten", PendingPartnerEvent.STATUS_PENDING, kept.status)
        assertTrue(kept.temporaryLocalCiphertext != null)

        assertTrue(smallOutbox.acknowledge(first.eventId, PartnerEventResultCode.ACCEPTED, 200))
        val third = recorder.record(PartnerEventKind.CHECK_IN)
        assertTrue(third is PartnerEventRecorder.RecordResult.Recorded)
        assertNull("a successful enqueue clears the capacity incident", smallOutbox.health())
    }

    @Test
    fun ownerSwitch_suspendsTheOtherAccountsRows_andResumesOnlyTheActiveOwners() = runBlocking {
        val recorder = recorder(records = mutableListOf(enrollment(1)))
        val first = recorder.record(PartnerEventKind.CHECK_IN) as PartnerEventRecorder.RecordResult.Recorded
        currentUid = "uid-2"
        val second = recorder.record(PartnerEventKind.CHECK_IN) as PartnerEventRecorder.RecordResult.Recorded

        assertEquals(1, outbox.applyActiveOwner("uid-2"))
        assertEquals(PendingPartnerEvent.STATUS_OWNER_AUTH_REQUIRED, outbox.event(first.eventId)!!.status)
        assertEquals(PendingPartnerEvent.STATUS_PENDING, outbox.event(second.eventId)!!.status)
        assertTrue("a suspended row is never due", outbox.dueRows("uid-1", 0, 10).isEmpty())

        assertEquals(1, outbox.applyActiveOwner("uid-1"))
        assertEquals(PendingPartnerEvent.STATUS_PENDING, outbox.event(first.eventId)!!.status)
        assertEquals(PendingPartnerEvent.STATUS_OWNER_AUTH_REQUIRED, outbox.event(second.eventId)!!.status)
    }

    @Test
    fun cleanup_deletesOnlyAckedRowsPastTheSevenDayWindow() = runBlocking {
        val recorder = recorder(records = mutableListOf(enrollment(1)))
        val acked = recorder.record(PartnerEventKind.CHECK_IN) as PartnerEventRecorder.RecordResult.Recorded
        val pending = recorder.record(PartnerEventKind.CHECK_IN) as PartnerEventRecorder.RecordResult.Recorded
        outbox.acknowledge(acked.eventId, PartnerEventResultCode.ACCEPTED, 1_000)

        val retention = PendingPartnerEvent.ACKED_RETENTION_MILLIS
        assertEquals(0, outbox.cleanupAcked(1_000 + retention))
        assertEquals(1, outbox.cleanupAcked(1_001 + retention))
        assertNull(outbox.event(acked.eventId))
        assertEquals("unacknowledged rows are never deleted", PendingPartnerEvent.STATUS_PENDING, outbox.event(pending.eventId)!!.status)
    }

    /** §13.4 stable identity: a migrated record keeps the persisted legacy event id. */
    @Test
    fun requestedEventId_isTheStoredIdentity() = runBlocking {
        val recorder = recorder(records = mutableListOf(enrollment(1)))
        val legacyId = "11111111-1111-4111-8111-111111111111"

        val result = recorder.record(
            PartnerEventKind.RULE_CHANGE_ATTEMPT,
            safeMetadata = mapOf("ruleChange" to RuleChange.CREATED.wire),
            sensitivePayload = "payload".toByteArray(),
            requestedEventId = legacyId,
        ) as PartnerEventRecorder.RecordResult.Recorded

        assertEquals(legacyId, result.eventId)
        assertEquals(legacyId, outbox.event(legacyId)?.eventId)
    }

    /** §13.4 consent boundary: a rule routed to one pairing never fans out to the others. */
    @Test
    fun recipientPairId_sealsOnlyForTheRoutedPairing() = runBlocking {
        val recorder = recorder(records = mutableListOf(enrollment(1), enrollment(2)))

        val recorded = recorder.record(
            PartnerEventKind.RULE_CHANGE_ATTEMPT,
            safeMetadata = mapOf("ruleChange" to RuleChange.ASSIGNED.wire),
            sensitivePayload = "payload".toByteArray(),
            recipientPairId = "pair-1",
        ) as PartnerEventRecorder.RecordResult.Recorded

        val envelopes = ExistingE2eeRecipientEncryptor.decode(
            checkNotNull(checkNotNull(outbox.event(recorded.eventId)).recipientEnvelopesBlob),
        )
        assertEquals(
            listOf(ExistingE2eeRecipientEncryptor.recipientKeyIdOf(byteArrayOf(1))),
            envelopes.map { it.recipientEncryptionKeyId },
        )
    }

    @Test
    fun staleKeyRegeneration_keepsTheRoutedPairScope() = runBlocking {
        val records = mutableListOf(enrollment(1), enrollment(2))
        val recorder = recorder(records = records)
        val recorded = recorder.record(
            PartnerEventKind.RULE_CHANGE_ATTEMPT,
            safeMetadata = mapOf("ruleChange" to RuleChange.UPDATED.wire),
            sensitivePayload = "payload".toByteArray(),
            recipientPairId = "pair-1",
        ) as PartnerEventRecorder.RecordResult.Recorded

        // The partner re-enrolled under the same pair with a fresh agreement key.
        records.clear()
        records += enrollment(11).copy(pairId = "pair-1")
        records += enrollment(2)
        assertEquals(
            PartnerEventRecorder.RegenerationResult.Regenerated,
            recorder.regenerateRecipientEnvelopes(recorded.eventId, recipientKeyRevision = 2),
        )

        val envelopes = ExistingE2eeRecipientEncryptor.decode(
            checkNotNull(checkNotNull(outbox.event(recorded.eventId)).recipientEnvelopesBlob),
        )
        assertEquals(
            "the rotated key for pair-1 only — pair-2 is not part of this routing",
            listOf(ExistingE2eeRecipientEncryptor.recipientKeyIdOf(byteArrayOf(11))),
            envelopes.map { it.recipientEncryptionKeyId },
        )
    }

    private fun recorder(
        records: MutableList<EnrollmentRecord>,
        protector: ServicePayloadProtector = FakeProtector(),
    ) = PartnerEventRecorder(outbox, protector, encryptorFor(records), clock, { currentUid })

    private fun encryptorFor(records: MutableList<EnrollmentRecord>) =
        ExistingE2eeRecipientEncryptor(
            enrollments = { records.toList() },
            deriveCek = { cek },
            cipher = EventCipher(),
        )

    private suspend fun insertRow(
        eventId: String = java.util.UUID.randomUUID().toString(),
        ownerUid: String = "uid-1",
        status: String = PendingPartnerEvent.STATUS_PENDING,
        wireOutcome: String = PendingPartnerEvent.WIRE_NOT_SENT,
        source: ByteArray? = null,
        sequence: Long = 100,
        schemaVersion: Int = PartnerEventProtocol.VERSION,
    ): PendingPartnerEvent {
        val row = PendingPartnerEvent(
            eventId = eventId,
            installationId = "inst-x",
            ownerUid = ownerUid,
            sequence = sequence,
            schemaVersion = schemaVersion,
            kind = PartnerEventKind.CHECK_IN.wire,
            safeMetadataJson = "{}",
            temporaryLocalCiphertext = source,
            deviceOccurredAtMs = 10,
            status = status,
            wireOutcome = wireOutcome,
            createdLocalAtMs = 10,
        )
        db.serviceEventOutboxDao().insertEvent(row)
        return row
    }

    private fun decrypt(
        envelopes: List<RecipientEnvelopeV1>,
        row: PendingPartnerEvent,
        kind: PartnerEventKind,
    ): List<String> = envelopes.map { envelope ->
        String(
            EventCipher().decryptWithAad(
                cek,
                PartnerEventEnvelopeAad.of(
                    row.eventId, row.installationId, row.ownerUid, kind,
                    envelope.recipientEncryptionKeyId,
                ),
                Base64.getUrlDecoder().decode(envelope.payload),
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

    private class FakeClock : PartnerDeliveryClock {
        override fun wallClockMs(): Long = 1_000L
        override fun elapsedRealtimeMs(): Long = 500L
    }

    /** In-memory stand-in for the Android Keystore seal; the AAD prefix makes a mismatch fail. */
    private class FakeProtector : ServicePayloadProtector {
        var failSeal = false

        override fun seal(aad: ByteArray, plaintext: ByteArray): ByteArray {
            if (failSeal) error("keystore unavailable")
            return aad + byteArrayOf(0) + plaintext.reversedArray()
        }

        override fun open(aad: ByteArray, sealed: ByteArray): ByteArray {
            val prefix = aad + byteArrayOf(0)
            check(sealed.size >= prefix.size && sealed.copyOfRange(0, prefix.size).contentEquals(prefix))
            return sealed.copyOfRange(prefix.size, sealed.size).reversedArray()
        }
    }
}
