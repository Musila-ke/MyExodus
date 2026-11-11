package com.example.myexodus.applock

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.myexodus.R
import kotlinx.coroutines.launch

/**
 * §8.2: the only parent capable of composing protected navigation, sheets, dialogs and screen
 * content. While the state is anything but DISABLED/UNLOCKED, none of that tree exists.
 */
@Composable
fun AppLockRootGate(
    state: AppLockState,
    controller: AppLockController,
    content: @Composable () -> Unit,
) {
    if (state.showsProtectedUi) content() else AppLockShell(state, controller)
}

/** §5 user-visible behavior 4: the locked screen shows a reason and its one relevant action. */
@Composable
private fun AppLockShell(state: AppLockState, controller: AppLockController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val authorizer = rememberVaultCipherAuthorizer(controller)
    var failure by remember(state) { mutableStateOf<AppLockFailure?>(null) }
    var busy by remember { mutableStateOf(false) }

    val title = when (state) {
        AppLockState.RECOVERY_REQUIRED -> R.string.app_lock_recovery_title
        AppLockState.SETUP_REQUIRED -> R.string.app_lock_setup_title
        else -> R.string.app_lock_locked_title
    }
    val body = when (state) {
        AppLockState.RECOVERY_REQUIRED -> R.string.app_lock_recovery_body
        AppLockState.SETUP_REQUIRED -> R.string.app_lock_setup_body
        AppLockState.ENROLLING, AppLockState.DISABLING, AppLockState.LOCKING,
        AppLockState.OPENING, AppLockState.PREFLIGHT, AppLockState.AUTHENTICATING ->
            R.string.app_lock_transition_body
        else -> R.string.app_lock_locked_body
    }
    val inProgress = state == AppLockState.ENROLLING || state == AppLockState.DISABLING ||
        state == AppLockState.LOCKING || state == AppLockState.OPENING ||
        state == AppLockState.PREFLIGHT || state == AppLockState.AUTHENTICATING
    val progressLabel = stringResource(R.string.app_lock_progress_description)

    fun runOperation(operation: suspend () -> AppLockResult) {
        if (busy) return
        busy = true
        failure = null
        scope.launch {
            val result = operation()
            if (result is AppLockResult.Failed) failure = result.failure
            busy = false
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = stringResource(title),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                if (state == AppLockState.SETUP_REQUIRED) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.app_lock_setup_disclosure),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                failure?.let { failure ->
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = appLockFailureText(failure),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    if (failure == AppLockFailure.NoScreenLock) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                // §5.2: the OS owns lock creation; re-check on the next tap.
                                context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                            },
                        ) { Text(stringResource(R.string.app_lock_setup_device_lock)) }
                    }
                }
                if (!inProgress) {
                    Spacer(Modifier.height(24.dp))
                    when (state) {
                        AppLockState.LOCKED -> Button(
                            enabled = !busy,
                            onClick = { runOperation { controller.unlock(authorizer) } },
                        ) { Text(stringResource(R.string.app_lock_unlock)) }
                        AppLockState.SETUP_REQUIRED -> {
                            Button(
                                enabled = !busy,
                                onClick = { runOperation { controller.enroll(authorizer) } },
                            ) { Text(stringResource(R.string.app_lock_setup_enable)) }
                            TextButton(
                                enabled = !busy,
                                onClick = { controller.cancelSetup() },
                            ) { Text(stringResource(R.string.app_lock_setup_not_now)) }
                        }
                        AppLockState.RECOVERY_REQUIRED -> Button(
                            onClick = { controller.retryRecovery() },
                        ) { Text(stringResource(R.string.app_lock_recovery_retry)) }
                        else -> Unit
                    }
                }
                if (inProgress || busy) {
                    Spacer(Modifier.height(24.dp))
                    CircularProgressIndicator(
                        Modifier.semantics { contentDescription = progressLabel },
                    )
                }
            }
        }
    }
}

/** §9 failure categories to user copy; shared by the gate and the Settings pane. */
@Composable
internal fun appLockFailureText(failure: AppLockFailure): String = stringResource(
    when (failure) {
        AppLockFailure.NoScreenLock -> R.string.app_lock_failure_no_screen_lock
        AppLockFailure.NoSupportedAuthenticator -> R.string.app_lock_failure_no_authenticator
        AppLockFailure.VaultBusy -> R.string.app_lock_failure_busy
        AppLockFailure.Unavailable -> R.string.app_lock_failure_unavailable
    },
)
