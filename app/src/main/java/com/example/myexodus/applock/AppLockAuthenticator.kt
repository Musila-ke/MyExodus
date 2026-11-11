package com.example.myexodus.applock

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import javax.crypto.Cipher
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Plan §8.5/§8.6 capability preflight before any prompt is raised. */
sealed interface AppLockPreflight {
    data object Ready : AppLockPreflight
    data object NoScreenLock : AppLockPreflight
    data object NoSupportedAuthenticator : AppLockPreflight
}

sealed interface AppLockAuthResult {
    /** The cipher returned by the successful callback — never the one handed in. */
    data class Authorized(val cipher: Cipher) : AppLockAuthResult
    data object Cancelled : AppLockAuthResult
}

/**
 * OS authentication for the auth-bound vault key (plan §8.5/§8.6).
 *
 * API 30+: the wrapping cipher is handed to `BiometricPrompt` as a `CryptoObject`; only the
 * Cipher delivered by the successful callback may perform the operation, and the attempt must
 * still be current (§8.8 attempt IDs). Combined strong biometrics + device credential, with
 * confirmation required and no negative button (the library rejects one with
 * DEVICE_CREDENTIAL).
 *
 * API 26-29: there is no crypto-bound prompt. The caller launches [legacyConfirmationIntent]
 * through a registered Activity Result launcher and, on `RESULT_OK`, immediately initializes
 * and uses the time-bound cipher (§8.6). A cancel or a late result grants nothing.
 */
object AppLockAuthenticator {

    /** Must never suspend forever: a dropped OEM sheet used to wedge every later prompt. */
    private const val PROMPT_TIMEOUT_MS = 120_000L

    fun preflight(context: Context): AppLockPreflight {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        if (keyguard?.isDeviceSecure() != true) return AppLockPreflight.NoScreenLock
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // Legacy keys are authorized by the Keyguard credential confirmation itself.
            return AppLockPreflight.Ready
        }
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        return if (BiometricManager.from(context).canAuthenticate(authenticators) ==
            BiometricManager.BIOMETRIC_SUCCESS
        ) {
            AppLockPreflight.Ready
        } else {
            AppLockPreflight.NoSupportedAuthenticator
        }
    }

    /**
     * API 30+ only. [isAttemptCurrent] is checked at the callback boundary so a cancelled or
     * superseded attempt cannot publish access even if the OS answers late.
     */
    suspend fun authenticateCrypto(
        activity: FragmentActivity,
        title: String,
        subtitle: String?,
        cipher: Cipher,
        isAttemptCurrent: () -> Boolean,
    ): AppLockAuthResult {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            "CryptoObject prompt requires API 30+; legacy keys confirm through Keyguard"
        }
        return withContext(Dispatchers.Main.immediate) {
            withTimeoutOrNull<AppLockAuthResult>(PROMPT_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    val prompt = BiometricPrompt(
                        activity,
                        ContextCompat.getMainExecutor(activity),
                        object : BiometricPrompt.AuthenticationCallback() {
                            override fun onAuthenticationSucceeded(
                                result: BiometricPrompt.AuthenticationResult,
                            ) {
                                val authorized = result.cryptoObject?.cipher
                                val decision = if (authorized != null && isAttemptCurrent()) {
                                    AppLockAuthResult.Authorized(authorized)
                                } else {
                                    AppLockAuthResult.Cancelled
                                }
                                continuation.resume(decision)
                            }

                            override fun onAuthenticationError(
                                errorCode: Int,
                                errString: CharSequence,
                            ) {
                                continuation.resume(AppLockAuthResult.Cancelled)
                            }

                            override fun onAuthenticationFailed() = Unit
                        },
                    )
                    continuation.invokeOnCancellation { prompt.cancelAuthentication() }
                    val promptInfo = BiometricPrompt.PromptInfo.Builder()
                        .setTitle(title)
                        .apply { if (subtitle != null) setSubtitle(subtitle) }
                        .setAllowedAuthenticators(
                            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                                BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                        )
                        .setConfirmationRequired(true)
                        .build()
                    prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
                }
            } ?: AppLockAuthResult.Cancelled
        }
    }

    /** API 26-29 §8.6 Keyguard confirmation, or null when no secure lock is set. */
    fun legacyConfirmationIntent(
        context: Context,
        title: CharSequence,
        description: CharSequence,
    ): Intent? {
        val keyguard = context.getSystemService(KeyguardManager::class.java) ?: return null
        if (!keyguard.isDeviceSecure()) return null
        @Suppress("DEPRECATION")
        return keyguard.createConfirmDeviceCredentialIntent(title, description)
    }
}
