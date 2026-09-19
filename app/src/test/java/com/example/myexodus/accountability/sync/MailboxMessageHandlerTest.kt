package com.example.myexodus.accountability.sync

import android.app.Application
import com.example.myexodus.accountability.serialization.EventPayload
import com.example.myexodus.accountability.serialization.SignedEnvelope
import com.example.myexodus.accountability.serialization.UnsignedEnvelope
import com.example.myexodus.accountability.state.ChainStateStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * §7 item 10 / §8 Step 9 pipeline order: AD-10b dedup BEFORE any fetch, missing-doc no-op,
 * own-sender skip, verify-then-decrypt, chain commit with ack + generic notification on the
 * consumed outcomes only. Signature/crypto correctness itself is [MailboxEventProcessorTest];
 * this pins the ordering and the side-effect fan-out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MailboxMessageHandlerTest {

    private val partnerKey: PublicKey = ecKeyPair().public

    private fun doc(
        messageId: String = "m1",
        senderId: String? = "partner-sender",
        payload: String? = Base64.getEncoder().encodeToString(byteArrayOf(9, 8, 7)),
    ) = MailboxDocument(messageId, senderId, payload, 1_700_000_000_000L)

    private class Fake {
        var alreadyProcessed = false
        var outcome: MailboxEventProcessor.EnvelopeResult =
            MailboxEventProcessor.EnvelopeResult.Valid(envelope(), EventPayload(text = "blocked:x"))
        var appendResult: ChainStateStore.AppendResult = ChainStateStore.AppendResult.Appended
        var ackError: Exception? = null
        var notifyError: Exception? = null
        var tamperRepairing = false
        var deletionShredded = true

        val processedChecks = mutableListOf<String>()
        val loads = mutableListOf<String>()
        val appended = mutableListOf<Triple<String, String, Long>>()
        val acks = mutableListOf<Pair<String, String>>()
        val notified = mutableListOf<String>()
        val repairResponses = mutableListOf<Triple<String, Long, ByteArray>>()
        val deletionRequests = mutableListOf<Pair<String, String>>()
        var tamperChecks = 0

        val handler = MailboxMessageHandler(
            processed = { processedChecks += it; alreadyProcessed },
            process = { _, _, _ -> outcome },
            append = { pairId, messageId, _, at ->
                appended += Triple(pairId, messageId, at)
                appendResult
            },
            ack = { recipientUid, messageId ->
                ackError?.let { throw it }
                acks += recipientUid to messageId
            },
            onDeletionRequest = { pairId, messageId ->
                deletionRequests += pairId to messageId
                deletionShredded
            },
            notify = { payloadType ->
                notifyError?.let { throw it }
                notified += payloadType
            },
            respondToRequest = { pairId, sequence, hash -> repairResponses += Triple(pairId, sequence, hash) },
            flagTamperIfRepairing = { tamperChecks++; tamperRepairing },
            now = { 42L },
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

    private suspend fun handle(fake: Fake, doc: MailboxDocument?, messageId: String = "m1") =
        fake.handler.handle(
            pairId = "pair-1",
            recipientUid = "uid-me",
            mySenderKeyId = "my-sender",
            partnerSigningKey = partnerKey,
            keyForEpoch = { ByteArray(32) }, // single-epoch (keyEpoch 0) test traffic
            messageId = messageId,
            load = { fake.loads += messageId; doc },
        )

    private fun on(fake: Fake, doc: MailboxDocument?, messageId: String = "m1") =
        runBlocking { handle(fake, doc, messageId) }

    @Test
    fun alreadyProcessed_shortCircuitsBeforeLoad() {
        val fake = Fake().apply { alreadyProcessed = true }

        val result = on(fake, doc())

        assertEquals(MailboxMessageHandler.Kind.ALREADY_PROCESSED, result.kind)
        assertEquals("AD-10b: the dedup check precedes the fetch", emptyList<String>(), fake.loads)
        assertEquals(emptyList<Triple<String, String, Long>>(), fake.appended)
        assertEquals(emptyList<Pair<String, String>>(), fake.acks)
        assertEquals(emptyList<String>(), fake.notified)
    }

    @Test
    fun missingDocument_isNoOp() {
        val fake = Fake()

        val result = on(fake, null)

        assertEquals(MailboxMessageHandler.Kind.MISSING, result.kind)
        assertEquals(listOf("m1"), fake.processedChecks)
        assertEquals(emptyList<Triple<String, String, Long>>(), fake.appended)
        assertEquals(emptyList<String>(), fake.notified)
    }

    @Test
    fun ownSenderId_isSkipped_neverTouchesTheChain() {
        val fake = Fake()

        val result = on(fake, doc(senderId = "my-sender"))

        assertEquals(MailboxMessageHandler.Kind.SELF, result.kind)
        assertEquals(emptyList<Triple<String, String, Long>>(), fake.appended)
        assertEquals(emptyList<Pair<String, String>>(), fake.acks)
    }

    @Test
    fun missingOrMalformedPayload_isNoOp() {
        val fake = Fake()

        assertEquals(MailboxMessageHandler.Kind.NO_PAYLOAD, on(fake, doc(payload = null)).kind)
        assertEquals(MailboxMessageHandler.Kind.NO_PAYLOAD, on(fake, doc(payload = "not-base64!!")).kind)
        assertEquals(emptyList<Triple<String, String, Long>>(), fake.appended)
    }

    @Test
    fun invalidSignature_isLogged_notAcked_notApplied() {
        val fake = Fake().apply { outcome = MailboxEventProcessor.EnvelopeResult.InvalidSignature }

        val result = on(fake, doc())

        assertEquals(MailboxMessageHandler.Kind.BAD_SIGNATURE, result.kind)
        // Not acked and no chain write: the message stays subject to re-delivery.
        assertEquals(emptyList<Triple<String, String, Long>>(), fake.appended)
        assertEquals(emptyList<Pair<String, String>>(), fake.acks)
        assertEquals(emptyList<String>(), fake.notified)
    }

    @Test
    fun corrupt_isLogged_notAcked() {
        val fake = Fake().apply { outcome = MailboxEventProcessor.EnvelopeResult.Corrupt }

        val result = on(fake, doc())

        assertEquals(MailboxMessageHandler.Kind.CORRUPT, result.kind)
        assertEquals(emptyList<Pair<String, String>>(), fake.acks)
    }

    @Test
    fun appended_acksAndNotifies_once_carryingTheTransactionTimestamp() {
        val fake = Fake()

        val result = on(fake, doc())

        assertEquals(MailboxMessageHandler.Kind.APPENDED, result.kind)
        assertEquals(EventPayload.TYPE_ACTIVITY, result.payloadType)
        assertEquals(listOf(Triple("pair-1", "m1", 42L)), fake.appended)
        assertEquals(listOf("uid-me" to "m1"), fake.acks)
        assertEquals(listOf(EventPayload.TYPE_ACTIVITY), fake.notified)
    }

    @Test
    fun duplicateAndGap_areAcked_butNeverNotify() {
        val duplicate = Fake().apply { appendResult = ChainStateStore.AppendResult.Duplicate }
        assertEquals(MailboxMessageHandler.Kind.DUPLICATE, on(duplicate, doc()).kind)
        assertEquals(listOf("uid-me" to "m1"), duplicate.acks)
        assertEquals(emptyList<String>(), duplicate.notified)

        val gap = Fake().apply { appendResult = ChainStateStore.AppendResult.Gap }
        assertEquals(MailboxMessageHandler.Kind.GAP, on(gap, doc()).kind)
        assertEquals(listOf("uid-me" to "m1"), gap.acks)
        assertEquals(emptyList<String>(), gap.notified)
    }

    @Test
    fun forkAndRejected_areNotAcked() {
        val fork = Fake().apply { appendResult = ChainStateStore.AppendResult.Fork }
        assertEquals(MailboxMessageHandler.Kind.FORK, on(fork, doc()).kind)
        assertEquals(emptyList<Pair<String, String>>(), fork.acks)

        val rejected = Fake().apply { appendResult = ChainStateStore.AppendResult.Rejected }
        assertEquals(MailboxMessageHandler.Kind.REJECTED, on(rejected, doc()).kind)
        assertEquals(emptyList<Pair<String, String>>(), rejected.acks)
    }

    @Test
    fun failingAckOrNotify_neverEscapes() {
        val ackFails = Fake().apply { ackError = IllegalStateException("offline") }
        assertEquals(MailboxMessageHandler.Kind.APPENDED, on(ackFails, doc()).kind)
        assertEquals("notify still ran after the ack failure", listOf(EventPayload.TYPE_ACTIVITY), ackFails.notified)

        val notifyFails = Fake().apply { notifyError = IllegalStateException("channel gone") }
        assertEquals(MailboxMessageHandler.Kind.APPENDED, on(notifyFails, doc()).kind)
        assertEquals(listOf("uid-me" to "m1"), notifyFails.acks)
    }

    @Test
    fun deletionRequest_isShredded_neverChained_ackRidesTheShred() {
        val fake = Fake().apply {
            outcome = MailboxEventProcessor.EnvelopeResult.Valid(
                Fake.envelope(),
                EventPayload(type = EventPayload.TYPE_DELETION_REQUEST, text = ""),
            )
            // Irrelevant by construction: the deletion path never consults chain position.
            appendResult = ChainStateStore.AppendResult.Duplicate
        }

        val result = on(fake, doc())

        // §8 Step 13 / §10: the verified intent is shredded directly — the one exception to
        // append-then-ack. The handler writes no ack of its own (the shred path owes it),
        // does not chain the event, and never notifies.
        assertEquals(EventPayload.TYPE_DELETION_REQUEST, result.payloadType)
        assertEquals(MailboxMessageHandler.Kind.DELETION_REQUESTED, result.kind)
        assertEquals(listOf("pair-1" to "m1"), fake.deletionRequests)
        assertEquals(emptyList<Triple<String, String, Long>>(), fake.appended)
        assertEquals(emptyList<Pair<String, String>>(), fake.acks)
        assertEquals(emptyList<String>(), fake.notified)
    }

    @Test
    fun failedDeletionShred_reportsDELETION_FAILED_unackedAndRetryable() {
        val fake = Fake().apply {
            outcome = MailboxEventProcessor.EnvelopeResult.Valid(
                Fake.envelope(),
                EventPayload(type = EventPayload.TYPE_DELETION_REQUEST, text = ""),
            )
            deletionShredded = false
        }

        val result = on(fake, doc())

        // AD-13b: a failed shred must leave the message unacked (re-deliverable), not chained.
        assertEquals(MailboxMessageHandler.Kind.DELETION_FAILED, result.kind)
        assertEquals(emptyList<Triple<String, String, Long>>(), fake.appended)
        assertEquals(emptyList<Pair<String, String>>(), fake.acks)
    }

    // --- Step 11: sync_request response + AD-08 tamper correlation. ---

    @Test
    fun appendedSyncRequest_answersTheRepair_andSuppressesNotification() {
        val fake = Fake().apply {
            outcome = MailboxEventProcessor.EnvelopeResult.Valid(
                Fake.envelope(),
                EventPayload(
                    type = EventPayload.TYPE_SYNC_REQUEST,
                    text = "sync_request",
                    expectedPreviousSequence = 7,
                    expectedPreviousHash = ByteArray(32) { 4 },
                ),
            )
        }

        val result = on(fake, doc())

        assertEquals(MailboxMessageHandler.Kind.APPENDED, result.kind)
        // Answered with the AD-08b anchor, never surfaced as an alert.
        assertEquals(1, fake.repairResponses.size)
        assertEquals(Triple("pair-1", 7L, ByteArray(32) { 4 }.toList()), fake.repairResponses.single().let {
            Triple(it.first, it.second, it.third.toList())
        })
        assertEquals(emptyList<String>(), fake.notified)
        assertEquals(listOf("uid-me" to "m1"), fake.acks)
    }

    @Test
    fun syncRequestWithoutAnchorFields_isLoggedNoOp() {
        val fake = Fake().apply {
            outcome = MailboxEventProcessor.EnvelopeResult.Valid(
                Fake.envelope(),
                EventPayload(type = EventPayload.TYPE_SYNC_REQUEST, text = "sync_request"),
            )
        }

        val result = on(fake, doc())

        assertEquals(MailboxMessageHandler.Kind.APPENDED, result.kind)
        assertEquals(emptyList<Triple<String, Long, ByteArray>>(), fake.repairResponses)
    }

    @Test
    fun invalidSignatureDuringAnActiveRepair_isTamperSuspected() {
        val fake = Fake().apply {
            outcome = MailboxEventProcessor.EnvelopeResult.InvalidSignature
            tamperRepairing = true
        }

        val result = on(fake, doc())

        assertEquals(MailboxMessageHandler.Kind.TAMPER_SUSPECTED, result.kind)
        assertEquals(1, fake.tamperChecks)
        // Not incorporated, so not acked and not marked processed.
        assertEquals(emptyList<Pair<String, String>>(), fake.acks)
    }

    @Test
    fun invalidSignatureWithoutARepair_staysOrdinaryInvalid() {
        val fake = Fake().apply { outcome = MailboxEventProcessor.EnvelopeResult.InvalidSignature }

        assertEquals(MailboxMessageHandler.Kind.BAD_SIGNATURE, on(fake, doc()).kind)
        assertEquals(1, fake.tamperChecks)
    }

    private companion object {
        fun ecKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
    }
}
