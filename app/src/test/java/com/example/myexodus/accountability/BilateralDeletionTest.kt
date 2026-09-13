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
import com.example.myexodus.accountability.state.AccountabilityDatabase
import com.example.myexodus.accountability.state.ChainStateStore
import com.example.myexodus.accountability.sync.FirestoreRelay
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §10 order-of-operations: the DELETION_REQUEST must be chained and transmitted BEFORE the
 * agreement key is destroyed — local shredding without transmission breaks the bilateral
 * contract. The relay and Keystore are fakes behind the constructor seams.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BilateralDeletionTest {

    private lateinit var context: Context
    private lateinit var dbName: String
    private lateinit var db: AccountabilityDatabase
    private lateinit var store: ChainStateStore
    private lateinit var creator: EventCreator
    private lateinit var deletion: BilateralDeletion

    private val signer = AccountabilitySigner()
    private val cipher = EventCipher()
    private val cekDerivation = CekDerivation()
    private val myAgreement = ecKeyPair()
    private val mySigning = ecKeyPair()
    private val partnerAgreement = ecKeyPair()
    private val partnerSigning = ecKeyPair()
    private val mySenderKeyId = sha256Hex(mySigning.public.encoded)
    private val pairId = "pair-deletion-test"

    private var destroyed = false
    private val deletedPairs = mutableListOf<String>()
    private val tombstoned = mutableListOf<String>()
    private val ops = mutableListOf<String>()
    private var failTombstone = false
    private var drainResults = ArrayDeque<FirestoreRelay.DrainResult>()
    private val drained = mutableListOf<Triple<String, String, String>>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbName = "test_${System.nanoTime()}.db"
        db = Room.databaseBuilder(context, AccountabilityDatabase::class.java, dbName)
            .addMigrations(AccountabilityDatabase.MIGRATION_1_2)
            .build()
        store = ChainStateStore(db)
        val epochStore = IdentityEpochStore(db.identityEpochDao())
        creator = EventCreator(
            store = store,
            enrollmentDao = db.enrollmentDao(),
            cekDerivation = cekDerivation,
            eventCipher = cipher,
            signer = signer,
            epochStore = epochStore,
            agreementPrivateKey = { myAgreement.private },
        )
        deletion = BilateralDeletion(
            creator = creator,
            drainOutbox = { pairId, authorUid, partnerUid ->
                drained += Triple(pairId, authorUid, partnerUid)
                drainResults.removeFirst()
            },
            enrollmentDao = db.enrollmentDao(),
            store = store,
            destroyAgreementKey = { destroyed = true },
            deletePairState = {
                deletedPairs += it
                ops += "delete" // §10 step 4: server-side invalidation
            },
            // The rules gate the tombstone's create on the pair doc existing, so the
            // tombstone must be written before the pair doc is deleted.
            writeDeletionTombstone = {
                if (failTombstone) throw IllegalStateException("relay down")
                tombstoned += it
                ops += "tombstone"
            },
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
            // Seed one ordinary event so the wipe has chain data to remove.
            creator.create(pairId, EventPayload(type = "activity", text = "seeded"), signing())
        }
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun initiate_transmitsDeletion_thenShreds() = runTest {
        // Batch 1 delivers the deletion event; batch 2 finds the outbox empty (Sent(0)) and ends the loop.
        drainResults += FirestoreRelay.DrainResult.Sent(1)
        drainResults += FirestoreRelay.DrainResult.Sent(0)
        val result = deletion.initiate(pairId, "uid-a", "uid-b", signing())

        assertEquals(BilateralDeletion.DeletionResult.Deleted, result)
        assertTrue("agreement key destroyed only after transmission", destroyed)
        val expected = listOf(pairId to "uid-a" to "uid-b")
        assertEquals(
            expected + expected,
            drained.map { it.first to it.second to it.third },
        )
        assertEquals(listOf(pairId), deletedPairs) // §10 step 4: relay pair state deleted
        assertEquals(listOf(pairId), tombstoned)
        assertEquals("tombstone before pair-doc delete (rules require the pair doc to exist)",
            listOf("tombstone", "delete"), ops)
        assertNull(db.enrollmentDao().getByPairId(pairId))
        assertTrue(store.chainFor(pairId).isEmpty())
    }

    @Test
    fun initiate_drainsEveryPendingBatch_beforeShredding() = runTest {
        // Older attempts queued behind the deletion event must all reach the mailbox before
        // the key dies — otherwise they would be stranded forever.
        drainResults += FirestoreRelay.DrainResult.Sent(2)
        drainResults += FirestoreRelay.DrainResult.Sent(1)
        drainResults += FirestoreRelay.DrainResult.Sent(0)
        val result = deletion.initiate(pairId, "uid-a", "uid-b", signing())

        assertEquals(BilateralDeletion.DeletionResult.Deleted, result)
        assertTrue(destroyed)
        assertEquals(3, drained.size)
    }

    @Test
    fun initiate_permissionDenied_keepsEverything() = runTest {
        drainResults += FirestoreRelay.DrainResult.PermissionDenied
        val result = deletion.initiate(pairId, "uid-a", "uid-b", signing())

        assertEquals(BilateralDeletion.DeletionResult.Failed("rules rejected the deletion event"), result)
        assertFalse("no shred without transmission", destroyed)
        assertTrue("pair data intact", store.chainFor(pairId).isNotEmpty())
        assertTrue("enrollment intact", db.enrollmentDao().getByPairId(pairId) != null)
    }

    @Test
    fun initiate_relayFailure_isTransmissionPending() = runTest {
        drainResults += FirestoreRelay.DrainResult.Failed("offline")
        val result = deletion.initiate(pairId, "uid-a", "uid-b", signing())

        assertEquals(BilateralDeletion.DeletionResult.TransmissionPending, result)
        assertFalse(destroyed)
    }

    @Test
    fun duplicateDeletionRequest_stillTransmits() = runTest {
        // First attempt never reached the mailbox — retry is the contract.
        drainResults += FirestoreRelay.DrainResult.Failed("offline")
        assertEquals(BilateralDeletion.DeletionResult.TransmissionPending, deletion.initiate(pairId, "uid-a", "uid-b", signing()))

        // Identical request: creator reports Duplicate, but the event must still be drained.
        drainResults += FirestoreRelay.DrainResult.Sent(1)
        drainResults += FirestoreRelay.DrainResult.Sent(0)
        assertEquals(BilateralDeletion.DeletionResult.Deleted, deletion.initiate(pairId, "uid-a", "uid-b", signing()))
        assertTrue(destroyed)
        assertEquals(3, drained.size) // first initiate (Failed) + second initiate (Sent(1), Sent(0))
    }

    @Test
    fun handleIncoming_destroysKeyAndWipesPair() = runTest {
        deletion.handleIncoming(pairId)

        assertTrue(destroyed)
        assertEquals(listOf(pairId), deletedPairs) // §10 step 4: server-side invalidation
        assertEquals(listOf(pairId), tombstoned)
        assertEquals(listOf("tombstone", "delete"), ops)
        assertNull(db.enrollmentDao().getByPairId(pairId))
        assertTrue(store.chainFor(pairId).isEmpty())
    }

    @Test
    fun tombstoneFailure_doesNotBlockDeletion() = runTest {
        // Best-effort by design: the bilateral contract (transmit, shred, wipe) must not
        // hinge on a hardening write to the relay.
        failTombstone = true
        drainResults += FirestoreRelay.DrainResult.Sent(1)
        drainResults += FirestoreRelay.DrainResult.Sent(0)
        val result = deletion.initiate(pairId, "uid-a", "uid-b", signing())

        assertEquals(BilateralDeletion.DeletionResult.Deleted, result)
        assertTrue(destroyed)
        assertEquals(listOf(pairId), deletedPairs)
        assertTrue(tombstoned.isEmpty())
    }

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
