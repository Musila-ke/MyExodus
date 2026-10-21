package com.example.myexodus.accountability.sync

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myexodus.accountability.EventCreator
import com.example.myexodus.accountability.PartnerTestIdentity
import com.example.myexodus.accountability.RoundTripTestEntryPoint
import com.example.myexodus.accountability.enrollment.EnrollmentTranscript
import com.example.myexodus.accountability.serialization.EventPayload
import com.example.myexodus.accountability.serialization.SignedEnvelope
import com.example.myexodus.accountability.serialization.UnsignedEnvelope
import com.example.myexodus.accountability.serialization.encodeSigned
import com.example.myexodus.accountability.serialization.eventHash
import com.example.myexodus.accountability.state.ChainHead
import com.example.myexodus.accountability.state.ChainStateStore
import com.example.myexodus.accountability.state.ProcessedEvent
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import dagger.hilt.android.EntryPointAccessors
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Plan Step 6 validation, driven for real: a fresh virtual partner ([PartnerTestIdentity])
 * pairs through the production ceremony against the live rules + relay, then pushes the
 * pipeline through its failure edges — duplicate delivery, out-of-order buffering, a
 * signature-valid/decryption-invalid envelope, and the crash window between chain commit
 * and ack. Every assertion reads observable state (chain head, server ack docs), not
 * handler return values.
 *
 * Preconditions mirror [PartnerRoundTripInstrumentedTest]: signed-in identity, warm signing
 * window (PairingTestActivity → "Warm signing window"), app + androidTest APKs installed.
 */
@RunWith(AndroidJUnit4::class)
class PartnerSyncScenariosInstrumentedTest {

    @Test
    fun duplicateOutOfOrderCorruptAndAckCompletion(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ep = EntryPointAccessors.fromApplication(context, RoundTripTestEntryPoint::class.java)

        assertWarmSigningWindow(ep.signer())

        val uidA = FirebaseAuth.getInstance().currentUser?.uid
            ?: error("no signed-in identity — launch the app once (anonymous auth) and re-run")
        val identity = PartnerTestIdentity(
            context = context,
            cekDerivation = ep.cekDerivation(),
            eventCipher = ep.eventCipher(),
            epochStore = ep.identityEpochStore(),
        )
        AppCheckTestSupport.useDebugProviderIfRequested(FirebaseApp.getInstance(), identity.app)
        val uidB = identity.signIn()
        val pairId = EnrollmentTranscript.durablePairIdOf(uidA, uidB)
        log("A=$uidA B=$uidB pair=$pairId")

        pairDeviceWith(ep, identity, uidA, uidB, INITIATOR_LABEL, PARTNER_LABEL)
        val recordB = identity.enrollmentDao.getByPairId(pairId)
            ?: error("partner enrollment record missing after pairing")
        val senderKeyId = recordB.mySenderKeyId
        val store = ep.chainStateStore()
        val creator = identity.eventCreator()

        // --- Stage 1: duplicate delivery. The same signed bytes go out under two message ids,
        // the relay's at-least-once shape. The chain must advance once and both must ack.
        val seq1 = create(creator, identity, pairId)
        assertEquals(1L, seq1)
        val bytes1 = identity.store.storedEvent(pairId, senderKeyId, 1L)?.envelope
            ?: error("partner event 1 not stored")
        val m1 = deliver(identity, uidA, uidB, pairId, senderKeyId, bytes1)
        val m2 = deliver(identity, uidA, uidB, pairId, senderKeyId, bytes1)
        sync(ep)
        assertEquals("duplicate delivery must not advance twice", 1L, head(store, pairId, senderKeyId))
        assertTrue("first copy acked", acked(uidA, m1))
        assertTrue("duplicate copy acked", acked(uidA, m2))
        log("RESULT duplicate=PASS")

        // --- Stage 2: out-of-order. seq3 arrives first and buffers; seq2 then fills the gap
        // and the buffered successor cascades in the same pass.
        assertEquals(2L, create(creator, identity, pairId))
        assertEquals(3L, create(creator, identity, pairId))
        val bytes3 = identity.store.storedEvent(pairId, senderKeyId, 3L)?.envelope
            ?: error("partner event 3 not stored")
        val m3 = deliver(identity, uidA, uidB, pairId, senderKeyId, bytes3)
        sync(ep)
        assertEquals("seq3 must not commit ahead of seq2", 1L, head(store, pairId, senderKeyId))
        assertEquals("gap opens repair", ChainHead.REPAIR_PENDING, headRow(store, pairId, senderKeyId)?.repairState)
        assertEquals("seq3 buffered", listOf(3L), store.gapSequences(pairId, senderKeyId))
        assertTrue("orphan acked (consumed, held as a gap)", acked(uidA, m3))

        val bytes2 = identity.store.storedEvent(pairId, senderKeyId, 2L)?.envelope
            ?: error("partner event 2 not stored")
        val m4 = deliver(identity, uidA, uidB, pairId, senderKeyId, bytes2)
        sync(ep)
        val healed = headRow(store, pairId, senderKeyId)
        assertEquals("seq2 commits and seq3 cascades", 3L, healed?.lastSequence)
        assertEquals("gap closes", emptyList<Long>(), store.gapSequences(pairId, senderKeyId))
        assertEquals("repair returns to NONE", ChainHead.REPAIR_NONE, healed?.repairState)
        assertTrue("seq2 acked", acked(uidA, m4))
        log("RESULT outOfOrder=PASS")

        // --- Stage 3: decryption failure with a VALID signature. The virtual partner signs a
        // well-formed envelope whose ciphertext is not a valid AES-GCM box, so verification
        // passes and decryption is the failing step. Not acked, chain untouched.
        val headB = identity.store.localHead(pairId, senderKeyId)
            ?: error("partner head missing")
        val corrupt = UnsignedEnvelope(
            senderKeyId = senderKeyId,
            epoch = 0,
            sequence = headB.lastSequence + 1,
            previousEventHash = headB.lastEventHash,
            nonce = ByteArray(12) { 9 },
            ciphertext = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
            createdAtEpochMillis = System.currentTimeMillis(),
        )
        val signature = identity.signer.beginSign()
        signature.update(eventHash(corrupt))
        val badBytes = encodeSigned(SignedEnvelope(corrupt, identity.signer.finishSign(signature)))
        val m5 = deliver(identity, uidA, uidB, pairId, senderKeyId, badBytes)
        sync(ep)
        assertEquals("undecryptable envelope must not commit", 3L, head(store, pairId, senderKeyId))
        assertFalse("undecryptable envelope must not ack", acked(uidA, m5))
        log("RESULT decryptFailure=PASS")

        // The chain is still healthy for the same sequence number.
        assertEquals(4L, create(creator, identity, pairId))
        val bytes4 = identity.store.storedEvent(pairId, senderKeyId, 4L)?.envelope
            ?: error("partner event 4 not stored")
        val m6 = deliver(identity, uidA, uidB, pairId, senderKeyId, bytes4)
        sync(ep)
        assertEquals("real seq4 commits after the corrupt one", 4L, head(store, pairId, senderKeyId))
        assertTrue("real seq4 acked", acked(uidA, m6))

        // --- Stage 4: crash window. Seed the processed-events gate exactly as a death between
        // the chain commit and the ack leaves it, then re-deliver the message: the completion
        // path must write the missing ack WITHOUT re-applying the event.
        assertEquals(5L, create(creator, identity, pairId))
        val bytes5 = identity.store.storedEvent(pairId, senderKeyId, 5L)?.envelope
            ?: error("partner event 5 not stored")
        val m7 = deliver(identity, uidA, uidB, pairId, senderKeyId, bytes5)
        assertFalse("no ack yet", acked(uidA, m7))
        ep.accountabilityDatabase().processedEventDao()
            .insert(ProcessedEvent(m7, System.currentTimeMillis()))
        sync(ep)
        assertTrue("re-delivery completes the missing ack", acked(uidA, m7))
        assertEquals(
            "the processed message must not be applied a second time",
            4L,
            head(store, pairId, senderKeyId),
        )
        log("RESULT ackCompletion=PASS pair=$pairId")
        log("RESULT scenarios=PASS")
    }

