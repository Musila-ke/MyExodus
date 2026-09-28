package com.example.myexodus.accountability.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** ADR #112: the owner-ruled prompt-selection matrix — biometrics win when they work,
 *  the screen lock is the fallback, and blocking happens only when neither can run. */
class SigningPathTest {

    @Test
    fun biometricsAvailable_prefersBiometricOverDeviceCredential() {
        assertSame(
            SigningPath.Biometric,
            chooseSigningPath(
                biometricsAvailable = true,
                biometricsNoneEnrolled = false,
                deviceCredentialPossible = true,
            ),
        )
    }

    @Test
    fun biometricsUnavailable_withSecureLockScreen_fallsToDeviceCredential() {
        // None enrolled (PIN-only phone) …
        assertSame(
            SigningPath.DeviceCredential,
            chooseSigningPath(
                biometricsAvailable = false,
                biometricsNoneEnrolled = true,
                deviceCredentialPossible = true,
            ),
        )
        // … and hardware present but not usable right now both land on the fallback.
        assertSame(
            SigningPath.DeviceCredential,
            chooseSigningPath(
                biometricsAvailable = false,
                biometricsNoneEnrolled = false,
                deviceCredentialPossible = true,
            ),
        )
    }

    @Test
    fun noBiometric_noLockScreen_noneEnrolled_blocksWithTheEnrollFix() {
        assertEquals(
            SigningPath.Blocked(NoSignatureReason.NOT_ENROLLED),
            chooseSigningPath(
                biometricsAvailable = false,
                biometricsNoneEnrolled = true,
                deviceCredentialPossible = false,
            ),
        )
    }

    @Test
    fun noBiometric_noLockScreen_notNoneEnrolled_blocksUnavailable() {
        assertEquals(
            SigningPath.Blocked(NoSignatureReason.UNAVAILABLE),
            chooseSigningPath(
                biometricsAvailable = false,
                biometricsNoneEnrolled = false,
                deviceCredentialPossible = false,
            ),
        )
    }
}
