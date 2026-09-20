package com.example.myexodus.ui

import android.text.format.DateFormat
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.myexodus.R
import java.time.DayOfWeek
import java.time.format.TextStyle

/**
 * The schedule controls both editors use — the rules editor (rule windows) and the recovery
 * plan's risk-window editor (Req 19). Same two shapes on both sides: minutes-of-day, and a
 * Monday-first day string ("1111111" = every day, index 0 = Monday; [daysMask] holds the same
 * set as bit0..bit6).
 */
@Composable
internal fun TimeField(
    label: String,
    minutes: Long,
    onChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var picking by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { picking = true }, modifier = modifier) {
        Column {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(formatTime(minutes), style = MaterialTheme.typography.bodyLarge)
        }
    }
    if (picking) {
        TimePickerDialog(
            initialMinutes = minutes,
            onConfirm = {
                onChange(it)
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DayChips(
    days: String,
    onChange: (String) -> Unit,
    @StringRes labelRes: Int = R.string.editor_days,
) {
    val presets = listOf(
        R.string.editor_days_every to "1111111",
        R.string.editor_days_weekdays to "1111100",
        R.string.editor_days_weekends to "0000011",
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(labelRes), style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { (labelRes, value) ->
                FilterChip(
                    selected = days == value,
                    onClick = { onChange(value) },
                    label = { Text(stringResource(labelRes)) },
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (0..6).forEach { index ->
                val selected = days.getOrNull(index) == '1'
                FilterChip(
                    selected = selected,
                    // [toggleDay] itself refuses to clear the last remaining day.
                    onClick = { onChange(toggleDay(days, index)) },
                    label = {
                        Text(
                            DayOfWeek.of(index + 1)
                                .getDisplayName(TextStyle.SHORT, LocalConfiguration.current.locales[0]),
                        )
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(
    initialMinutes: Long,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberTimePickerState(
        initialHour = (initialMinutes / 60).toInt(),
        initialMinute = (initialMinutes % 60).toInt(),
        is24Hour = DateFormat.is24HourFormat(LocalContext.current),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour * 60L + state.minute) }) {
                Text(stringResource(R.string.dialog_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.rules_cancel)) }
        },
        text = { TimePicker(state = state) },
    )
}
