package com.example.myexodus.accountability.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myexodus.accountability.RoundTripTestEntryPoint
import com.example.myexodus.accountability.enrollment.PairingFlow
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Two-device day leg (2026-10-04): the X6532 (real identity, real [PairingFlow]) joins the
 * production ceremony invitation the owner's UI created on the X6532C. Driven by
 * instrumentation because the X6532's Partner tab is frozen on the Ended-unpair card (the
 * harness partner's uid is unrecoverable — see PartnerTestIdentity#signIn), while the
 * ceremony's join path itself is the same production call the UI makes.
 *
 * Preconditions: app launched + signed in on the X6532; warm signing window tapped
 * ("Warm signing window" in PairingTestActivity); a live invitation code from the X6532C.
 * Run: adb shell am instrument -w -r -e joinCode <code> \
 *        -e class com.example.myexodus.accountability.sync.JoinWithCodeDeviceInstrumentedTest \
 *        com.example.myexodus.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class JoinWithCodeDeviceInstrumentedTest {

    @Test
    fun joinWithOwnerCode_pairsRealIdentityWithTheInviter(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ep = EntryPointAccessors.fromApplication(context, RoundTripTestEntryPoint::class.java)
        assertWarmSigningWindow(ep.signer())

        val code = InstrumentationRegistry.getArguments().getString("joinCode")
            ?: error("pass -e joinCode <invitation code>")
        val uidA = FirebaseAuth.getInstance().currentUser?.uid
            ?: error("no signed-in identity — launch the app once and re-run")
        harnessLog("join leg: uid=$uidA code=$code")

        val outcome = ep.pairingFlow().completeWithCode(code, uidA, ep.signer().beginSign(), myLabel = "X6532")
        harnessLog("join outcome: $outcome")
        assertTrue("join failed: $outcome", outcome is PairingFlow.Outcome.Paired)
        harnessLog("SAS (must match the initiator screen): ${(outcome as PairingFlow.Outcome.Paired).sas}")
    }
}
