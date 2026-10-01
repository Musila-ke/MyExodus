package com.example.myexodus.ui.partner

import com.example.myexodus.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks the #22 contract: server verdict codes map to specific copy, and the connectivity
 * ("check both phones are online") body is reached only by a genuinely transport-shaped
 * failure (null reason), never by a verdict.
 */
class PairingFailureCopyTest {

    @Test
    fun verdictCodesMapToSpecificCopy() {
        assertEquals(
            R.string.partner_ceremony_failed_active_title to R.string.partner_ceremony_failed_active_body,
            pairingFailureCopy("PAIRING_ACTIVE"),
        )
        assertEquals(
            R.string.partner_ceremony_failed_stale_title to R.string.partner_ceremony_failed_stale_body,
            pairingFailureCopy("TRANSCRIPT_MISMATCH"),
        )
        assertEquals(
            R.string.partner_ceremony_failed_same_device_title to R.string.partner_ceremony_failed_same_device_body,
            pairingFailureCopy("SAME_KEY"),
        )
        assertEquals(
            R.string.partner_ceremony_failed_same_account_title to R.string.partner_ceremony_failed_same_account_body,
            pairingFailureCopy("SELF_PAIRING"),
        )
    }

    @Test
    fun localActiveSentinelSharesTheActiveCopy() {
        assertEquals(
            pairingFailureCopy("PAIRING_ACTIVE"),
            pairingFailureCopy("an active pairing with different material already exists"),
        )
    }

    @Test
    fun onlyTransportShapeGetsConnectivityCopy() {
        assertEquals(
            R.string.partner_ceremony_offline_title to R.string.partner_ceremony_offline_body,
            pairingFailureCopy(null),
        )
        // A verdict with an unrecognised code must NOT read as a network problem.
        assertEquals(
            R.string.partner_ceremony_failed_title to R.string.partner_ceremony_failed_body,
            pairingFailureCopy("SOME_FUTURE_CODE"),
        )
    }

    /** The invite/join prepare throw-path maps a server denial to this code (task #28). */
    @Test
    fun serverRejectionsNeverReadAsConnectivity() {
        assertEquals(
            R.string.partner_ceremony_failed_title to R.string.partner_ceremony_failed_body,
            pairingFailureCopy("SERVER_REJECTED"),
        )
    }

    @Test
    fun identityRejectionsMapToVerifyCopy() {
        assertEquals(
            R.string.partner_ceremony_failed_verify_title to R.string.partner_ceremony_failed_verify_body,
            pairingFailureCopy("KEY_SOFTWARE"),
        )
        assertEquals(
            R.string.partner_ceremony_failed_verify_title to R.string.partner_ceremony_failed_verify_body,
            pairingFailureCopy("attestation: CHAIN_UNTRUSTED"),
        )
    }
}
