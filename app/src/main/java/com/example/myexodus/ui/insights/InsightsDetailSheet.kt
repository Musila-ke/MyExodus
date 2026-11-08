package com.example.myexodus.ui.insights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.myexodus.R
import com.example.myexodus.insights.InsightDay
import com.example.myexodus.insights.InsightPackage
import com.example.myexodus.insights.InsightTarget
import com.example.myexodus.ui.formatDuration
import com.example.myexodus.ui.rememberAppLabel

/**
 * The tap-through behind every Insights number (owner audit 2026-11-08: no displayed value may
 * leave the reader guessing what it counts). A chart column opens its day, a ranked row opens
 * its own days. Every list names the target or app through the same [rememberAppLabel] the
 * rows use, and an empty list says "None" rather than showing nothing.
 */
internal sealed interface InsightDetail {
    data class Day(val day: InsightDay) : InsightDetail
    data class Target(val target: InsightTarget, val days: List<InsightDay>) : InsightDetail
    data class Package(val pkg: InsightPackage, val days: List<InsightDay>) : InsightDetail
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InsightsDetailSheet(detail: InsightDetail, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                // A day can hit many distinct targets (a rule's site list is unbounded), so the
                // sheet scrolls inside its own window rather than clipping the tail.
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    when (detail) {
                        is InsightDetail.Day -> detailDayLabel(detail.day.date)
                        is InsightDetail.Target -> rememberAppLabel(detail.target.target)
                        is InsightDetail.Package -> rememberAppLabel(detail.pkg.packageName)
                    },
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    when (detail) {
                        is InsightDetail.Day -> stringResource(R.string.insights_detail_day_caption)
                        is InsightDetail.Target -> stringResource(R.string.insights_detail_target_caption)
                        is InsightDetail.Package -> stringResource(R.string.insights_detail_package_caption)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (detail) {
                is InsightDetail.Day -> DayDetailBody(detail.day)
                is InsightDetail.Target -> TargetDetailBody(detail.target, detail.days)
                is InsightDetail.Package -> PackageDetailBody(detail.pkg, detail.days)
            }
        }
    }
}

@Composable
private fun DayDetailBody(day: InsightDay) {
    LabeledSection(stringResource(R.string.insights_detail_head_blocked)) {
        if (day.blockedTargets.isEmpty()) {
            NoneRow()
        } else {
            for (target in day.blockedTargets) {
                ValueRow(rememberAppLabel(target.target), target.count.toString())
            }
        }
    }
    LabeledSection(stringResource(R.string.insights_detail_head_overrides)) {
        if (day.overrideTargets.isEmpty()) {
            NoneRow()
        } else {
            for (target in day.overrideTargets) {
                ValueRow(rememberAppLabel(target.target), target.count.toString())
            }
        }
    }
    LabeledSection(stringResource(R.string.insights_detail_head_apps)) {
        if (day.packages.isEmpty()) {
            NoneRow()
        } else {
            for (pkg in day.packages) {
                ValueRow(rememberAppLabel(pkg.packageName), formatDuration(pkg.creditedMs))
            }
        }
    }
}

@Composable
private fun TargetDetailBody(target: InsightTarget, days: List<InsightDay>) {
    val entries = days.mapNotNull { day ->
        day.blockedTargets.firstOrNull { it.target == target.target }
            ?.let { detailDayLabel(day.date) to it.count.toString() }
    }
    LabeledSection(stringResource(R.string.insights_detail_head_days)) {
        if (entries.isEmpty()) {
            NoneRow()
        } else {
            for ((label, value) in entries) ValueRow(label, value)
        }
    }
}

@Composable
private fun PackageDetailBody(pkg: InsightPackage, days: List<InsightDay>) {
    val entries = days.mapNotNull { day ->
        day.packages.firstOrNull { it.packageName == pkg.packageName }
            ?.let { detailDayLabel(day.date) to formatDuration(it.creditedMs) }
    }
    LabeledSection(stringResource(R.string.insights_detail_head_days)) {
        if (entries.isEmpty()) {
            NoneRow()
        } else {
            for ((label, value) in entries) ValueRow(label, value)
        }
    }
}

@Composable
private fun LabeledSection(heading: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            heading,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
    }
}

@Composable
private fun ValueRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NoneRow() {
    Text(
        stringResource(R.string.insights_detail_none),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
