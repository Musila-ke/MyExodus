package com.example.myexodus.ui.recovery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.myexodus.R
import com.example.myexodus.recovery.UrgeCatalog
import com.example.myexodus.ui.BackRow
import com.example.myexodus.ui.SecureSurface
import com.example.myexodus.ui.WindowWidth
import com.example.myexodus.ui.rememberWindowWidth

/**
 * "What works for you" (plan §E2.7, step 8): the three optional local preferences the selection
 * engine reads. Nothing here is a rule and nothing is mirrored to a partner; only ordering and
 * hiding change. Direct access like the rest of the tab.
 */
@Composable
fun RecoveryPreferencesScreen(
    prefs: RecoveryPrefs,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // A read-time privacy surface like the rest of the tab (INV-11); screenshot-protected,
    // direct access (owner ruling 2026-11-08).
    SecureSurface()
    PreferencesBody(prefs = prefs, onBack = onBack, modifier = modifier)
}

@Composable
private fun PreferencesBody(
    prefs: RecoveryPrefs,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sidePadding = if (rememberWindowWidth() == WindowWidth.COMPACT) 16.dp else 24.dp
    // Local mirrors: SharedPreferences has no observable flow, and the rest of the tab reads it
    // the same way. Writes go through RecoveryPrefs so the engine sees them immediately.
    var canMove by remember { mutableStateOf(prefs.canMove) }
    var quiet by remember { mutableStateOf(prefs.quietPreferred) }
    var disliked by remember { mutableStateOf(prefs.dislikedActions) }

    Column(modifier = modifier.fillMaxSize()) {
        BackRow(label = stringResource(R.string.recovery_prefs_title), onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = sidePadding)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.recovery_prefs_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionEyebrow(stringResource(R.string.recovery_prefs_move_label))
            Hairline()
            Text(
                stringResource(R.string.recovery_prefs_move_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MoveChip(R.string.recovery_prefs_yes, selected = canMove == true) {
                    canMove = true
                    prefs.canMove = true
                }
                MoveChip(R.string.recovery_prefs_no, selected = canMove == false) {
                    canMove = false
                    prefs.canMove = false
                }
                // "Not sure" is the same state as unanswered: the engine never reads it as a no.
                MoveChip(R.string.recovery_prefs_unsure, selected = canMove == null) {
                    canMove = null
                    prefs.canMove = null
                }
            }

            Hairline()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .toggleable(
                        value = quiet,
                        role = Role.Switch,
                        onValueChange = {
                            quiet = it
                            prefs.quietPreferred = it
                        },
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.recovery_prefs_quiet_label),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        stringResource(R.string.recovery_prefs_quiet_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // The row owns the toggle semantics; the switch is the visual.
                Switch(checked = quiet, onCheckedChange = null)
            }

            Hairline()
            SectionEyebrow(stringResource(R.string.recovery_prefs_hidden_label))
            Hairline()
            if (disliked.isEmpty()) {
                Text(
                    stringResource(R.string.recovery_prefs_hidden_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // A set has no display order; sort the resolved labels so the list is stable.
                disliked
                    .map { id -> id to UrgeCatalog.byId(id)?.labelRes?.let { stringResource(it) } }
                    .sortedBy { (id, label) -> label ?: id }
                    .forEach { (id, label) ->
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                label ?: id,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = {
                                    prefs.clearDisliked(id)
                                    disliked = prefs.dislikedActions
                                },
                                modifier = Modifier.heightIn(min = 48.dp),
                                contentPadding = PaddingValues(horizontal = 0.dp),
                            ) {
                                Text(stringResource(R.string.recovery_show_again))
                            }
                        }
                    }
            }
        }
    }
}

@Composable
private fun MoveChip(labelRes: Int, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(stringResource(labelRes)) },
    )
}
