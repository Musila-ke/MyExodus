package com.example.myexodus.ui.rules

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Casino
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Newspaper
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.myexodus.R
import com.example.myexodus.data.db.entity.RestrictionRule

/**
 * Owner request 2026-10-21: a category list the user reviews before anything is added, so
 * nobody has to open a triggering site to copy its address. Selection is per category and
 * starts "all of this list" — the list itself is the recommendation, and unchecking is the
 * review. Nothing is written until the Block button is tapped.
 */
/**
 * The category row shared by the empty website section and the new-rule editor (owner
 * correction 2026-10-21: categories belong where a website rule is created, not only in the
 * empty state). Each chip opens the reviewed list for that category.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CategoryPresetChips(
    onOpenPresets: (categoryId: String) -> Unit,
    enabled: Boolean = true,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        WebsitePresets.categories.forEach { category ->
            FilterChip(
                selected = false,
                onClick = { onOpenPresets(category.id) },
                enabled = enabled,
                label = { Text(stringResource(category.titleRes)) },
                leadingIcon = {
                    Icon(
                        categoryIcon(category.id),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
            )
        }
    }
}

private fun categoryIcon(categoryId: String): ImageVector = when (categoryId) {
    "social" -> Icons.Outlined.Groups
    "adult" -> Icons.Outlined.VisibilityOff
    "betting" -> Icons.Outlined.Casino
    "video" -> Icons.Outlined.SmartDisplay
    "news" -> Icons.Outlined.Newspaper
    else -> Icons.Outlined.ShoppingBag
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun WebsitePresetSheet(
    initialCategoryId: String,
    existingRules: List<RestrictionRule>,
    saving: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
    onAddOwnSite: () -> Unit,
) {
    var categoryId by rememberSaveable { mutableStateOf(initialCategoryId) }
    val category = WebsitePresets.category(categoryId) ?: WebsitePresets.categories.first()
    val available = unblockedSites(category, existingRules)
    // Keyed on the category: switching lists starts a fresh review of that list, and a
    // partially-failed insert leaves already-blocked hosts out (they arrive via existingRules).
    var selected by remember(category.id, available) {
        mutableStateOf(available.toSet())
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val blockLabel = pluralStringResource(
        R.plurals.rules_presets_block_count,
        selected.size,
        selected.size,
    )

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
                stringResource(R.string.rules_presets_sheet_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                stringResource(R.string.rules_presets_sheet_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                WebsitePresets.categories.forEach { option ->
                    FilterChip(
                        selected = option.id == category.id,
                        onClick = { categoryId = option.id },
                        enabled = !saving,
                        label = { Text(stringResource(option.titleRes)) },
                    )
                }
            }
            HorizontalDivider()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(
                        R.string.rules_presets_selected_count,
                        selected.size,
                        available.size,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        selected = if (selected.size == available.size) {
                            emptySet()
                        } else {
                            available.toSet()
                        }
                    },
                    enabled = !saving && available.isNotEmpty(),
                ) {
                    Text(
                        stringResource(
                            if (selected.size == available.size) R.string.rules_presets_clear
                            else R.string.rules_presets_select_all,
                        ),
                    )
                }
            }
            if (available.isEmpty()) {
                Text(
                    stringResource(R.string.rules_presets_all_blocked),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                available.forEach { host ->
                    val checked = host in selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .toggleable(
                                value = checked,
                                enabled = !saving,
                                role = Role.Checkbox,
                                onValueChange = { want ->
                                    selected = if (want) selected + host else selected - host
                                },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Text(
                            host,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            if (error != null) {
                Text(
                    error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(
                onClick = { onConfirm(selected.toList()) },
                enabled = selected.isNotEmpty() && !saving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (saving) {
                        stringResource(R.string.rules_presets_saving)
                    } else {
                        blockLabel
                    },
                )
            }
            TextButton(
                onClick = onAddOwnSite,
                enabled = !saving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.rules_presets_add_own))
            }
        }
    }
}
