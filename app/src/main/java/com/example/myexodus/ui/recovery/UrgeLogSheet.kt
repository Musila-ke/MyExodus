package com.example.myexodus.ui.recovery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.myexodus.R

/**
 * The urge log (v3 W2, V1 Req 10): an optional 1–10 intensity, one tap to save, then the caller
 * routes the user home. The copy deliberately separates this from the override — logging keeps
 * the block, the override retires it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UrgeLogSheet(
    onDismiss: () -> Unit,
    onSubmit: (intensity: Int?) -> Unit,
) {
    var intensity by remember { mutableStateOf<Int?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.recovery_log_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                stringResource(R.string.recovery_log_intensity),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(10) { index ->
                    val value = index + 1
                    FilterChip(
                        selected = intensity == value,
                        // A second tap clears it — the scale is skippable at any point.
                        onClick = { intensity = if (intensity == value) null else value },
                        label = { Text("$value") },
                    )
                }
            }
            Text(
                stringResource(R.string.recovery_log_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.recovery_log_cancel))
                }
                Button(onClick = { onSubmit(intensity) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.recovery_log_submit))
                }
            }
        }
    }
}
