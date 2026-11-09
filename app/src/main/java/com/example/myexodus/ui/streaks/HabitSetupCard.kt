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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.myexodus.R
import com.example.myexodus.streaks.WithdrawalSeverity
import com.example.myexodus.ui.partner.ReachOutTarget
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
    submitting: Boolean,
    onOnboard: (
        label: String,
        durationYears: Int,
        severity: WithdrawalSeverity,
        recoveryStartAt: Long,
        partnerPairId: String?,
    ) -> Unit,
    onCancel: (() -> Unit)? = null,
    /** Drives the title only: the Home FAB opens this form with onCancel set even when no
     *  habit exists yet, so "Add another habit" must not be keyed on the Cancel button. */
    firstHabit: Boolean = true,
    /** Owner ruling 2026-11-09: picking a partner here IS the share — it lands in What you
     *  share automatically, and Disconnect there revokes it. Empty roster hides the choice. */
    partners: List<ReachOutTarget> = emptyList(),
) {
    var label by rememberSaveable { mutableStateOf("") }
    var durationIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var severity by rememberSaveable { mutableStateOf<WithdrawalSeverity?>(null) }
    var startAt by rememberSaveable { mutableLongStateOf(System.currentTimeMillis()) }
    var pickingDate by rememberSaveable { mutableStateOf(false) }
    var partnerPairId by rememberSaveable { mutableStateOf<String?>(null) }

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
                        stringResource(
                            if (firstHabit) R.string.streaks_setup_title
                            else R.string.streaks_setup_title_add,
                        ),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(R.string.streaks_setup_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (onCancel != null) {
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.streaks_cancel)) }
                }
            }

            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text(stringResource(R.string.streaks_setup_label)) },
                placeholder = { Text(stringResource(R.string.streaks_setup_label_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            SetupSection(stringResource(R.string.streaks_setup_duration)) {
                DURATION_OPTIONS.forEachIndexed { index, (optionLabel, _) ->
                    FilterChip(
                        selected = durationIndex == index,
                        onClick = { durationIndex = index },
                        label = { Text(stringResource(optionLabel)) },
                    )
                }
            }

            SetupSection(stringResource(R.string.streaks_setup_severity)) {
                SEVERITY_OPTIONS.forEach { (optionLabel, optionSeverity) ->
                    FilterChip(
                        selected = severity == optionSeverity,
                        onClick = { severity = optionSeverity },
                        label = { Text(stringResource(optionLabel)) },
                    )
                }
            }

            if (partners.isNotEmpty()) {
                SetupSection(stringResource(R.string.streaks_setup_share_with)) {
                    FilterChip(
                        selected = partnerPairId == null,
                        onClick = { partnerPairId = null },
                        label = { Text(stringResource(R.string.streaks_setup_share_none)) },
                    )
                    partners.forEach { partner ->
                        FilterChip(
                            selected = partnerPairId == partner.pairId,
                            onClick = { partnerPairId = partner.pairId },
                            label = { Text(partner.name) },
                        )
                    }
                }
                Text(
                    stringResource(R.string.streaks_setup_share_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        stringResource(R.string.streaks_setup_counting_from),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        DATE_FORMAT.format(Instant.ofEpochMilli(startAt).atZone(ZoneId.systemDefault())),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { pickingDate = true }) {
                    Text(stringResource(R.string.streaks_setup_change))
                }
            }
            Text(
                stringResource(R.string.streaks_setup_date_note),
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
                    onOnboard(
                        label.trim(),
                        DURATION_OPTIONS[durationIndex!!].second,
                        severity!!,
                        startAt,
                        partnerPairId,
                    )
                },
                enabled = !submitting && label.isNotBlank() && durationIndex != null && severity != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.streaks_setup_start))
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
            ) { Text(stringResource(R.string.streaks_picker_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.streaks_cancel)) }
        },
    ) { DatePicker(state = state) }
}

/** Upper bound of each range; 10 is the formula's cap, so "over 10" and "5–10" coincide.
 *  Labels are string ids, not literals — the value the chip maps to is the pair's second half. */
private val DURATION_OPTIONS = listOf(
    R.string.streaks_setup_duration_under_year to 0,
    R.string.streaks_setup_duration_1_2 to 2,
    R.string.streaks_setup_duration_2_5 to 5,
    R.string.streaks_setup_duration_5_10 to 10,
    R.string.streaks_setup_duration_over_10 to 10,
)

private val SEVERITY_OPTIONS = listOf(
    R.string.streaks_setup_severity_mild to WithdrawalSeverity.MILD,
    R.string.streaks_setup_severity_moderate to WithdrawalSeverity.MODERATE,
    R.string.streaks_setup_severity_severe to WithdrawalSeverity.SEVERE,
)

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy")

/** The picker works in UTC whole days (same convention as the rules editor). */
private fun Long.toPickerDayMillis(): Long =
    Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()
        .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.toLocalStartOfDayMillis(): Long =
    Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
        .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
