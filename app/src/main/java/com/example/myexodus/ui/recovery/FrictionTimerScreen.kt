package com.example.myexodus.ui.recovery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.myexodus.R
import com.example.myexodus.blocking.recovery.RecoveryStateStore
import com.example.myexodus.ui.BackRow
import com.example.myexodus.ui.SecureSurface
import com.example.myexodus.ui.formatDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Friction timer editor (owner request 2026-11-01): the wait before a block lifts was fixed at
 * 60 s; [com.example.myexodus.data.db.entity.RecoveryPolicy] has always carried the number, this
 * is the surface that sets it. One global value, read when a session starts — a running
 * countdown keeps the length it started with.
 */
@Composable
fun FrictionTimerScreen(
    recoveryStore: RecoveryStateStore,
    prefs: RecoveryPrefs,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SecureSurface()
    GatedContent(prefs = prefs, titleRes = R.string.recovery_gate_title_friction) {
        FrictionTimerBody(recoveryStore = recoveryStore, onBack = onBack, modifier = modifier)
    }
}

@Composable
private fun FrictionTimerBody(
    recoveryStore: RecoveryStateStore,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf<Long?>(null) }
    var hoursText by rememberSaveable { mutableStateOf("") }
    var minutesText by rememberSaveable { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val seconds = withContext(Dispatchers.IO) { recoveryStore.frictionSeconds() }
        hoursText = (seconds / 3600).toString()
        minutesText = ((seconds % 3600) / 60).toString()
        current = seconds
    }

    // Blank reads as zero, so "1" alone in Minutes is one minute, not an error.
    val total = (hoursText.toIntOrNull() ?: 0) * 3600L + (minutesText.toIntOrNull() ?: 0) * 60L
    val loaded = current
    val valid = loaded != null &&
        total in RecoveryStateStore.MIN_FRICTION_SECONDS..RecoveryStateStore.MAX_FRICTION_SECONDS

    Column(modifier = modifier.fillMaxSize()) {
        BackRow(label = stringResource(R.string.recovery_timer_label), onBack = onBack)
        if (loaded == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    stringResource(R.string.friction_timer_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.friction_timer_current, formatDuration(loaded * 1000)),
                    style = MaterialTheme.typography.titleMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = hoursText,
                        onValueChange = { hoursText = it.filter(Char::isDigit).take(2); saved = false },
                        label = { Text(stringResource(R.string.friction_timer_hours)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = minutesText,
                        onValueChange = { minutesText = it.filter(Char::isDigit).take(2); saved = false },
                        label = { Text(stringResource(R.string.friction_timer_minutes)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                if (!valid) {
                    Text(
                        stringResource(R.string.friction_timer_invalid),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Button(
                    onClick = {
                        val seconds = total
                        scope.launch {
                            withContext(Dispatchers.IO) { recoveryStore.setFrictionSeconds(seconds) }
                            hoursText = (seconds / 3600).toString()
                            minutesText = ((seconds % 3600) / 60).toString()
                            current = seconds
                            saved = true
                        }
                    },
                    enabled = valid,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.friction_timer_save))
                }
                if (saved) {
                    Text(
                        stringResource(R.string.friction_timer_saved, formatDuration(total * 1000)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}
