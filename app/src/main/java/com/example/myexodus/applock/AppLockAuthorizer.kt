package com.example.myexodus.applock

import android.app.Activity
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.FragmentActivity
import com.example.myexodus.R
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** §8.8: bounds the Keyguard sheet; the 60 s return window alone cannot end a stuck await. */
private const val LEGACY_CONFIRMATION_TIMEOUT_MS = 120_000L

/**
 * §5.5/§5.6: the one authorizer for the protected tree. API 30+ authenticates the per-use
 * cipher through `BiometricPrompt`; API 26-29 confirms the device credential through Keyguard
 * and initializes the time-bound cipher only after `RESULT_OK`. A cancel grants nothing (null),
 * and a Keystore failure propagates so the controller can route it to recovery.
 */
@Composable
fun rememberVaultCipherAuthorizer(controller: AppLockController): VaultCipherAuthorizer {
    val activity = LocalActivity.current as? FragmentActivity
    val title = stringResource(R.string.app_lock_prompt_title)
    val subtitle = stringResource(R.string.app_lock_prompt_subtitle)
    val pendingLegacy = remember { AtomicReference<CancellableContinuation<Boolean>?>(null) }
    val legacyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        // A late result after a cancelled attempt still grants nothing: the controller
        // re-checks the attempt id before any cipher is used.
        pendingLegacy.getAndSet(null)?.resume(result.resultCode == Activity.RESULT_OK)
    }
    return remember(activity, legacyLauncher, title, subtitle, controller) {
        object : VaultCipherAuthorizer {
            override suspend fun authorize(initialize: () -> Cipher): Cipher? {
                if (activity == null) return null
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val cipher = initialize()
                    val result = AppLockAuthenticator.authenticateCrypto(
                        activity,
                        title,
                        subtitle,
                        cipher,
                        isAttemptCurrent = { true },
                    )
                    return (result as? AppLockAuthResult.Authorized)?.cipher
                }
                val intent = AppLockAuthenticator.legacyConfirmationIntent(activity, title, subtitle)
                    ?: return null
                val confirmed = withTimeoutOrNull(LEGACY_CONFIRMATION_TIMEOUT_MS) {
                    withContext(Dispatchers.Main.immediate) {
                        suspendCancellableCoroutine<Boolean> { continuation ->
                            // §8.8: Keyguard stops the host mid-attempt; the token keeps the
                            // attempt alive across that stop.
                            controller.beginCredentialChallenge()
                            pendingLegacy.set(continuation)
                            continuation.invokeOnCancellation {
                                pendingLegacy.compareAndSet(continuation, null)
                            }
                            // A managed device can lack the Keyguard activity; grant nothing.
                            runCatching { legacyLauncher.launch(intent) }.onFailure {
                                pendingLegacy.compareAndSet(continuation, null)
                                continuation.resume(false)
                            }
                        }
                    }
                }
                // §8.6: the legacy key is time-bound, so initialize immediately after RESULT_OK.
                return if (confirmed == true) initialize() else null
            }
        }
    }
}
