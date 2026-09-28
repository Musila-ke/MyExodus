package com.example.myexodus.accountability.crypto

import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.os.Build
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §8.2 biometric invalidation polling. The Keystore cannot see these changes on this key:
 * `setInvalidatedByBiometricEnrollment` "applies only to keys which require user
 * authentication ... and if no positive validity duration has been set" (android-36 javadoc,
 * verified 2026-09-28), and the signing key carries the 900 s timeout (§5). So the app
 * compares the live `BiometricManager.canAuthenticate(BIOMETRIC_STRONG)` state (plus the
 * device-secure flag) against the last committed state at startup and on ON_RESUME; a change
 * runs Identity Migration (key rotation).
 *
 * Commit discipline: detection never writes — the caller commits after the migration
 * succeeded. A failed rotation therefore stays pending and retries on the next check.
 * The first check (nothing committed yet) is a baseline, never a change: an app upgrade
 * must not rotate an existing identity.
 *
 * The cache is plain SharedPreferences (Decision 12 — no security-crypto): the cached value
 * is public system state (canAuthenticate status + deviceSecure flag), not a secret; it holds
 * nothing the plaintext file wouldn't reveal by calling the same APIs directly.
 *
 * Known ceiling (documented in plan §5): this detects coarse state transitions only — a new
 * fingerprint added while others remain enrolled is not distinguishable by any app-level API.
 */
@Singleton
class BiometricEnrollmentMonitor @Inject constructor(@ApplicationContext context: Context) {

    // Stored as a property: K2 (built-in Kotlin) fails to resolve Context-typed constructor
    // params inside method bodies (repro 2026-08-28); property receivers work.
    private val appContext: Context = context

    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** True when the live state differs from the last committed one; false on first run. */
    fun enrollmentStateChanged(): Boolean {
        val committed = prefs.getString(KEY_ENROLLMENT_STATE, null) ?: return false
        return committed != currentEnrollmentState()
    }

    /** Records the live state as committed — after a successful migration, or when nothing
     *  changed (establishes the baseline). A failed migration must stay uncommitted. */
    fun commitEnrollmentState() {
        val now = currentEnrollmentState()
        if (prefs.getString(KEY_ENROLLMENT_STATE, null) != now) {
            prefs.edit { putString(KEY_ENROLLMENT_STATE, now) }
        }
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
