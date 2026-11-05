package com.example.myexodus.accountability.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myexodus.accountability.BilateralDeletion
import com.example.myexodus.accountability.PartnerTestIdentity
import com.example.myexodus.accountability.RoundTripTestEntryPoint
import com.example.myexodus.accountability.crypto.AccountabilityKeyStore
import com.example.myexodus.accountability.enrollment.EnrollmentTranscript
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * §19.5 item 4 on the real device (ADR #106): with TWO live pairings, unpairing one destroys
 * only that pair's agreement key — the sibling's key material and CEK domain are untouched,
 * the legacy device-level key survives while other enrollments remain, and a second unpair
 * (the `IllegalStateException: agreement key missing` report) still completes.
 *
 * This is the device half the JVM matrix cannot cover: A's per-pair keys here are REAL
 * AndroidKeystore entries (`generatePairAgreementKey` / `destroyPairAgreementKey` via the DI
 * graph), so what is asserted is the keystore's actual state after a production `initiate` —
 * not a fake's recorded calls. The virtual partners are two distinct secondary identities
 * (their own FirebaseApp/uid/DB); their protocol legs run production code, and the shared
 * [PartnerTestKeys] keypair is legal because `registerPairing`'s SAME_KEY clause compares
 * within one pair.
 *
 * Preconditions: the app's anonymous user is signed in, and the biometric signing window is
 * warm (warm it with a production signing prompt; one window covers every signature
 * below). Run the app process down: `am instrument` starts it fresh.
 *
 * Self-cleaning: both virtual pairs are unpaired by the end, so the roster keeps only pairs
 * that predate the run. The unpair journal rows (partner never acks a virtual uid) age out as
 * LOCAL_ONLY_TERMINATED per AD-13b — the same honest terminal state a real unreachable partner
 * yields.
 */
@RunWith(AndroidJUnit4::class)
class PartnerUnpairIsolationInstrumentedTest {

    @Test
    fun unpairOneOfTwoPairs_leavesTheSiblingKeyAlive_andTheSecondUnpairStillWorks(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ep = EntryPointAccessors.fromApplication(context, RoundTripTestEntryPoint::class.java)

        assumePartnerHarness(ep)
        val uidA = FirebaseAuth.getInstance().currentUser?.uid
            ?: error("no signed-in identity — launch the app once (anonymous auth) and re-run")
        val preCount = ep.enrollmentDao().all().size

        val partnerB = PartnerTestIdentity(context, ep.cekDerivation(), ep.eventCipher(), ep.identityEpochStore())
        val partnerC = PartnerTestIdentity(
            context,
            ep.cekDerivation(),
            ep.eventCipher(),
            ep.identityEpochStore(),
            appName = "partner-test-c",
            dbName = "myexodus_partner_test_c.db",
        )
        AppCheckTestSupport.useDebugProviderIfRequested(FirebaseApp.getInstance(), partnerB.app)
        AppCheckTestSupport.useDebugProviderIfRequested(FirebaseApp.getInstance(), partnerC.app)
        val uidB = partnerB.signIn()
        val uidC = partnerC.signIn()
        val pairIdAB = EnrollmentTranscript.durablePairIdOf(uidA, uidB)
        val pairIdAC = EnrollmentTranscript.durablePairIdOf(uidA, uidC)
        harnessLog("isolation leg: A=$uidA B=$uidB C=$uidC pairs=$pairIdAB,$pairIdAC preCount=$preCount")

        pairDeviceWith(ep, partnerB, uidA, uidB, "Musila", "Isolation Partner B")
        pairDeviceWith(ep, partnerC, uidA, uidC, "Musila", "Isolation Partner C")

        val recAB = ep.enrollmentDao().getByPairId(pairIdAB) ?: error("AB enrollment missing after pairing")
        val recAC = ep.enrollmentDao().getByPairId(pairIdAC) ?: error("AC enrollment missing after pairing")
        val aliasAB = recAB.myAgreementKeyAlias ?: error("AB enrollment carries no per-pair alias (ADR #106)")
        val aliasAC = recAC.myAgreementKeyAlias ?: error("AC enrollment carries no per-pair alias (ADR #106)")
        assertTrue("each pair must get its own alias", aliasAB != aliasAC)

        val keystore = ep.keyStore()
        val partnerPubAC = EnrollmentTranscript.parsePublicKey(recAC.partnerAgreementPubKey)
        // The sibling's liveness baseline: its CEK derives from the pair key it will keep.
        val cekBefore = ep.cekDerivation().deriveCek(pairIdAC, keystore.agreementPrivateKeyFor(aliasAC), partnerPubAC)
        assertTrue("AB's pair key must exist before the unpair", keystore.pairAgreementPublicKey(aliasAB).encoded.isNotEmpty())

        // Unpair #1 — the reported defect's first half. 2026-09-27: shredding pair A killed
        // the device-level key every other pair derived from; here the pair key is per-pair.
        val outcomeAB = ep.bilateralDeletion().initiate(pairIdAB, uidA, uidB, ep.signer().beginSign())
        assertTrue("unpair AB must delete: $outcomeAB", outcomeAB is BilateralDeletion.DeletionResult.Deleted)
        assertNull("AB's enrollment must be wiped", ep.enrollmentDao().getByPairId(pairIdAB))
        assertKeyDestroyed(keystore, aliasAB, "AB's own pair key")

        assertNotNull("AC's pair key must survive AB's unpair", keystore.pairAgreementPublicKey(aliasAC))
        assertNotNull("AC's enrollment must survive AB's unpair", ep.enrollmentDao().getByPairId(pairIdAC))
        val cekAfter = ep.cekDerivation().deriveCek(pairIdAC, keystore.agreementPrivateKeyFor(aliasAC), partnerPubAC)
        assertArrayEquals("AC's CEK domain must be untouched by AB's unpair", cekBefore, cekAfter)
        assertTrue(
            "the legacy device-level key must survive while other enrollments remain",
            runCatching { keystore.agreementPrivateKeyFor(null) }.isSuccess,
        )

        // Unpair #2 — the reported defect's second half (IllegalStateException: agreement
        // key missing, because the first shred had destroyed the shared key).
        val outcomeAC = ep.bilateralDeletion().initiate(pairIdAC, uidA, uidC, ep.signer().beginSign())
        assertTrue("the second unpair must delete too: $outcomeAC", outcomeAC is BilateralDeletion.DeletionResult.Deleted)
        assertNull("AC's enrollment must be wiped", ep.enrollmentDao().getByPairId(pairIdAC))
        assertKeyDestroyed(keystore, aliasAC, "AC's own pair key")

        // The canary invariant (§10/ADR #52): the device-level key dies with the LAST
        // enrollment, never earlier — that is what keeps a later DB wipe detectable.
        val remaining = ep.enrollmentDao().all().size
        assertEquals("no enrollment may be lost beyond the two unpaired", preCount, remaining)
        val deviceKeyAlive = runCatching { keystore.agreementPrivateKeyFor(null) }.isSuccess
        assertEquals("device key present iff an enrollment remains", remaining > 0, deviceKeyAlive)

        harnessLog("RESULT unpairIsolation=PASS remaining=$remaining deviceKeyAlive=$deviceKeyAlive")
    }

    private fun assertKeyDestroyed(keystore: AccountabilityKeyStore, alias: String, what: String) {
        val outcome = runCatching { keystore.pairAgreementPublicKey(alias) }
        assertTrue("$what must be destroyed — probe: ${outcome.exceptionOrNull()}", outcome.isFailure)
    }
}
