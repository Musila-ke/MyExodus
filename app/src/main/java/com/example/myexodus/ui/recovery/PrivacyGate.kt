package com.example.myexodus.ui.recovery

import android.app.KeyguardManager
import android.os.SystemClock
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** What [PrivacyGate.ensureUnlocked] decided; the caller decides how to render each case. */
sealed interface GateResult {
    /** Fresh unlock (or the 60 s window was still open): the gated surface may render. */
    data object Unlocked : GateResult

    /** The user dismissed the prompt — the surface must not render. */
    data object Cancelled : GateResult

    /** No secure lock screen exists to gate with (plan v3 W6): access granted, banner owed. */
    data object UnlockedWithoutSecureLock : GateResult
}

/**
 * The recovery surfaces' privacy gate (`MainActivity.signWithBiometric`'s prompt plumbing,
 * minus the signing): `BIOMETRIC_WEAK or DEVICE_CREDENTIAL`, no CryptoObject — nothing is
 * sealed by this prompt, it is a shoulder-surfing/unauthorized-open lock, not a key gate.
 *
 * The 60 s window is memory-only ([SystemClock.elapsedRealtime], never persisted): a process
 * death re-locks. A device with no secure lock has nothing to gate with, so the gate steps
 * aside and the caller shows the one-time explanation banner instead of a dead end.
 */
object PrivacyGate {

    /** Plan v3 W6: one unlock covers every gated surface for 60 s. */
    const val WINDOW_MS = 60_000L

    @Volatile private var unlockedAtElapsedMs = 0L

    /** Test seam: the window is a pure function of two clock readings. */
    fun isWindowOpen(nowElapsedMs: Long, unlockedAtElapsedMs: Long): Boolean =
        unlockedAtElapsedMs > 0L && nowElapsedMs - unlockedAtElapsedMs in 0..WINDOW_MS

    fun isUnlocked(nowElapsedMs: Long = SystemClock.elapsedRealtime()): Boolean =
        isWindowOpen(nowElapsedMs, unlockedAtElapsedMs)

    /** Called only from the auth callback path — never on bypass, so a lock-less device still
     *  re-evaluates on every open (it has nothing to remember). */
    internal fun markUnlocked(nowElapsedMs: Long = SystemClock.elapsedRealtime()) {
        unlockedAtElapsedMs = nowElapsedMs
    }

    /** Test seam for the memory window. */
    internal fun reset() {
        unlockedAtElapsedMs = 0L
    }

    suspend fun ensureUnlocked(activity: FragmentActivity, title: String): GateResult {
        if (isUnlocked()) return GateResult.Unlocked
        if (!hasSecureLock(activity)) {
            Log.i(TAG, "privacy gate bypassed: no secure lock screen")
            return GateResult.UnlockedWithoutSecureLock
        }
        val authenticated = prompt(activity, title)
        if (authenticated) markUnlocked()
        return if (authenticated) GateResult.Unlocked else GateResult.Cancelled
    }

    private fun hasSecureLock(activity: FragmentActivity): Boolean =
        activity.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    private suspend fun prompt(activity: FragmentActivity, title: String): Boolean =
        suspendCancellableCoroutine { cont ->
            val prompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(
                        result: BiometricPrompt.AuthenticationResult,
                    ) {
                        cont.resume(true)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        // Cancellation and lockout both land here; both mean "still locked".
                        Log.w(TAG, "privacy gate prompt: $errString")
                        cont.resume(false)
                    }

                    override fun onAuthenticationFailed() = Unit
                },
            )
            cont.invokeOnCancellation { prompt.cancelAuthentication() }
            // BIOMETRIC_WEAK | DEVICE_CREDENTIAL forbids a negative button (the framework
            // throws) — the system prompt carries its own cancel affordance.
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_WEAK or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                )
                .build()
            prompt.authenticate(promptInfo)
        }

    private const val TAG = "PrivacyGate"
}
