package com.example.myexodus.applock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.myexodus.R

/**
 * §8.2: the only parent capable of composing protected navigation, sheets, dialogs and screen
 * content. While the state is anything but DISABLED/UNLOCKED, none of that tree exists.
 */
@Composable
fun AppLockRootGate(state: AppLockState, content: @Composable () -> Unit) {
    if (state.showsProtectedUi) content() else AppLockShell(state)
}

@Composable
private fun AppLockShell(state: AppLockState) {
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
                if (inProgress) {
                    Spacer(Modifier.height(24.dp))
                    CircularProgressIndicator(
                        Modifier.semantics { contentDescription = progressLabel },
                    )
                }
            }
        }
    }
}
