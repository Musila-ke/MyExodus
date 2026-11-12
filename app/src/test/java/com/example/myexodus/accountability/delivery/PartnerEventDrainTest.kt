package com.example.myexodus.accountability.delivery

import android.app.Application
import android.content.Context
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.accountability.crypto.EventCipher
import com.example.myexodus.accountability.enrollment.EnrollmentRecord
import com.example.myexodus.accountability.state.AccountabilityDatabase
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.functions.FirebaseFunctions
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Partner-delivery plan Phase 4 / §4.3 / §8.2 / §8.3.8: the drain against a real Room queue.
 * Each scenario pins one rule the plan calls out — partial-response reconciliation, exact-byte
 * resend, exponential backoff, the fixed quota defer, lease reclaim, stale-key regeneration,
 * re-enrollment suspension, and terminal rejections.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PartnerEventDrainTest {

    private lateinit var context: Context
    private lateinit var dbName: String
    private lateinit var db: AccountabilityDatabase
    private lateinit var outbox: ServiceEventOutbox
    private lateinit var clock: MutableClock
    private lateinit var keyStore: FakeKeyStore
    private lateinit var transport: ScriptedTransport
    private lateinit var registrar: ScriptedRegistrar
    private lateinit var recorder: PartnerEventRecorder
    private lateinit var drain: PartnerEventDrain
    private lateinit var records: MutableList<EnrollmentRecord>
    private var currentUid: String? = "uid-1"
    private var regenerations = 0
    private var enrollmentRecipientKeyId: String? = null

    private val cek = ByteArray(32) { (it + 1).toByte() }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbName = "drain_${System.nanoTime()}.db"
        db = Room.databaseBuilder(context, AccountabilityDatabase::class.java, dbName).build()
        outbox = ServiceEventOutbox(db)
        clock = MutableClock()
        currentUid = "uid-1"
        enrollmentRecipientKeyId = null
        keyStore = FakeKeyStore()
        transport = ScriptedTransport()
        records = mutableListOf(enrollment(1))
        recorder = PartnerEventRecorder(
            outbox, FakeProtector(), encryptorFor(records), clock, { currentUid },
        )
        registrar = ScriptedRegistrar(context, keyStore, outbox)
        regenerations = 0
        drain = PartnerEventDrain(
            outbox = outbox,
            keyStore = keyStore,
            transport = transport,
            registrar = registrar,
            recorder = recorder,
            clock = clock,
            ownerUid = { currentUid },
            jitterMs = { 0 },
            onRecipientKeyRegenerated = { regenerations++ },
            currentRecipientKeyId = { enrollmentRecipientKeyId },
        )
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun pausedSync_claimsNothing_andRetainsTheRowForTheNextEnabledPass() = runBlocking {
        registered()
        record()
        val paused = PartnerEventDrain(
            outbox = outbox,
            keyStore = keyStore,
            transport = transport,
            registrar = registrar,
            recorder = recorder,
            clock = clock,
            ownerUid = { currentUid },
            syncEnabled = { false },
            jitterMs = { 0 },
        )

        val result = paused.run(allowRegistration = false)

        assertEquals(0, result.claimed)
        assertEquals(0, transport.submitted.size)
        assertEquals("a paused sync never schedules or drops the row", 1, outbox.dueRows("uid-1", clock.nowMs, 10).size)
        assertEquals(1, drain.run(allowRegistration = false).accepted)
    }

    @Test
    fun acceptedBatch_acknowledgesEveryRow_andNeverResubmits() = runBlocking {
        registered()
        record()
        record()

        val result = drain.run(allowRegistration = false)

        assertEquals(2, result.claimed)
        assertEquals(2, result.accepted)
        assertEquals(0, result.retried)
        assertEquals(1, transport.submitted.size)
        assertEquals(2, transport.submitted.single().events.size)
        val rows = outbox.dueRows("uid-1", clock.nowMs, 10)
        assertTrue("ACKED rows leave the due set", rows.isEmpty())

        assertTrue(outbox.dueRows("uid-1", 0, 10).isEmpty())
        assertEquals(0, drain.run(allowRegistration = false).claimed)
        assertEquals("a settled queue makes no network call", 1, transport.submitted.size)
    }

    @Test
    fun partialResponse_defersOnlyTheMissingIndex_asUnknown_andResendsTheExactBytes() = runBlocking {
        registered()
        val first = record()
        val second = record()
        transport.responder = { batch ->
            PartnerEventBatchResultV1(
                listOf(
                    PartnerEventResultV1(
                        0, batch.events[0].event.eventId, PartnerEventResultCode.ACCEPTED,
                    ),
                ),
            )
        }

        val result = drain.run(allowRegistration = false)

        assertEquals(1, result.accepted)
        assertEquals(1, result.retried)
        val accepted = checkNotNull(outbox.event(first.eventId))
        assertEquals(PendingPartnerEvent.STATUS_ACKED, accepted.status)
        val row = checkNotNull(outbox.event(second.eventId))
        assertEquals("the unanswered index is still pending", PendingPartnerEvent.STATUS_PENDING, row.status)
        assertEquals(PendingPartnerEvent.WIRE_UNKNOWN, row.wireOutcome)
        assertEquals("RESULT_MISSING", row.lastErrorCode)
        assertEquals(clock.nowMs + 30_000, row.nextAttemptAtMs)
        assertNotNull("the signed bytes are kept unchanged", row.signedEnvelope)

        clock.nowMs += 30_000
        transport.responder = ScriptedTransport.ACCEPT_ALL
        val retried = drain.run(allowRegistration = false)

        assertEquals(1, retried.accepted)
        assertEquals(2, transport.submitted.size)
        val resent = transport.submitted[1].events.single()
        assertArrayEquals(
            "an ambiguous outcome resends the identical event record",
            transport.submitted[0].events[1].event.toJcsValue().toString().toByteArray(),
            resent.event.toJcsValue().toString().toByteArray(),
        )
        assertEquals(
            "the signature is reused, never re-minted",
            transport.submitted[0].events[1].signature,
            resent.signature,
        )
        assertEquals("one signature per distinct row; the retry reuses", 2, keyStore.signCount)
    }

    @Test
    fun transportFailure_backsOffExponentially_andKeepsTheSignature() = runBlocking {
        registered()
        val recorded = record()
        transport.responder = { throw IllegalStateException("offline") }

        val first = drain.run(allowRegistration = false)
        assertEquals(1, first.retried)
        var row = checkNotNull(outbox.event(recorded.eventId))
        assertEquals(PendingPartnerEvent.STATUS_PENDING, row.status)
        assertEquals(PendingPartnerEvent.WIRE_UNKNOWN, row.wireOutcome)
        assertEquals("TRANSPORT_UNKNOWN", row.lastErrorCode)
        assertEquals(1, row.attempts)
        assertEquals(clock.nowMs + 30_000, row.nextAttemptAtMs)

        clock.nowMs += 30_000
        drain.run(allowRegistration = false)
        row = checkNotNull(outbox.event(recorded.eventId))
        assertEquals(2, row.attempts)
        assertEquals("second retry doubles the base delay", clock.nowMs + 60_000, row.nextAttemptAtMs)
        assertEquals(1, keyStore.signCount)

        clock.nowMs += 60_000
        transport.responder = ScriptedTransport.ACCEPT_ALL
        val settled = drain.run(allowRegistration = false)
        assertEquals(1, settled.accepted)
        assertEquals(
            "every attempt after the first reuses the same signed bytes",
            transport.submitted[0].events.single().signature,
            transport.submitted[2].events.single().signature,
        )
    }

    @Test
    fun quotaRejection_usesTheFixedLongDefer_withNoRetryAfterOnCallables() = runBlocking {
        registered()
        val recorded = record()
        transport.responder = { throw DeliveryRateLimitedException(RuntimeException("quota")) }

        val result = drain.run(allowRegistration = false)

        assertEquals(1, result.retried)
        val row = checkNotNull(outbox.event(recorded.eventId))
        assertEquals("RESOURCE_EXHAUSTED", row.lastErrorCode)
        assertEquals(clock.nowMs + 300_000, row.nextAttemptAtMs)
    }

    @Test
    fun staleKey_regeneratesForTheAuthorizedKey_andIsDueAgainImmediately() = runBlocking {
        registered()
        val recorded = record()
        val before = checkNotNull(outbox.event(recorded.eventId))

        records.clear()
        records += enrollment(9)
        val newKeyId = ExistingE2eeRecipientEncryptor.recipientKeyIdOf(byteArrayOf(9))
        transport.responder = { batch ->
            PartnerEventBatchResultV1(
                listOf(
                    PartnerEventResultV1(
                        index = 0,
                        eventId = batch.events[0].event.eventId,
                        code = PartnerEventResultCode.RECIPIENT_KEY_STALE,
                        currentRecipientKeys = listOf(RecipientKeyRef(newKeyId, 5, null)),
                    ),
                ),
            )
        }

        val result = drain.run(allowRegistration = false)

        assertEquals(1, result.retried)
        assertEquals("§14 stale re-encryption count", 1, regenerations)
        val row = checkNotNull(outbox.event(recorded.eventId))
        assertEquals(PendingPartnerEvent.STATUS_PENDING, row.status)
        assertEquals(5, row.recipientKeyRevision)
        assertEquals(PendingPartnerEvent.WIRE_NOT_SENT, row.wireOutcome)
        assertEquals("the identity is untouched", before.sequence, row.sequence)
        assertEquals(before.deviceOccurredAtMs, row.deviceOccurredAtMs)
        assertNull("successful regeneration reopens the send window", row.nextAttemptAtMs)
        val envelopes = ExistingE2eeRecipientEncryptor.decode(checkNotNull(row.recipientEnvelopesBlob))
        assertEquals(listOf(newKeyId), envelopes.map { it.recipientEncryptionKeyId })

        transport.responder = ScriptedTransport.ACCEPT_ALL
        val settled = drain.run(allowRegistration = false)

        assertEquals(1, settled.accepted)
        assertEquals(
            listOf(newKeyId),
            transport.submitted[1].events.single().event.recipientEnvelopes
                .map { it.recipientEncryptionKeyId },
        )
        assertEquals("regeneration forces a fresh signature", 2, keyStore.signCount)
    }

    @Test
    fun staleKey_withNoLocalEnrollment_parksTheRowWaiting() = runBlocking {
        registered()
        val recorded = record()
        records.clear()
        val staleKeyId = ExistingE2eeRecipientEncryptor.recipientKeyIdOf(byteArrayOf(9))
        transport.responder = { batch ->
            PartnerEventBatchResultV1(
                listOf(
                    PartnerEventResultV1(
                        index = 0,
                        eventId = batch.events[0].event.eventId,
                        code = PartnerEventResultCode.RECIPIENT_KEY_STALE,
                        currentRecipientKeys = listOf(RecipientKeyRef(staleKeyId, 2, null)),
                    ),
                ),
            )
        }

        drain.run(allowRegistration = false)

        val row = checkNotNull(outbox.event(recorded.eventId))
        assertEquals(PendingPartnerEvent.STATUS_WAITING_FOR_RECIPIENT_KEY, row.status)
        assertNull(row.recipientEnvelopesBlob)
        assertTrue("the sealed source survives for a later retry", row.temporaryLocalCiphertext != null)
        assertEquals(clock.nowMs + 900_000, row.nextAttemptAtMs)
        assertTrue(outbox.waitingRows("uid-1", clock.nowMs + 899_999, 10).isEmpty())
        assertEquals(1, outbox.waitingRows("uid-1", clock.nowMs + 900_000, 10).size)
    }

    @Test
    fun staleKey_withEmptyAuthorizedSet_parksWaiting_andNeverReencryptsForALocalEnrollment() = runBlocking {
        registered()
        val recorded = record()
        // A local enrollment exists (`records` seeds one): if the empty server set were passed
        // as `null`, the encryptor would fall back to every local key, revoked or not.
        transport.responder = { batch ->
            PartnerEventBatchResultV1(
                listOf(
                    PartnerEventResultV1(
                        index = 0,
                        eventId = batch.events[0].event.eventId,
                        code = PartnerEventResultCode.RECIPIENT_KEY_STALE,
                        currentRecipientKeys = emptyList(),
                    ),
                ),
            )
        }

        val result = drain.run(allowRegistration = false)

        assertEquals(1, result.retried)
        val row = checkNotNull(outbox.event(recorded.eventId))
        assertEquals(PendingPartnerEvent.STATUS_WAITING_FOR_RECIPIENT_KEY, row.status)
        assertNull("no envelopes may be minted without an authorized key", row.recipientEnvelopesBlob)
        assertTrue("the sealed source survives for a later retry", row.temporaryLocalCiphertext != null)
        assertEquals(
            "the stale attempt is retired; no revoked-key bytes stay resendable",
            PendingPartnerEvent.WIRE_NOT_SENT,
            row.wireOutcome,
        )
        assertEquals(clock.nowMs + 900_000, row.nextAttemptAtMs)
    }

    @Test
    fun waitingRow_deliversOnceTheRecipientKeyAppears_andCountsTheRegeneration() = runBlocking {
        registered()
        val recorded = record()
        records.clear()
        val staleKeyId = ExistingE2eeRecipientEncryptor.recipientKeyIdOf(byteArrayOf(9))
        transport.responder = { batch ->
            PartnerEventBatchResultV1(
                listOf(
                    PartnerEventResultV1(
                        index = 0,
                        eventId = batch.events[0].event.eventId,
                        code = PartnerEventResultCode.RECIPIENT_KEY_STALE,
                        currentRecipientKeys = listOf(RecipientKeyRef(staleKeyId, 2, null)),
                    ),
                ),
            )
        }
        drain.run(allowRegistration = false)
        assertEquals(
            PendingPartnerEvent.STATUS_WAITING_FOR_RECIPIENT_KEY,
            checkNotNull(outbox.event(recorded.eventId)).status,
        )
        assertEquals("nothing was regenerated yet", 0, regenerations)

        records += enrollment(9)
        transport.responder = ScriptedTransport.ACCEPT_ALL
        clock.nowMs += 900_000

        val result = drain.run(allowRegistration = false)

        assertEquals(1, regenerations)
        assertEquals(1, result.accepted)
    }

    @Test
    fun reenrollRequired_suspendsTheInstallation_thenARegisteredDrainResumesEveryRow() = runBlocking {
        registered()
        record()
        record()
        transport.responder = { batch ->
            PartnerEventBatchResultV1(
                batch.events.mapIndexed { index, wire ->
                    PartnerEventResultV1(
                        index, wire.event.eventId, PartnerEventResultCode.REENROLL_REQUIRED,
                    )
                },
            )
        }

        val suspended = drain.run(allowRegistration = false)

        assertTrue(suspended.registrationRequired)
        assertEquals(
            InstallationState.REGISTRATION_REENROLL_REQUIRED,
            outbox.installation("uid-1")!!.registrationState,
        )
        assertEquals("rows are preserved, never dropped", 0, outbox.dueRows("uid-1", clock.nowMs, 10).size)
        assertEquals(0, outbox.waitingRows("uid-1", clock.nowMs, 10).size)
        val suspendedRows = outbox.earliestNextAttempt("uid-1")
        assertNull("suspended rows are invisible to scheduling until re-enrolled", suspendedRows)

        val blocked = drain.run(allowRegistration = false)
        assertTrue("a background drain may not re-register", blocked.registrationRequired)
        assertEquals(1, transport.submitted.size)

        transport.responder = ScriptedTransport.ACCEPT_ALL
        val resumed = drain.run(allowRegistration = true)

        assertEquals(2, resumed.accepted)
        assertEquals(InstallationState.REGISTRATION_ACTIVE, outbox.installation("uid-1")!!.registrationState)
        assertTrue(outbox.dueRows("uid-1", clock.nowMs, 10).isEmpty())
        assertEquals(4, keyStore.signCount)
    }

    @Test
    fun missingSigningKey_waitsForAForegroundRegistration_andThenDelivers() = runBlocking {
        registered()
        record()
        keyStore.present = false

        val background = drain.run(allowRegistration = false)

        assertTrue(background.registrationRequired)
        assertEquals(0, transport.submitted.size)
        assertEquals(
            InstallationState.REGISTRATION_REENROLL_REQUIRED,
            outbox.installation("uid-1")!!.registrationState,
        )

        val foreground = drain.run(allowRegistration = true)

        assertEquals(1, registrar.calls)
        assertEquals(1, foreground.accepted)
        assertEquals(InstallationState.REGISTRATION_ACTIVE, outbox.installation("uid-1")!!.registrationState)
    }

    @Test
    fun staleRecipientKey_reregistersOnAForegroundPass_thenDelivers() = runBlocking {
        // #196: a completed pairing mints a new agreement key while the signing key still
        // looks healthy; the server keeps advertising the previous enrollment's recipient
        // key until a foreground pass re-registers.
        registered()
        record()
        enrollmentRecipientKeyId = "fresh-key-id"

        val result = drain.run(allowRegistration = true)

        assertEquals(1, registrar.calls)
        assertEquals(1, result.accepted)
        assertEquals(1, transport.submitted.size)
    }

    @Test
    fun matchingRecipientKey_neverRegisters() = runBlocking {
        registered()
        record()
        enrollmentRecipientKeyId = "recipient-key-id"

        val result = drain.run(allowRegistration = true)

        assertEquals(0, registrar.calls)
        assertEquals(1, result.accepted)
    }

    @Test
    fun backgroundDrain_neverRefreshesTheRecipientKey() = runBlocking {
        registered()
        record()
        enrollmentRecipientKeyId = "fresh-key-id"

        val result = drain.run(allowRegistration = false)

        assertEquals("§8.3.1: registration is a foreground act", 0, registrar.calls)
        assertEquals(1, result.accepted)
    }

    @Test
    fun failedRecipientKeyRefresh_stillDeliversOutboundRows() = runBlocking {
        registered()
        record()
        enrollmentRecipientKeyId = "fresh-key-id"
        registrar.refreshOverride = PartnerEventRegistrar.Outcome.Retryable

        val result = drain.run(allowRegistration = true)

        assertEquals(1, registrar.calls)
        assertEquals("a stale recipient key only blocks inbound delivery", 1, result.accepted)
        assertEquals(
            InstallationState.REGISTRATION_ACTIVE,
            outbox.installation("uid-1")!!.registrationState,
        )
    }

    @Test
    fun refreshThatMintedAnUnregisteredKey_suspendsInsteadOfSigningWithIt() = runBlocking {
        registered()
        record()
        enrollmentRecipientKeyId = "fresh-key-id"
        registrar.refreshMintsKey = "N".repeat(43)
        registrar.refreshOverride = PartnerEventRegistrar.Outcome.Retryable

        val result = drain.run(allowRegistration = true)

        assertTrue(result.registrationRequired)
        assertEquals("no row may be signed with a key the server never accepted", 0, result.claimed)
        assertEquals(0, transport.submitted.size)
        assertEquals(
            InstallationState.REGISTRATION_REENROLL_REQUIRED,
            outbox.installation("uid-1")!!.registrationState,
        )
    }

    @Test
    fun leaseLeftByAKilledProcess_isReclaimedAfterExpiry() = runBlocking {
        registered()
        val recorded = record()
        val claimed = outbox.claimDue(
            "uid-1", clock.nowMs, 10, clock.nowMs + 120_000,
        )
        assertEquals(1, claimed.size)
        assertEquals(
            "the lease is committed even though the returned snapshot predates it",
            PendingPartnerEvent.STATUS_LEASED,
            outbox.event(recorded.eventId)!!.status,
        )
        assertTrue("a live lease hides the row from the next drain", outbox.dueRows("uid-1", clock.nowMs, 10).isEmpty())

        clock.nowMs += 120_001
        val result = drain.run(allowRegistration = false)

        assertEquals(1, result.accepted)
        assertEquals(PendingPartnerEvent.STATUS_ACKED, checkNotNull(outbox.event(recorded.eventId)).status)
    }

    @Test
    fun waitingRow_isReprobedOnceAnEnrollmentExists_thenDelivered() = runBlocking {
        registered()
        records.clear()
        val recorded = recorder.record(
            PartnerEventKind.CHECK_IN, sensitivePayload = "detail".toByteArray(),
        ) as PartnerEventRecorder.RecordResult.Recorded
        assertEquals(
            PendingPartnerEvent.STATUS_WAITING_FOR_RECIPIENT_KEY,
            checkNotNull(outbox.event(recorded.eventId)).status,
        )

        records += enrollment(1)
        val result = drain.run(allowRegistration = false)

        assertEquals(1, result.accepted)
        assertEquals(PendingPartnerEvent.STATUS_ACKED, checkNotNull(outbox.event(recorded.eventId)).status)
        val sentKey = transport.submitted.single().events.single()
            .event.recipientEnvelopes.single().recipientEncryptionKeyId
        assertEquals(ExistingE2eeRecipientEncryptor.recipientKeyIdOf(byteArrayOf(1)), sentKey)
    }

    @Test
    fun unreadableRow_isPermanentlyRejected_withoutSigningAnything() = runBlocking {
        val installation = registered()
        insertRow(installation.installationId, kind = "MADE_UP")

        val result = drain.run(allowRegistration = false)

        assertEquals(1, result.rejected)
        assertEquals(0, transport.submitted.size)
        assertEquals(0, keyStore.signCount)
        val row = outbox.event("00000000-0000-4000-8000-000000000099")!!
        assertEquals(PendingPartnerEvent.STATUS_PERMANENT_REJECTED, row.status)
        assertEquals(PartnerEventIssue.KIND_UNSUPPORTED.wire, row.lastErrorCode)
    }

    @Test
    fun serverPermanentRejection_erasesTheSource_keepsTheShell_andNeverResubmits() = runBlocking {
        registered()
        val recorded = record()
        transport.responder = { batch ->
            PartnerEventBatchResultV1(
                listOf(
                    PartnerEventResultV1(
                        index = 0,
                        eventId = batch.events[0].event.eventId,
                        code = PartnerEventResultCode.PERMANENT_REJECTED,
                        issue = "SIGNATURE_INVALID",
                    ),
                ),
            )
        }

        val result = drain.run(allowRegistration = false)

        assertEquals(1, result.rejected)
        val row = checkNotNull(outbox.event(recorded.eventId))
        assertEquals(PendingPartnerEvent.STATUS_PERMANENT_REJECTED, row.status)
        assertEquals("SIGNATURE_INVALID", row.lastErrorCode)
        assertEquals("the terminal transition is timestamped", clock.nowMs, row.terminalAtMs)
        assertNull("the retained source is erased at rejection", row.temporaryLocalCiphertext)
        assertNull(row.recipientEnvelopesBlob)
        assertNull(row.signedEnvelope)
        assertNull(row.signingSignature)
        assertTrue(outbox.dueRows("uid-1", clock.nowMs, 10).isEmpty())
    }

    @Test
    fun terminalShell_isDeletedOnlyAfterTheSevenDayWindow() = runBlocking {
        val installation = registered()
        insertRow(installation.installationId, kind = "MADE_UP")
        drain.run(allowRegistration = false)
        val eventId = "00000000-0000-4000-8000-000000000099"
        val terminalAt = checkNotNull(checkNotNull(outbox.event(eventId)).terminalAtMs)

        clock.nowMs = terminalAt + PendingPartnerEvent.REJECTED_RETENTION_MILLIS
        assertEquals(0, outbox.cleanupRejected(clock.nowMs))

        clock.nowMs = terminalAt + PendingPartnerEvent.REJECTED_RETENTION_MILLIS + 1
        assertEquals(1, outbox.cleanupRejected(clock.nowMs))
        assertNull(outbox.event(eventId))
    }

    @Test
    fun noSignedInOwner_isANoOp() = runBlocking {
        registered()
        record()
        currentUid = null

        val result = drain.run(allowRegistration = true)

        assertEquals(0, result.claimed)
        assertEquals(0, transport.submitted.size)
    }

    private suspend fun registered(): InstallationState {
        val installation = outbox.ensureInstallation("uid-1")
        outbox.markRegistered(
            installation.installationId,
            keyStore.keyId(installation.installationId),
            "recipient-key-id",
        )
        return checkNotNull(outbox.installation("uid-1"))
    }

    private suspend fun record(): PartnerEventRecorder.RecordResult.Recorded =
        recorder.record(
            PartnerEventKind.CHECK_IN, sensitivePayload = "detail".toByteArray(),
        ) as PartnerEventRecorder.RecordResult.Recorded

    private suspend fun insertRow(installationId: String, kind: String) {
        db.serviceEventOutboxDao().insertEvent(
            PendingPartnerEvent(
                eventId = "00000000-0000-4000-8000-000000000099",
                installationId = installationId,
                ownerUid = "uid-1",
                sequence = 999,
                schemaVersion = PartnerEventProtocol.VERSION,
                kind = kind,
                safeMetadataJson = "{}",
                deviceOccurredAtMs = 10,
                status = PendingPartnerEvent.STATUS_PENDING,
                createdLocalAtMs = 10,
            ),
        )
    }

    private fun encryptorFor(records: MutableList<EnrollmentRecord>) =
        ExistingE2eeRecipientEncryptor(
            enrollments = { records.toList() },
            deriveCek = { cek },
            cipher = EventCipher(),
        )

    private fun enrollment(keySeed: Int) = EnrollmentRecord(
        pairId = "pair-$keySeed",
        mySenderKeyId = "a".repeat(64),
        partnerSigningKeyId = "b".repeat(64),
        partnerSigningPubKey = byteArrayOf(keySeed.toByte()),
        partnerAgreementPubKey = byteArrayOf(keySeed.toByte()),
        createdAtEpochMillis = 1,
    )

    private class MutableClock(var nowMs: Long = 1_000_000L) : PartnerDeliveryClock {
        override fun wallClockMs(): Long = nowMs
        override fun elapsedRealtimeMs(): Long = nowMs / 2
    }

    private class FakeKeyStore : PartnerEventKeyStore() {
        var present = true
        var signCount = 0
        var currentKeyId: String = KEY_ID

        override fun hasKey(installationId: String): Boolean = present

        override fun keyId(installationId: String): String = currentKeyId

        override fun sign(installationId: String, data: ByteArray): ByteArray {
            signCount++
            return SIGNATURE
        }

        override fun publicKeyDer(installationId: String): ByteArray = byteArrayOf(4, 5, 6)

        override fun deleteKey(installationId: String) = Unit

        companion object {
            val KEY_ID = "K".repeat(43)
            val SIGNATURE = ByteArray(72) { (it + 1).toByte() }
        }
    }

    private class ScriptedTransport : PartnerDeliveryTransport {
        val submitted = mutableListOf<PartnerEventBatchV1>()
        var responder: (PartnerEventBatchV1) -> PartnerEventBatchResultV1 = ACCEPT_ALL

        override suspend fun submit(
            installationId: String,
            batch: PartnerEventBatchV1,
        ): PartnerEventBatchResultV1 {
            submitted += batch
            return responder(batch)
        }

        companion object {
            val ACCEPT_ALL: (PartnerEventBatchV1) -> PartnerEventBatchResultV1 = { batch ->
                PartnerEventBatchResultV1(
                    batch.events.mapIndexed { index, wire ->
                        PartnerEventResultV1(
                            index, wire.event.eventId, PartnerEventResultCode.ACCEPTED,
                        )
                    },
                )
            }
        }
    }

    /** The network is out of scope here (registrar's own test covers the wire); this records
     *  the §8.3.8 round trip the drain depends on. */
    private class ScriptedRegistrar(
        context: Context,
        private val keyStore: FakeKeyStore,
        private val outbox: ServiceEventOutbox,
    ) : PartnerEventRegistrar(
        appContext = context,
        functions = functionsInstance(),
        keyStore = keyStore,
        outbox = outbox,
        recipientPublicKeySpki = { byteArrayOf(7) },
        currentAuth = { PartnerEventRegistrar.PartnerAuth("uid-1", fresh = true) },
        allowUnattested = true,
    ) {
        var calls = 0

        /** Scripted refresh failure; [refreshMintsKey] simulates a key minted before it failed. */
        var refreshOverride: Outcome? = null
        var refreshMintsKey: String? = null

        override suspend fun register(installationId: String): Outcome {
            calls++
            keyStore.present = true
            refreshMintsKey?.let { keyStore.currentKeyId = it }
            refreshOverride?.let { return it }
            val committed = outbox.markRegistered(installationId, keyStore.keyId(installationId), "recipient-key-id")
            return if (committed) {
                Outcome.Registered("ACTIVE_UNATTESTED", 1)
            } else {
                Outcome.Retryable
            }
        }
    }

    /** In-memory stand-in for the Keystore seal; the AAD prefix makes a mismatch fail. */
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
        // The super constructor needs a Firebase singleton; the scripted transport never
        // touches the network.
        fun functionsInstance(): FirebaseFunctions {
            val context: Context = ApplicationProvider.getApplicationContext()
            val app = FirebaseApp.getApps(context).firstOrNull()
                ?: FirebaseApp.initializeApp(
                    context,
                    FirebaseOptions.Builder()
                        .setApplicationId("test-app")
                        .setProjectId("test-project")
                        .setApiKey("test-key")
                        .build(),
                )!!
            return FirebaseFunctions.getInstance(app, FirebasePartnerDeliveryTransport.REGION)
        }
    }
}
