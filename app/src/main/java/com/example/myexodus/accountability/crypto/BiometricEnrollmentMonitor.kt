package com.example.myexodus.accountability.crypto

import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.os.Build
import androidx.core.content.edit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §8.2 biometric invalidation polling. Android ignores `setInvalidatedByBiometricEnrollment`
 * when a positive auth timeout is set (§5), so enrollment changes are not detected by the
 * Keystore. The app compares the current `BiometricManager.canAuthenticate(BIOMETRIC_STRONG)`
 * state against the cached state at startup and on ON_RESUME; any change triggers Identity
 * Migration (key rotation).
 *
 * The cache is plain SharedPreferences (Decision 12 — no security-crypto): the cached value
 * is public system state (canAuthenticate status + deviceSecure flag), not a secret; it holds
 * nothing the plaintext file wouldn't reveal by calling the same APIs directly.
 *
 * Known ceiling (documented in plan §5): this detects coarse state transitions only — a new
 * fingerprint added while others remain enrolled is not distinguishable by any app-level API.
 */
@Singleton
class BiometricEnrollmentMonitor @Inject constructor(context: Context) {

    // Stored as a property: K2 (built-in Kotlin) fails to resolve Context-typed constructor
    // params inside method bodies (repro 2026-08-28); property receivers work.
    private val appContext: Context = context

    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Caches the current enrollment state; returns true when it changed since last check. */
    fun checkAndCacheEnrollmentState(): Boolean {
        val now = currentEnrollmentState()
        val before = prefs.getString(KEY_ENROLLMENT_STATE, null)
        val changed = before != null && before != now
        prefs.edit { putString(KEY_ENROLLMENT_STATE, now) }
        return changed
    }

    private fun currentEnrollmentState(): String {
        val keyguard = appContext.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        // BiometricManager.from() is trimmed from the android-37.0 stubs (javap-verified
        // 2026-08-28); getSystemService is the runtime-equivalent path. BIOMETRIC_STRONG
        // exists only from API 30; below that the poll has no signal (the protocol itself
        // already requires API 31 for in-Keystore ECDH), so the state is pinned to UNSUPPORTED.
        if (Build.VERSION.SDK_INT < 30) return "UNSUPPORTED|${keyguard.isDeviceSecure}"
        val biometric = appContext.getSystemService(Context.BIOMETRIC_SERVICE) as BiometricManager
        val status = biometric.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        return "$status|${keyguard.isDeviceSecure}"
    }

    companion object {
        private const val PREFS_NAME = "biometric_enrollment_state"
        private const val KEY_ENROLLMENT_STATE = "enrollment_state"
    }
}