    private suspend fun create(
        creator: EventCreator,
        identity: PartnerTestIdentity,
        pairId: String,
    ): Long {
        val result = creator.create(pairId, EventPayload(text = "scenario"), identity.signer.beginSign())
        assertTrue("partner event create failed: $result", result is EventCreator.CreateResult.Created)
        return (result as EventCreator.CreateResult.Created).sequence
    }

    private suspend fun deliver(
        identity: PartnerTestIdentity,
        recipientUid: String,
        senderUid: String,
        pairId: String,
        senderKeyId: String,
        envelope: ByteArray,
    ): String {
        val messageId = UUID.randomUUID().toString()
        identity.writeToMailbox(
            recipientUid = recipientUid,
            messageId = messageId,
            data = FirestoreSchema.mailboxMessageData(
                senderId = senderKeyId,
                senderUid = senderUid,
                pairId = pairId,
                payloadBase64 = Base64.getEncoder().encodeToString(envelope),
                keyEpoch = 0,
            ),
        )
        return messageId
    }

    private suspend fun sync(ep: RoundTripTestEntryPoint) {
        log("sync: ${ep.partnerSyncCoordinator().syncOnce(null)}")
    }

    private suspend fun head(store: ChainStateStore, pairId: String, senderKeyId: String): Long =
        headRow(store, pairId, senderKeyId)?.lastSequence ?: 0L

    private suspend fun headRow(store: ChainStateStore, pairId: String, senderKeyId: String) =
        store.localHead(pairId, senderKeyId)

    private suspend fun acked(recipientUid: String, messageId: String): Boolean =
        !FirebaseFirestore.getInstance()
            .collection("mailboxes").document(recipientUid)
            .collection("messages").document(messageId)
            .collection("acks").get().await().isEmpty

    private fun log(message: String) = Log.i(TAG, message)

    private companion object {
        const val TAG = "MyExodusPartnerTest"
        const val INITIATOR_LABEL = "Musila"
        const val PARTNER_LABEL = "Scenario Partner"
    }
}
