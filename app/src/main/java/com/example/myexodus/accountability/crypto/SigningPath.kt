package com.example.myexodus.accountability.crypto

/**
 * Which prompt a signing request must raise, and why none can be raised when blocked.
 * Owner ruling 2026-09-28 (ADR #112) supersedes plan §5.20's biometric-only rule: the
 * signing key accepts `AUTH_BIOMETRIC_STRONG` or `AUTH_DEVICE_CREDENTIAL`, so a phone
 * with a screen lock but no enrolled biometrics can still approve and unpair.
 */
sealed interface SigningPath {

    /** Biometrics work — the pre-ruling flow, unchanged. */
    data object Biometric : SigningPath

    /** No usable biometrics; the screen lock (PIN/pattern/password) authorizes the key. */
    data object DeviceCredential : SigningPath

    data class Blocked(val reason: NoSignatureReason) : SigningPath
}

/** One taxonomy for every "no signature" dialog (plan §5.20/§12.3). */
enum class NoSignatureReason {

    /** No strong biometric and no secure screen lock — either fix unlocks signing. */
    NOT_ENROLLED,

    /** No usable biometrics right now and no secure screen lock. */
    UNAVAILABLE,

    /**
     * The screen lock verified, but the key refused the signature — a key generated before
     * ADR #112 accepts biometrics only (keystore parameters are immutable). Enrolling a
     * biometric restores signing; a fresh enrollment ceremony is what enables the fallback.
     */
    SCREEN_LOCK_REJECTED,
}

/**
 * Device-credential ("type whatever you have") fallback is chosen only when biometrics
 * cannot run at all; a working biometric prompt is never bypassed in favour of it.
 */
fun chooseSigningPath(
    biometricsAvailable: Boolean,
    biometricsNoneEnrolled: Boolean,
    deviceCredentialPossible: Boolean,
): SigningPath = when {
    biometricsAvailable -> SigningPath.Biometric
    deviceCredentialPossible -> SigningPath.DeviceCredential
    biometricsNoneEnrolled -> SigningPath.Blocked(NoSignatureReason.NOT_ENROLLED)
    else -> SigningPath.Blocked(NoSignatureReason.UNAVAILABLE)
}
