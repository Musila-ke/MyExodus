package com.example.myexodus.accountability.sync

import android.util.Log
import com.example.myexodus.accountability.PartnerTestIdentity
import com.example.myexodus.accountability.RoundTripTestEntryPoint
import com.example.myexodus.accountability.crypto.AccountabilitySigner
import com.example.myexodus.accountability.enrollment.PairingFlow
import com.example.myexodus.accountability.enrollment.PairingToken
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume

internal const val HARNESS_TAG = "MyExodusPartnerTest"

internal fun harnessLog(message: String) = Log.i(HARNESS_TAG, message)

/**
 * The §12 device tests' shared pairing drive: A (the real identity, through its DI-wired
 * [PairingFlow]) and a virtual partner ([PartnerTestIdentity]) run the production ceremony —
 * real registerPairing, real attestation both ways — and both roles must land Paired with the
 * same SAS. Extracted so the round-trip matrix and the §19.5 unpair-isolation test exercise
 * byte-identical ceremony semantics instead of two copies that could drift.
 */
internal suspend fun pairDeviceWith(
    ep: RoundTripTestEntryPoint,
    identity: PartnerTestIdentity,
    uidA: String,
    uidB: String,
    initiatorLabel: String,
    partnerLabel: String,
) {
    harnessLog("pairing: A initiates, B redeems, A confirms")
    val initiation = ep.pairingFlow().initiate(uidA, myLabel = initiatorLabel)
    coroutineScope {
        val partnerOutcome = async(Dispatchers.IO) {
            identity.pairingFlow().complete(
                initiation.qrBytes,
                uidB,
                identity.signer.beginSign(),
                myLabel = partnerLabel,
            )
        }
        val await = ep.pairingFlow().awaitPartner(initiation.token, PairingToken.TTL_MILLIS)
        assertNotNull("partner material not verified (initiator rejected — see MyExodusPairing log)", await)
        harnessLog("SAS (both roles must agree): ${await!!.sas}")
        val outcomeA = ep.pairingFlow().submitInitiatorSignature(await, initiation.token, uidA, ep.signer().beginSign())
        val outcomeB = withTimeout(PARTNER_OUTCOME_TIMEOUT_MILLIS) { partnerOutcome.await() }
        harnessLog("initiator=$outcomeA partner=$outcomeB")
        assertTrue("initiator pairing failed: $outcomeA", outcomeA is PairingFlow.Outcome.Paired)
        assertTrue("partner pairing failed: $outcomeB", outcomeB is PairingFlow.Outcome.Paired)
        assertEquals(
            "SAS mismatch between roles",
            (outcomeA as PairingFlow.Outcome.Paired).sas,
            (outcomeB as PairingFlow.Outcome.Paired).sas,
        )
    }
}

/**
 * The §12 harness preconditions on any device: a signed-in identity and a warm signing
 * window (PairingTestActivity's "Warm signing window"). A rig that never ran the harness —
 * a fresh AVD, which can never pair (attestation wall) — reports these legs SKIPPED in the
 * runner rather than red, and never green without proof; the X6532 harness run satisfies
 * the assumption and executes the leg for real.
 */
internal fun assumePartnerHarness(ep: RoundTripTestEntryPoint) {
    Assume.assumeTrue(
        "no signed-in identity — launch the app once (anonymous auth) and re-run",
        FirebaseAuth.getInstance().currentUser?.uid != null,
    )
    Assume.assumeTrue(
        "signing window is cold — tap \"Warm signing window\" in PairingTestActivity " +
            "(adb shell am start -n com.example.myexodus/.accountability.PairingTestActivity) " +
            "and re-run",
        isWarmSigningWindow(ep.signer()),
    )
}

/** A harness input or fixture the rig may not carry (a `-e` argument, an enrollment left by a
 *  prior harness run): absence is a SKIP with its setup instruction, never a red leg. */
internal fun <T : Any> assumePresent(value: T?, reason: String): T =
    value ?: run {
        Assume.assumeTrue(reason, false)
        throw AssertionError(reason)
    }

/** Non-throwing warm-window probe: a missing identity key or an expired 900 s window is the
 *  harness setup, not a product failure — [assumePartnerHarness] turns it into a skip. */
internal fun isWarmSigningWindow(signer: AccountabilitySigner): Boolean = runCatching {
    val signature = signer.beginSign()
    signature.update("warm-window-probe".toByteArray())
    signer.finishSign(signature)
}.isSuccess

private const val PARTNER_OUTCOME_TIMEOUT_MILLIS = 60_000L
