package com.example.myexodus.accountability

import android.app.Application
import android.content.Context
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.accountability.crypto.AccountabilitySigner
import com.example.myexodus.accountability.crypto.EventCipher
import com.example.myexodus.accountability.crypto.IdentityEpochStore
import com.example.myexodus.accountability.enrollment.CekDerivation
import com.example.myexodus.accountability.enrollment.EnrollmentRecord
import com.example.myexodus.accountability.serialization.EventPayload
import com.example.myexodus.accountability.serialization.decodePayload
import com.example.myexodus.accountability.serialization.decodeSigned
import com.example.myexodus.accountability.serialization.encodePayload
import com.example.myexodus.accountability.serialization.eventHash
import com.example.myexodus.accountability.state.AccountabilityDatabase
import com.example.myexodus.accountability.state.ChainEvent
import com.example.myexodus.accountability.state.ChainHead
import com.example.myexodus.accountability.state.ChainStateStore
import com.example.myexodus.accountability.state.SessionKeyManager
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * FR3 (§6.2) with real crypto: the creator chains an event under the pairwise CEK, signs the
 * envelope hash with the caller-provided [Signature], and normalizes to Low-S (§10) so the
 * partner's verifier accepts it. The Keystore boundary is a plain-KeyPair lambda.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EventCreatorTest {

    private lateinit var context: Context
    private lateinit var dbName: String
    private lateinit var db: AccountabilityDatabase
    private lateinit var store: ChainStateStore
    private lateinit var epochStore: IdentityEpochStore
    private lateinit var creator: EventCreator

    private val signer = AccountabilitySigner()
    private val cipher = EventCipher()
    private val cekDerivation = CekDerivation()
    private val myAgreement = ecKeyPair()
    private val mySigning = ecKeyPair()
    private val partnerAgreement = ecKeyPair()
    private val partnerSigning = ecKeyPair()
    private val mySenderKeyId = sha256Hex(mySigning.public.encoded)
    private val pairId = "pair-deletion-test"

    private val cek = cekDerivation.deriveCek(pairId, myAgreement.private, partnerAgreement.public)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbName = "test_${System.nanoTime()}.db"
        db = Room.databaseBuilder(context, AccountabilityDatabase::class.java, dbName)
            .addMigrations(AccountabilityDatabase.MIGRATION_1_2)
            .build()
        store = ChainStateStore(db)
        epochStore = IdentityEpochStore(db.identityEpochDao())
        creator = EventCreator(
            store = store,
            enrollmentDao = db.enrollmentDao(),
            cekDerivation = cekDerivation,
            eventCipher = cipher,
            signer = signer,
            epochStore = epochStore,
            agreementPrivateKey = { myAgreement.private },
            sessionKeyManager = SessionKeyManager(store),
        )
        runBlocking {
            db.enrollmentDao().upsert(
                EnrollmentRecord(
                    pairId = pairId,
                    mySenderKeyId = mySenderKeyId,
                    partnerSigningKeyId = sha256Hex(partnerSigning.public.encoded),
                    partnerSigningPubKey = partnerSigning.public.encoded,
                    partnerAgreementPubKey = partnerAgreement.public.encoded,
                    createdAtEpochMillis = 1_700_000_000_000L,
                ),
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun createdEvent_isChainedVerifiableAndDecryptable() = runTest {
        val result = creator.create(pairId, EventPayload(type = "activity", text = "check-in"), signing())
        assertEquals(1L, (result as EventCreator.CreateResult.Created).sequence)

        val row = store.chainFor(pairId).single()
        assertEquals(mySenderKeyId, row.senderKeyId)
        assertEquals(1L, row.sequence)
        assertNull(row.previousEventHash)
        assertEquals(ChainEvent.DIRECTION_SENT, row.direction)

        val signed = decodeSigned(row.envelope)
        // Partner-side verification of my event uses MY signing key.
        assertTrue(signer.verify(mySigning.public, eventHash(signed.unsigned), signed.signature))
        // Partner-side decryption with the re-derived CEK.
        val payload = decodePayload(cipher.decrypt(cek, signed.unsigned.sequence, signed.unsigned.ciphertext))
        assertEquals("activity", payload.type)
        assertEquals("check-in", payload.text)
        assertEquals(0, signed.unsigned.epoch)
    }

    @Test
    fun secondEvent_advancesSequenceAndLinksPreviousHash() = runTest {
        creator.create(pairId, EventPayload(type = "activity", text = "one"), signing())
        creator.create(pairId, EventPayload(type = "activity", text = "two"), signing())

        val rows = store.chainFor(pairId)
        val second = decodeSigned(rows[1].envelope)
        assertEquals(2L, second.unsigned.sequence)
        assertArrayEquals(eventHash(decodeSigned(rows[0].envelope).unsigned), second.unsigned.previousEventHash)
    }

    @Test
    fun epochBump_isEmbeddedInNewEvents() = runTest {
        assertEquals(1, epochStore.bump())
        creator.create(pairId, EventPayload(text = ""), signing())
        val signed = decodeSigned(store.chainFor(pairId).single().envelope)
        assertEquals(1, signed.unsigned.epoch)
    }

    @Test
    fun notEnrolled_returnsFailed() = runTest {
        val result = creator.create("pair-unknown", EventPayload(text = ""), signing())
        assertEquals(EventCreator.CreateResult.Failed("not enrolled"), result)
    }

    @Test
    fun rotationDue_newEnvelopeCarriesTheNextEpochUnderItsSessionKey() = runTest {
        // §8 Step 15 / AD-12: the 500th event of an epoch is the last one; the next envelope
        // is created under the rotated epoch's derived session key and declares it on the wire.
        db.chainHeadDao().insert(
            ChainHead(
                pairId = pairId,
                senderKeyId = mySenderKeyId,
                lastSequence = SessionKeyManager.ROTATE_AFTER_EVENTS,
                lastEventHash = ByteArray(32) { 3 },
                keyEpoch = 0,
                keyEpochStartedAtEpochMillis = 1_700_000_000_000L,
                keyEpochStartSequence = 0,
            ),
        )

        val created = creator.create(pairId, EventPayload(type = "activity", text = "rotated"), signing())
        assertEquals(501L, (created as EventCreator.CreateResult.Created).sequence)

        val signed = decodeSigned(store.chainFor(pairId).single().envelope)
        assertEquals(1, signed.unsigned.keyEpoch)
        assertEquals(1, db.chainHeadDao().get(pairId, mySenderKeyId)!!.keyEpoch)

        // The partner derives the same session key from the CEK alone — no key exchange.
        val sessionKey = cekDerivation.deriveSessionKey(cek, 1)
        val payload = decodePayload(cipher.decrypt(sessionKey, signed.unsigned.sequence, signed.unsigned.ciphertext))
        assertEquals("rotated", payload.text)
    }

    // Note: a same-millisecond double-create collapses to Duplicate via the chain store
    // (covered by ChainStateStoreTest); the retry-then-transmit path for deletion requests
    // is covered in BilateralDeletionTest.duplicateDeletionRequest_stillTransmits.

    private fun signing(): Signature = Signature.getInstance("SHA256withECDSA").apply {
        initSign(mySigning.private)
    }

    companion object {
        private fun ecKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

        private fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
