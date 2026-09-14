package com.example.myexodus.ui.streaks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.myexodus.streaks.WithdrawalSeverity
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * REQ-18 habit setup (V1's only onboarding): duration + severity + start date, matching the
 * repository's own input constraints (§8 Step 3) — the future-date rejection surfaces inline
 * as copy, never a dead-end. Duration is asked in ranges (the doc's own example is "Hooked
 * for 2–5 years") and mapped to the range's upper bound, which the formula caps at 10 years.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun HabitSetupCard(
    error: String?,
    onOnboard: (
        label: String,
        durationYears: Int,
        severity: WithdrawalSeverity,
        recoveryStartAt: Long,
    ) -> Unit,
    onCancel: (() -> Unit)? = null,
) {
    var label by rememberSaveable { mutableStateOf("") }
    var durationIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var severity by rememberSaveable { mutableStateOf<WithdrawalSeverity?>(null) }
    var startAt by rememberSaveable { mutableLongStateOf(System.currentTimeMillis()) }
    var pickingDate by rememberSaveable { mutableStateOf(false) }

    if (pickingDate) {
        StartDatePickerDialog(
            initialMillis = startAt,
            onDismiss = { pickingDate = false },
            onConfirm = {
                startAt = it
                pickingDate = false
            },
        )
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        if (onCancel != null) "Add another habit" else "Set up your habit",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "MyExodus counts your days free and builds your expectation timeline from " +
                            "a few answers. Everything stays on this device.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (onCancel != null) {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
            }

            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("What are you quitting?") },
                placeholder = { Text("Instagram, betting apps…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            SetupSection("How long has this habit been part of your life?") {
                DURATION_OPTIONS.forEachIndexed { index, (optionLabel, _) ->
                    FilterChip(
                        selected = durationIndex == index,
                        onClick = { durationIndex = index },
                        label = { Text(optionLabel) },
                    )
                }
            }

            SetupSection("How hard was it to go without it?") {
                SEVERITY_OPTIONS.forEach { (optionLabel, optionSeverity) ->
                    FilterChip(
                        selected = severity == optionSeverity,
                        onClick = { severity = optionSeverity },
                        label = { Text(optionLabel) },
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text("Counting from", style = MaterialTheme.typography.labelLarge)
                    Text(
                        DATE_FORMAT.format(Instant.ofEpochMilli(startAt).atZone(ZoneId.systemDefault())),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { pickingDate = true }) { Text("Change") }
            }
            Text(
                "The count starts when you set this up and runs forward from this moment. " +
                    "The date is a label for when you stopped.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            error?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Button(
                onClick = {
                    onOnboard(label.trim(), DURATION_OPTIONS[durationIndex!!].second, severity!!, startAt)
                },
                enabled = label.isNotBlank() && durationIndex != null && severity != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Start my streak")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SetupSection(title: String, chips: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { chips() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StartDatePickerDialog(
    initialMillis: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initialMillis.toPickerDayMillis())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = state.selectedDateMillis != null,
                onClick = { state.selectedDateMillis?.let { onConfirm(it.toLocalStartOfDayMillis()) } },
            ) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    ) { DatePicker(state = state) }
}

/** Upper bound of each range; 10 is the formula's cap, so "over 10" and "5–10" coincide. */
private val DURATION_OPTIONS = listOf(
    "Under a year" to 0,
    "1–2 years" to 2,
    "2–5 years" to 5,
    "5–10 years" to 10,
    "Over 10 years" to 10,
)

private val SEVERITY_OPTIONS = listOf(
    "Mild — occasionally" to WithdrawalSeverity.MILD,
    "Moderate — most days" to WithdrawalSeverity.MODERATE,
    "Severe — constantly, hard to control" to WithdrawalSeverity.SEVERE,
)

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy")

/** The picker works in UTC whole days (same convention as the rules editor). */
private fun Long.toPickerDayMillis(): Long =
    Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()
        .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.toLocalStartOfDayMillis(): Long =
    Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
        .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
