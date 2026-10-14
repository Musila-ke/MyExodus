package com.example.myexodus.accountability.sync

import android.app.Application
import android.content.Context
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.accountability.crypto.AccountabilityKeyStore
import com.example.myexodus.accountability.enrollment.CekDerivation
import com.example.myexodus.accountability.enrollment.EnrollmentDao
import com.example.myexodus.accountability.enrollment.EnrollmentRecord
import com.example.myexodus.accountability.serialization.EventPayload
import com.example.myexodus.accountability.serialization.SignedEnvelope
import com.example.myexodus.accountability.serialization.UnsignedEnvelope
import com.example.myexodus.accountability.state.AccountabilityDatabase
import com.example.myexodus.accountability.state.ChainStateStore
import com.example.myexodus.accountability.state.SessionKeyManager
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The §7.5 watermark machinery inside [FirestoreMailboxReceiver] — adversarial coverage of the
 * quota fix: strictly-after reads, max-advance (including foreign-pair and null-pair docs the
 * handler can never deliver), the expedited wake path's interplay with the bulk pass, and the
 * deletion short-circuit. Firestore access is faked at the house-pattern seams ([queryMailbox]
 * / [fetchMessage]); the fake models the real query semantics — `orderBy("timestamp")`
 * excludes documents missing the field, `startAfter` is strictly greater, `limit` applies
 * after ordering — so these tests pin receiver behavior, not fake conveniences.
 *
 * The AD-10b dedup marker is modeled in the append seam (same-transaction marking), which is
 * what makes the wake-then-bulk pass observable as appended-once + skipped-once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MailboxWatermarkStressTest {

    private lateinit var context: Context
    private lateinit var dbName: String
    private lateinit var db: AccountabilityDatabase
    private lateinit var store: ChainStateStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbName = "watermark_test_${System.nanoTime()}.db"
        db = Room.databaseBuilder(context, AccountabilityDatabase::class.java, dbName).build()
        store = ChainStateStore(db)
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(dbName)
    }

    // --- Rig ------------------------------------------------------------------------------

    private val agreementKeyPair: KeyPair = ecKeyPair()
    private val partnerAgreementKeyPair: KeyPair = ecKeyPair()
    private val partnerSigningKeyPair: KeyPair = ecKeyPair()

    /** Returns one fixed in-memory key for every lookup — the Keystore itself is not under
     *  test here, and the receiver's CEK derivation runs for real above it. */
    private class FakeKeystore(private val key: PrivateKey) : AccountabilityKeyStore() {
        override fun agreementPrivateKey(): PrivateKey = key
        override fun agreementPrivateKeyFor(alias: String?): PrivateKey = key
    }

    private class FakeEnrollmentDao : EnrollmentDao {
        var record: EnrollmentRecord? = null
        val watermarkWrites = mutableListOf<Pair<String, Long>>()

        override suspend fun upsert(record: EnrollmentRecord) {
            this.record = record
        }

        override suspend fun getByPairId(pairId: String): EnrollmentRecord? =
            record?.takeIf { it.pairId == pairId }

        override suspend fun all(): List<EnrollmentRecord> = listOfNotNull(record)

        override suspend fun delete(record: EnrollmentRecord) {
            if (this.record?.pairId == record.pairId) this.record = null
        }

        override suspend fun updateMailboxWatermark(pairId: String, ts: Long) {
            watermarkWrites += pairId to ts
            record = record?.copy(mailboxWatermarkTsEpochMillis = ts)
        }

        override suspend fun updatePartnerLabel(pairId: String, label: String?) {
            record = record?.copy(partnerLabel = label)
        }
    }

    /** Firestore-modeled mailbox: `timestamp` orderBy excludes null-ts docs; startAfter is
     *  strictly greater; limit applies after ordering. [preserveOrder] lets a test hand the
     *  receiver a raw, unsorted page — real Firestore never would, but a regression that
     *  regressed the watermark on one must still be caught. */
    private class FakeMailbox {
        val docs = mutableListOf<MailboxDocument>()
        val queryArgs = mutableListOf<Pair<Long?, Int>>()
        val fetched = mutableListOf<String>()
        var preserveOrder = false

        suspend fun query(myUid: String, afterEpochMillis: Long?, limit: Int): List<MailboxDocument> {
            queryArgs += afterEpochMillis to limit
            val page = docs.filter {
                it.timestampEpochMillis != null &&
                    (afterEpochMillis == null || it.timestampEpochMillis > afterEpochMillis)
            }
            val ordered = if (preserveOrder) page else page.sortedBy { it.timestampEpochMillis }
            return ordered.take(limit)
        }

        suspend fun fetch(myUid: String, messageId: String): MailboxDocument? {
            fetched += messageId
            return docs.firstOrNull { it.messageId == messageId }
        }
    }

    /** Handler seams; the message id rides the payload base64 so per-document outcomes are
     *  addressable without bypassing the receiver. */
    private class HandlerSeams {
        val processedIds = mutableSetOf<String>()
        var deletionIds = emptySet<String>()
        var appendResult: ChainStateStore.AppendResult = ChainStateStore.AppendResult.Appended
        var shredSucceeds = true
        var epoch0KeyAccepted = false
        var processCalls = 0
        val appendedIds = mutableListOf<String>()
        val deletionRequests = mutableListOf<String>()

        val handler = MailboxMessageHandler(
            processed = { it in processedIds },
            process = { _, keyForEpoch, bytes ->
                processCalls += 1
                epoch0KeyAccepted = keyForEpoch(0) != null
                val id = String(bytes)
                if (id in deletionIds) {
                    MailboxEventProcessor.EnvelopeResult.Valid(
                        envelope(),
                        EventPayload(type = EventPayload.TYPE_DELETION_REQUEST, text = ""),
                    )
                } else {
                    MailboxEventProcessor.EnvelopeResult.Valid(envelope(), EventPayload(text = id))
                }
            },
            append = { _, messageId, _, _ ->
                appendedIds += messageId
                // AD-10b: the append commits the processed marker in the same transaction.
                if (appendResult == ChainStateStore.AppendResult.Appended ||
                    appendResult == ChainStateStore.AppendResult.Duplicate ||
                    appendResult == ChainStateStore.AppendResult.Gap
                ) {
                    processedIds += messageId
                }
                appendResult
            },
            ack = { _, _ -> },
            onDeletionRequest = { _, messageId, _ ->
                deletionRequests += messageId
                shredSucceeds
            },
        )

        companion object {
            fun envelope() = SignedEnvelope(
                unsigned = UnsignedEnvelope(
                    senderKeyId = "partner-sender",
                    epoch = 0,
                    sequence = 1,
                    previousEventHash = null,
                    nonce = ByteArray(12),
                    ciphertext = byteArrayOf(1),
                    createdAtEpochMillis = 0L,
                ),
                signature = ByteArray(8),
            )
        }
    }

    private val dao = FakeEnrollmentDao()
    private val mailbox = FakeMailbox()
    private val seams = HandlerSeams()

    private fun enrollment(watermark: Long? = null) = EnrollmentRecord(
        pairId = PAIR_ID,
        mySenderKeyId = "my-sender",
        partnerSigningKeyId = "partner-signing",
        partnerSigningPubKey = partnerSigningKeyPair.public.encoded,
        partnerAgreementPubKey = partnerAgreementKeyPair.public.encoded,
        createdAtEpochMillis = 0L,
        mailboxWatermarkTsEpochMillis = watermark,
        myAgreementKeyAlias = null,
    )

    private fun receiver(): FirestoreMailboxReceiver = FirestoreMailboxReceiver(
        enrollmentDao = dao,
        store = store,
        keystore = FakeKeystore(agreementKeyPair.private),
        cekDerivation = CekDerivation(),
        sessionKeyManager = SessionKeyManager(store),
        handler = seams.handler,
        queryMailbox = mailbox::query,
        fetchMessage = mailbox::fetch,
    )

    /** The message id rides the payload bytes; pairId defaults to this pair. */
    private fun doc(id: String, ts: Long?, pairId: String? = PAIR_ID) = MailboxDocument(
        messageId = id,
        senderId = "partner-sender",
        payloadBase64 = Base64.getEncoder().encodeToString(id.toByteArray()),
        timestampEpochMillis = ts,
        pairId = pairId,
    )

    private fun pull(messageId: String? = null) =
        runBlocking { receiver().pull(PAIR_ID, "uid-me", messageId) }

    // --- Tests ----------------------------------------------------------------------------

    @Test
    fun bulkPass_handlesAscending_persistsMaxTimestamp() {
        dao.record = enrollment(watermark = null)
        mailbox.docs += listOf(doc("m1", 1_000), doc("m2", 2_000))

        val result = pull()

        assertEquals("one watermark query, from null, at the §7.5 limit", listOf(null to 500), mailbox.queryArgs)
        assertEquals("no wake — nothing fetched by id", emptyList<String>(), mailbox.fetched)
        assertEquals(listOf("m1", "m2"), seams.appendedIds)
        assertEquals(2, result.appended)
        assertEquals(listOf(PAIR_ID to 2_000L), dao.watermarkWrites)
        assertEquals(2_000L, dao.record!!.mailboxWatermarkTsEpochMillis)
        assertTrue("epoch 0 must pass the real receive policy on a headless chain", seams.epoch0KeyAccepted)
    }

    @Test
    fun lateArrivalBelowWatermark_invisibleToBulk_recoveredByWake() {
        // The §6 step 3 scenario: server delivered an older-timestamped document after the
        // watermark passed its position. The bulk query can never see it again.
        dao.record = enrollment(watermark = 2_000)
        mailbox.docs += doc("m2", 1_500)

        val result = pull(messageId = "m2")

        assertEquals("the wake's own id is fetched before the watermark query", listOf("m2"), mailbox.fetched)
        assertEquals(listOf(2_000L to 500), mailbox.queryArgs)
        assertEquals(listOf("m2"), seams.appendedIds)
        assertEquals(1, result.appended)
        assertEquals("the stale document must not regress the watermark", emptyList<Pair<String, Long>>(), dao.watermarkWrites)
    }

    @Test
    fun foreignAndNullPairDocuments_advanceWatermark_withoutDelivering() {
        dao.record = enrollment(watermark = null)
        mailbox.docs += doc("m1", 1_000)
        mailbox.docs += doc("mf", 2_000, pairId = "other-pair")
        mailbox.docs += doc("mn", 3_000, pairId = null)

        val first = pull()

        assertEquals("only this pair's document is applied", listOf("m1"), seams.appendedIds)
        assertEquals(2, first.skipped)
        assertEquals("foreign documents still advance the watermark (quota recovery)",
            listOf(PAIR_ID to 3_000L), dao.watermarkWrites)

        val second = pull()

        assertEquals("nothing at or below the watermark is re-read", 3_000L to 500, mailbox.queryArgs.last())
        assertEquals(0, second.appended)
        assertEquals(0, second.skipped)
        assertEquals("no second persist — the watermark did not move", 1, dao.watermarkWrites.size)
    }

    @Test
    fun unsortedPage_neverRegressesWatermark() {
        // A raw page out of order: the advanced watermark must survive the later, older doc.
        dao.record = enrollment(watermark = 5_000)
        mailbox.preserveOrder = true
        mailbox.docs += listOf(doc("m3", 9_000), doc("m2", 6_000))

        val result = pull()

        assertEquals(listOf("m3", "m2"), seams.appendedIds)
        assertEquals(2, result.appended)
        assertEquals(listOf(PAIR_ID to 9_000L), dao.watermarkWrites)
    }

    @Test
    fun unchangedWatermark_isNotPersisted() {
        dao.record = enrollment(watermark = 5_000)

        val result = pull()

        assertEquals(0, result.appended)
        assertEquals(emptyList<Pair<String, Long>>(), dao.watermarkWrites)
    }

    @Test
    fun wakeDocumentWithoutTimestamp_isHandled_withoutWatermarkContribution() {
        dao.record = enrollment(watermark = null)
        mailbox.docs += doc("mNull", ts = null)

        val result = pull(messageId = "mNull")

        assertEquals("the expedited path delivers regardless of the missing server stamp", listOf("mNull"), seams.appendedIds)
        assertEquals(1, result.appended)
        assertEquals("a null timestamp can never move the watermark", emptyList<Pair<String, Long>>(), dao.watermarkWrites)
    }

    @Test
    fun deletionMidBulk_stopsRemainingDocuments_andSkipsPersist() {
        dao.record = enrollment(watermark = null)
        seams.deletionIds = setOf("d2")
        mailbox.docs += listOf(doc("d1", 1_000), doc("d2", 2_000), doc("d3", 3_000))

        val result = pull()

        assertEquals("d3 is never even verified — the shredded pair must not re-create rows",
            2, seams.processCalls)
        assertEquals(listOf("d1"), seams.appendedIds)
        assertEquals(listOf("d2"), seams.deletionRequests)
        assertTrue(result.deletionRequested)
        assertFalse(result.deletionFailed)
        assertEquals("the early return skips the persist — as-is semantics", emptyList<Pair<String, Long>>(), dao.watermarkWrites)
    }

    @Test
    fun wakeDeletionRequest_shortCircuitsBeforeBulkQuery() {
        dao.record = enrollment(watermark = null)
        seams.deletionIds = setOf("d0")
        mailbox.docs += doc("d0", 1_000)

        val result = pull(messageId = "d0")

        assertTrue(result.deletionRequested)
        assertEquals(listOf("d0"), mailbox.fetched)
        assertEquals("no bulk pass after a verified deletion", emptyList<Pair<Long?, Int>>(), mailbox.queryArgs)
    }

    @Test
    fun wakeDelivers_thenBulkDedups() {
        dao.record = enrollment(watermark = null)
        mailbox.docs += doc("m1", 2_000)

        val result = pull(messageId = "m1")

        assertEquals("appended exactly once — the bulk re-read dedups it", listOf("m1"), seams.appendedIds)
        assertEquals(1, seams.processCalls)
        assertEquals(1, result.appended)
        assertEquals(1, result.skipped)
        assertEquals(listOf(PAIR_ID to 2_000L), dao.watermarkWrites)
    }

    @Test
    fun wakeForForeignDocument_isSkipped_bulkStillRuns() {
        dao.record = enrollment(watermark = null)
        mailbox.docs += doc("mf", 2_000, pairId = "other-pair")

        val result = pull(messageId = "mf")

        assertEquals(0, result.appended)
        assertEquals("skipped once by the wake and once by the bulk", 2, result.skipped)
        // The bulk query uses the watermark as read at pull start — the wake's own persist
        // happens only at the end, so a same-pull bulk still reads from the old position.
        assertEquals("the foreign wake must not abort the pass", null to 500, mailbox.queryArgs.single())
        assertEquals(listOf(PAIR_ID to 2_000L), dao.watermarkWrites)
    }

    @Test
    fun wakeForMissingId_isNoOp_bulkStillRuns() {
        dao.record = enrollment(watermark = null)
        mailbox.docs += doc("m1", 1_000)

        val result = pull(messageId = "ghost")

        assertEquals(listOf("ghost"), mailbox.fetched)
        assertEquals(listOf("m1"), seams.appendedIds)
        assertEquals(1, result.appended)
        assertEquals(1, result.skipped)
    }

    @Test
    fun noEnrollment_isNotEnrolled_nothingTouched() {
        dao.record = null
        mailbox.docs += doc("m1", 1_000)

        assertTrue(pull().isNotEnrolled)
        assertTrue(pull(messageId = "m1").isNotEnrolled)
        assertEquals(emptyList<Pair<Long?, Int>>(), mailbox.queryArgs)
        assertEquals(emptyList<String>(), mailbox.fetched)
        assertEquals(0, seams.processCalls)
    }

    @Test
    fun equalTimestamps_bothHandled_thenExcludedByStrictWatermark() {
        dao.record = enrollment(watermark = null)
        mailbox.docs += listOf(doc("ma", 1_000), doc("mb", 1_000))

        val first = pull()

        assertEquals(listOf("ma", "mb"), seams.appendedIds)
        assertEquals(2, first.appended)
        assertEquals(listOf(PAIR_ID to 1_000L), dao.watermarkWrites)

        val second = pull()

        assertEquals("startAfter is strictly greater — the tie is not re-read",
            0, second.appended)
        assertEquals(1, dao.watermarkWrites.size)
    }

    private companion object {
        const val PAIR_ID = "pair-1"

        fun ecKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
    }
}
