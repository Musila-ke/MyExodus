package com.example.myexodus.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.myexodus.R
import com.example.myexodus.applock.AppLockController
import com.example.myexodus.applock.AppLockFailure
import com.example.myexodus.applock.AppLockPreflight
import com.example.myexodus.applock.AppLockResult
import com.example.myexodus.applock.appLockFailureText
import com.example.myexodus.applock.rememberVaultCipherAuthorizer
import com.example.myexodus.ui.BackRow
import kotlinx.coroutines.launch

/**
 * Plan §5 user-visible behavior 1-3 and §8.11: the Settings half of App Lock. Enable hands
 * over to the root gate's disclosure shell (the gate owns enrollment); disable stays on this
 * session through the OS prompt so the DEKs it needs are still held.
 */
@Composable
internal fun AppLockPane(
    enabled: Boolean,
    controller: AppLockController,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val authorizer = rememberVaultCipherAuthorizer(controller)
    var failure by remember { mutableStateOf<AppLockFailure?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmDisable by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize()) {
        BackRow(label = stringResource(R.string.settings_app_lock_title), onBack = onBack)
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        stringResource(
                            if (enabled) R.string.settings_app_lock_summary_on
                            else R.string.settings_app_lock_summary_off,
                        ),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        stringResource(
                            if (enabled) R.string.settings_app_lock_hero_on
                            else R.string.settings_app_lock_hero_off,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            failure?.let { failure ->
                Text(
                    text = appLockFailureText(failure),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                if (failure == AppLockFailure.NoScreenLock) {
                    OutlinedButton(
                        onClick = {
                            // §5 behavior 2: never fake the private PIN sub-screen.
                            context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                        },
                    ) { Text(stringResource(R.string.app_lock_setup_device_lock)) }
                }
            }
            if (enabled) {
                Button(
                    onClick = { controller.lockNow() },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text(stringResource(R.string.settings_app_lock_lock_now)) }
                OutlinedButton(
                    onClick = { confirmDisable = true },
                    enabled = !busy,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text(stringResource(R.string.settings_app_lock_turn_off)) }
                if (busy) CircularProgressIndicator()
            } else {
                Button(
                    enabled = !busy,
                    onClick = {
                        failure = when (controller.preflight()) {
                            AppLockPreflight.Ready ->
                                if (controller.beginSetup()) null else AppLockFailure.Unavailable
                            AppLockPreflight.NoScreenLock -> AppLockFailure.NoScreenLock
                            AppLockPreflight.NoSupportedAuthenticator ->
                                AppLockFailure.NoSupportedAuthenticator
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text(stringResource(R.string.settings_app_lock_enable)) }
            }
            Text(
                stringResource(R.string.settings_app_lock_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (confirmDisable) {
        AlertDialog(
            onDismissRequest = { confirmDisable = false },
            title = { Text(stringResource(R.string.settings_app_lock_turn_off_confirm_title)) },
            text = { Text(stringResource(R.string.settings_app_lock_turn_off_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDisable = false
                        busy = true
                        failure = null
                        scope.launch {
                            when (val result = controller.disable(authorizer)) {
                                is AppLockResult.Failed -> failure = result.failure
                                AppLockResult.RecoveryRequired -> failure = AppLockFailure.Unavailable
                                else -> Unit
                            }
                            busy = false
                        }
                    },
                ) { Text(stringResource(R.string.settings_app_lock_turn_off_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDisable = false }) {
                    Text(stringResource(R.string.app_lock_cancel))
                }
            },
        )
    }
}
