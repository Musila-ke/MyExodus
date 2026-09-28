package com.example.myexodus.accountability.sync

import android.util.Log
import com.example.myexodus.accountability.PartnerTestIdentity
import com.example.myexodus.accountability.RoundTripTestEntryPoint
import com.example.myexodus.accountability.crypto.AccountabilitySigner
import com.example.myexodus.accountability.enrollment.PairingFlow
import com.example.myexodus.accountability.enrollment.PairingToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

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

/** One warm biometric window (900 s) covers every signature in a device test. */
internal fun assertWarmSigningWindow(signer: AccountabilitySigner) {
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

private const val PARTNER_OUTCOME_TIMEOUT_MILLIS = 60_000L
