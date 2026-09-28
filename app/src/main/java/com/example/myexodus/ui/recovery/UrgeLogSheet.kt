package com.example.myexodus.ui.recovery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.myexodus.R

/**
 * The urge log (v3 W2, V1 Req 10): an optional 1–10 intensity, one tap to save, then the caller
 * routes the user home. The copy deliberately separates this from the override — logging keeps
 * the block, the override retires it.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun UrgeLogSheet(
    onDismiss: () -> Unit,
    onSubmit: (intensity: Int?) -> Unit,
) {
    var intensity by remember { mutableStateOf<Int?>(null) }
    // skipPartiallyExpanded: at the default half-height anchor the chips filled the sheet and
    // the buttons sat below the fold (emulator dump, 2026-09-27) — the content is ~315dp and
    // fits whole, so open it whole.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
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
            // All ten on screen at once, wrapping to a second row: as a scrolling row the top
            // of the scale sat off the edge with nothing to signal it (device dump, 2026-09-27).
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                (1..10).forEach { value ->
                    val name = stringResource(R.string.recovery_log_intensity_chip, value)
                    FilterChip(
                        selected = intensity == value,
                        // A second tap clears it — the scale is skippable at any point.
                        onClick = { intensity = if (intensity == value) null else value },
                        label = { Text(value.toString()) },
                        modifier = Modifier.semantics { contentDescription = name },
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
