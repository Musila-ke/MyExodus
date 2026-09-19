package com.example.myexodus.ui.partner

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myexodus.accountability.EventCreator
import com.example.myexodus.accountability.PartnerTestIdentity
import com.example.myexodus.accountability.RoundTripTestEntryPoint
import com.example.myexodus.accountability.crypto.AccountabilitySigner
import com.example.myexodus.accountability.enrollment.EnrollmentTranscript
import com.example.myexodus.accountability.enrollment.PairingFlow
import com.example.myexodus.accountability.enrollment.PairingToken
import com.example.myexodus.accountability.serialization.EventPayload
import com.example.myexodus.accountability.sync.AppCheckTestSupport
import com.example.myexodus.accountability.sync.MailboxOutboxDrainer
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Step 12 device leg: pairs a fresh virtual partner (the §12 harness identity), has it send
 * two §6 activity events through the production creator + drainer, then reads the DI-wired
 * [PartnerDashboardSource] — the exact source the Partner tab renders. This is the one seam
 * the JVM suite cannot reach: the real keystore agreement key → CEK derivation → production
 * verify-then-decrypt on stored envelope bytes → `blocked:`/`override:` parsing.
 *
 * Same preconditions as [com.example.myexodus.accountability.sync.PartnerRoundTripInstrumentedTest]:
 * signed-in app user + warm biometric signing window (tap "Warm signing window" in
 * PairingTestActivity first). Leaves A paired with the fresh partner so the Partner tab shows
 * a live Paired state for the screenshot leg.
 */
@RunWith(AndroidJUnit4::class)
class PartnerDashboardInstrumentedTest {

    @Test
    fun pairedDashboard_decodesReceivedActivityThroughTheProductionSeam(): Unit = runBlocking {
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
        // Both apps normally run PlayIntegrity (installed at startup / harness construction);
        // on a non-attesting device both need the registered debug token instead.
        AppCheckTestSupport.useDebugProviderIfRequested(FirebaseApp.getInstance(), identity.app)
        val uidB = identity.signIn()
        val pairId = EnrollmentTranscript.durablePairIdOf(uidA, uidB)
        log("A=$uidA B=$uidB pair=$pairId")
        pairFreshPartner(ep, identity, uidA, uidB)

        val aRecord = ep.enrollmentDao().getByPairId(pairId) ?: error("A enrollment missing")
        val bRecord = identity.enrollmentDao.getByPairId(pairId) ?: error("B enrollment missing")

        // B emits one blocked + one override activity event: the two §6 vocabulary texts.
        val creator = identity.eventCreator()
        for (text in listOf("blocked:com.android.chrome", "override:com.android.settings")) {
            val result = creator.create(pairId, EventPayload(text = text), identity.signer.beginSign())
            assertTrue("B event create failed: $result", result is EventCreator.CreateResult.Created)
        }
        val drained = identity.outboxDrainer().drain(pairId, uidB, uidA)
        log("B drain: $drained")
        assertTrue(
            "drain must deliver both events",
            drained is MailboxOutboxDrainer.DrainResult.Sent && drained.eventsDelivered == 2,
        )

        log("sync: ${ep.partnerSyncCoordinator().syncOnce(null)}")

        // The dashboard read model — the exact DI-wired source the Partner tab renders.
        val ui = ep.partnerDashboardSource().load(System.currentTimeMillis())
        if (ui !is PartnerDashboardUi.Paired) {
            fail("dashboard not Paired: $ui")
            return@runBlocking
        }

        assertEquals("A must show B's attested key id", bRecord.mySenderKeyId, ui.partnerKeyId)
        assertEquals("pairing timestamp", aRecord.createdAtEpochMillis, ui.pairedAtEpochMillis)
        assertEquals("fresh receive must read FRESH", SyncFreshness.FRESH, ui.freshness)
        assertEquals("healed chain must read HEALTHY", PartnerChainStatus.HEALTHY, ui.chainStatus)
        assertEquals(2, ui.alerts.todayCount)
        val targets = ui.alerts.recent.map { it.target }.toSet()
        assertEquals(
            "both activity rows must decode through the production pipeline",
            setOf("com.android.chrome", "com.android.settings"), targets,
        )
        assertTrue("the override row must keep its kind", ui.alerts.recent.any { it.isOverride })
        assertTrue("the blocked row must keep its kind", ui.alerts.recent.any { !it.isOverride })
        log("RESULT dashboard=PASS keyId=${bRecord.mySenderKeyId} targets=$targets")
    }

    private suspend fun pairFreshPartner(
        ep: RoundTripTestEntryPoint,
        identity: PartnerTestIdentity,
        uidA: String,
        uidB: String,
    ) {
        val initiation = ep.pairingFlow().initiate(uidA)
        coroutineScope {
            val partnerOutcome = async(Dispatchers.IO) {
                identity.pairingFlow().complete(initiation.qrBytes, uidB, identity.signer.beginSign())
            }
            val await = ep.pairingFlow().awaitPartner(initiation.token, PairingToken.TTL_MILLIS)
            assertNotNull("partner material not verified", await)
            val outcomeA = ep.pairingFlow().submitInitiatorSignature(await!!, initiation.token, uidA, ep.signer().beginSign())
            val outcomeB = withTimeout(60_000L) { partnerOutcome.await() }
            assertTrue("initiator pairing failed: $outcomeA", outcomeA is PairingFlow.Outcome.Paired)
            assertTrue("partner pairing failed: $outcomeB", outcomeB is PairingFlow.Outcome.Paired)
        }
    }

    private fun assertWarmSigningWindow(signer: AccountabilitySigner) {
        val signature = signer.beginSign()
        signature.update("warm-window-probe".toByteArray())
        try {
            signer.finishSign(signature)
        } catch (e: Exception) {
            fail(
                "signing window is cold — tap \"Warm signing window\" in PairingTestActivity " +
                    "(adb shell am start -n com.example.myexodus/.accountability.PairingTestActivity) " +
                    "and re-run. Cause: ${e.message}",
            )
        }
    }

    private fun log(message: String) = Log.i(TAG, message)

    private companion object {
        const val TAG = "MyExodusPartnerTest"
    }
}
