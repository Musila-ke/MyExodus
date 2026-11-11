package com.example.myexodus.accountability.delivery

import android.app.Application
import android.content.Context
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.accountability.state.AccountabilityDatabase
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §8.6 Phase 5 recipient half — retrieval independent of FCM, receipt-before-watermark
 * ordering, terminal classification (skipped / undecryptable), duplicate suppression at the
 * `>=` watermark, and the retry contract for a failing projection. The query fake models
 * Firestore's `whereGreaterThanOrEqualTo("createdAt").orderBy("createdAt").limit(n)` so the
 * dedupe-on-re-read behavior is the real one, not a fake convenience.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PartnerMailboxReceiverTest {

    private lateinit var context: Context
    private lateinit var dbName: String
    private lateinit var db: AccountabilityDatabase
    private lateinit var dao: PartnerMailboxDao

    private var uid: String? = "recipient-uid"
    private var installationId: String? = "recipient-inst"
    private var documents: List<PartnerMailboxDocument> = emptyList()
    private var queries = mutableListOf<Triple<String, String, Long>>()
    private var delivered = mutableListOf<Pair<PartnerMailboxDocument, String>>()
    private var deliveredThrows = false
    private var decryptThrows: Exception? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbName = "mailbox_test_${System.nanoTime()}.db"
        db = Room.databaseBuilder(context, AccountabilityDatabase::class.java, dbName).build()
        dao = db.partnerMailboxDao()
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(dbName)
    }

    private fun receiver(limit: Int = 200) = PartnerMailboxReceiver(
        dao = dao,
        ownerUid = { uid },
        recipientInstallationId = { installationId },
        queryMailbox = { owner, installation, fromMs, pageLimit ->
            queries += Triple(owner, installation, fromMs)
            documents
                .filter { it.createdAtMs >= fromMs }
                .sortedBy { it.createdAtMs }
                .take(pageLimit)
        },
        decrypt = { document ->
            decryptThrows?.let { throw it }
            document.payload
                ?.takeUnless { it == CORRUPT }
                ?.toByteArray()
        },
        onDelivered = { document, plaintext ->
            if (deliveredThrows) error("projection failed")
            delivered += document to String(plaintext)
        },
        limit = limit,
    )

    private companion object {
        const val CORRUPT = "corrupt-ciphertext"
    }

    private fun doc(
        messageId: String,
        createdAtMs: Long,
        payload: String? = "body-$messageId",
        encoding: String = PartnerEventProtocol.ENCODING_EXISTING_E2EE,
        kind: String = PartnerEventKind.CHECK_IN.wire,
        pairId: String? = "pair-1",
    ) = PartnerMailboxDocument(
        messageId = messageId,
        eventId = "event-$messageId",
        senderInstallationId = "sender-inst",
        senderOwnerUid = "sender-uid",
        kind = kind,
        recipientKeyId = "key-1",
        payload = payload,
        encoding = encoding,
        pairId = pairId,
        createdAtMs = createdAtMs,
    )

    @Test
    fun pull_deliversReceiptsAndAdvancesTheWatermarkPerDocument() = runBlocking {
        documents = listOf(doc("m1", 100), doc("m2", 200))

        val result = receiver().pull()

        assertEquals(PartnerMailboxReceiver.PullResult(delivered = 2), result)
        assertEquals(
            listOf("body-m1", "body-m2"),
            delivered.map { it.second },
        )
        assertEquals(PartnerMailboxDelivery.STATUS_DELIVERED, dao.receipt("recipient-inst", "m1"))
        assertEquals(PartnerMailboxDelivery.STATUS_DELIVERED, dao.receipt("recipient-inst", "m2"))
        assertEquals(200L, dao.watermark("recipient-inst"))
    }

    @Test
    fun pull_startsFromThePersistedWatermark_andReReadsThatDocumentAsADuplicate() = runBlocking {
        documents = listOf(doc("m1", 100), doc("m2", 200))
        val receiver = receiver()
        receiver.pull()

        // The next page is queried at the watermark itself (>=, not strictly greater), so m2
        // comes back and must be suppressed by its receipt.
        queries.clear()
        val second = receiver.pull()

        assertEquals(200L, queries.single().third)
        assertEquals(PartnerMailboxReceiver.PullResult(duplicates = 1), second)
        assertEquals("delivered exactly once across both pulls", 2, delivered.size)
        assertEquals(200L, dao.watermark("recipient-inst"))
    }

    @Test
    fun pull_terminallySkipsMalformedDocuments_withoutCallingDecrypt() = runBlocking {
        documents = listOf(
            doc("wrong-encoding", 100, encoding = "legacy-plaintext"),
            doc("missing-payload", 200, payload = null),
            doc("unknown-kind", 300, kind = "NOT_A_KIND"),
            doc("missing-pair", 400, pairId = null),
            doc("good", 500),
        )

        val result = receiver().pull()

        assertEquals(4, result.skipped)
        assertEquals(1, result.delivered)
        for (id in listOf("wrong-encoding", "missing-payload", "unknown-kind", "missing-pair")) {
            assertEquals(PartnerMailboxDelivery.STATUS_SKIPPED, dao.receipt("recipient-inst", id))
        }
        assertEquals("skipped documents never reach the projection", listOf("body-good"), delivered.map { it.second })
    }

    @Test
    fun pull_marksAnUndecryptableDocumentTerminal_withoutStallingLaterDocuments() = runBlocking {
        documents = listOf(doc("m1", 100, payload = CORRUPT), doc("m2", 200))

        val result = receiver().pull()

        assertEquals(1, result.undecryptable)
        assertEquals(1, result.delivered)
        assertEquals(PartnerMailboxDelivery.STATUS_UNDECRYPTABLE, dao.receipt("recipient-inst", "m1"))
        assertEquals("the watermark moved past the unreadable document", 200L, dao.watermark("recipient-inst"))
    }

    @Test
    fun pull_stopsAtADocumentWhoseProjectionThrows_leavingNoReceiptSoTheNextPassRetries() = runBlocking {
        documents = listOf(doc("m1", 100), doc("m2", 200), doc("m3", 300))
        deliveredThrows = true

        val receiver = receiver()
        val failure = runCatching { receiver.pull() }.exceptionOrNull()
        assertTrue("projection failure must propagate: $failure", failure is IllegalStateException)

        assertNull("the failing document has no receipt", dao.receipt("recipient-inst", "m1"))
        assertNull(dao.receipt("recipient-inst", "m2"))
        assertNull("the watermark never advanced", dao.watermark("recipient-inst"))

        deliveredThrows = false
        val retried = receiver.pull()

        assertEquals(3, retried.delivered)
        assertEquals(listOf("body-m1", "body-m2", "body-m3"), delivered.map { it.second })
        assertEquals(300L, dao.watermark("recipient-inst"))
    }

    @Test
    fun pull_keepsOneWatermarkForDocumentsSharingACreatedAt() = runBlocking {
        documents = listOf(doc("m1", 100), doc("m2", 100), doc("m3", 250))

        receiver().pull()

        assertEquals(250L, dao.watermark("recipient-inst"))
        assertEquals(PartnerMailboxDelivery.STATUS_DELIVERED, dao.receipt("recipient-inst", "m1"))
        assertEquals(PartnerMailboxDelivery.STATUS_DELIVERED, dao.receipt("recipient-inst", "m2"))
    }

    @Test
    fun pull_withoutAnOwnerOrInstallation_queriesNothingAndReportsEmpty() = runBlocking {
        documents = listOf(doc("m1", 100))
        uid = null
        assertEquals(PartnerMailboxReceiver.PullResult(), receiver().pull())

        uid = "recipient-uid"
        installationId = null
        assertEquals(PartnerMailboxReceiver.PullResult(), receiver().pull())

        assertTrue(queries.isEmpty())
    }

    @Test
    fun pull_passesTheConfiguredLimitAndTheServiceInstallationIdToTheQuery() = runBlocking {
        documents = listOf(doc("m1", 100))

        receiver(limit = 7).pull()

        assertEquals("recipient-uid" to "recipient-inst", queries.single().first to queries.single().second)
    }

    @Test
    fun pull_doesNotSwallowCancellation() = runBlocking {
        documents = listOf(doc("m1", 100))
        decryptThrows = CancellationException("scope cancelled")

        val failure = runCatching { receiver().pull() }.exceptionOrNull()
        assertTrue("cancellation must propagate unswallowed", failure is CancellationException)

        assertNull(dao.receipt("recipient-inst", "m1"))
        assertNull(dao.watermark("recipient-inst"))
    }
}
