package com.example.myexodus.ui.recovery

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.example.myexodus.R
import com.example.myexodus.recovery.CrisisChannels
import com.example.myexodus.recovery.CrisisResource
import com.example.myexodus.recovery.CrisisResources
import com.example.myexodus.ui.SecureSurface
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * "Get help now" (V1 Req 8/16 surface; directory audit plan G, verified 2026-11-07): the
 * verified contacts from `assets/crisis_resources.json`, grouped into behavior sections with
 * the device region first. Deliberately not privacy-gated and never behind a timer — this is
 * the one screen that must open instantly and read calmly.
 *
 * Field Ledger grammar: section eyebrows over hairline rules, no icon discs, no card boxes per
 * entry. A field the source did not state is omitted rather than filled with "Not stated": the
 * directory carries only what was checked, and the verification date says when.
 *
 * [initialFilter] lets Urge Assist preselect the current behavior (plan E2.10); it defaults to
 * All so every entry is reachable from the hub.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CrisisScreen(
    modifier: Modifier = Modifier,
    initialFilter: String = CrisisResources.FILTER_ALL,
) {
    // W6 exempts this screen from the PrivacyGate, not from screenshot protection: a crisis
    // must open instantly, but it still shows verified personal directory data and a panic tap.
    SecureSurface()
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val allResources = remember {
        val json = runCatching {
            context.assets.open(ASSET).bufferedReader().use { it.readText() }
        }.getOrDefault("")
        CrisisResources.forRegion(
            CrisisResources.parse(json),
            Locale.getDefault().country,
        )
    }
    var filter by rememberSaveable(initialFilter) { mutableStateOf(initialFilter) }
    val visible = allResources.filter { CrisisResources.matches(it, filter) }
    val sections = CrisisResources.SECTION_ORDER.mapNotNull { section ->
        visible.filter { CrisisResources.sectionOf(it) == section }
            .takeIf { it.isNotEmpty() }
            ?.let { section to it }
    }
    val online = remember {
        // ACCESS_NETWORK_STATE is contributed by a library, not declared here; never let the
        // probe take down the one screen that has to open instantly. Unknown reads as online.
        runCatching {
            val manager = context.getSystemService(ConnectivityManager::class.java)
                ?: return@runCatching true
            manager.getNetworkCapabilities(manager.activeNetwork)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: true
        }.getOrDefault(true)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.recovery_crisis_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.recovery_crisis_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            CrisisResources.FILTERS.forEach { option ->
                FilterChip(
                    selected = filter == option,
                    onClick = { filter = option },
                    label = { Text(filterLabel(option)) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }

        if (sections.isEmpty()) {
            Text(
                stringResource(R.string.recovery_crisis_no_results),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        sections.forEach { (section, entries) ->
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    sectionLabel(section).uppercase(locale),
                    style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.6.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
                entries.forEachIndexed { index, resource ->
                    CrisisEntry(resource)
                    if (index < entries.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                }
            }
        }

        if (!online) {
            Text(
                stringResource(R.string.recovery_crisis_offline),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            stringResource(R.string.recovery_crisis_not_emergency),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CrisisEntry(resource: CrisisResource) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                resource.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                regionLabel(resource.region),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (resource.description.isNotBlank()) {
            Text(
                resource.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (resource.phone.isNotBlank()) {
                ChannelButton(stringResource(R.string.recovery_crisis_call, resource.phone)) {
                    openUri(context, Intent.ACTION_DIAL, CrisisChannels.telUri(resource.phone))
                }
            }
            if (resource.sms.isNotBlank()) {
                ChannelButton(stringResource(R.string.recovery_crisis_sms, resource.sms)) {
                    openUri(context, Intent.ACTION_SENDTO, CrisisChannels.smsUri(resource.sms))
                }
            }
            if (resource.whatsapp.isNotBlank()) {
                ChannelButton(stringResource(R.string.recovery_crisis_whatsapp, resource.whatsapp)) {
                    openUri(context, Intent.ACTION_VIEW, CrisisChannels.whatsappUri(resource.whatsapp))
                }
            }
            if (resource.url.isNotBlank()) {
                ChannelButton(stringResource(R.string.recovery_crisis_open)) {
                    openUri(context, Intent.ACTION_VIEW, resource.url)
                }
            }
        }
        MetaLine(R.string.recovery_crisis_hours, resource.hours)
        MetaLine(R.string.recovery_crisis_cost, resource.cost)
        MetaLine(R.string.recovery_crisis_eligibility, resource.eligibility)
        MetaLine(R.string.recovery_crisis_limitations, resource.limitations)
        if (resource.lastVerifiedAt.isNotBlank()) {
            Text(
                stringResource(R.string.recovery_crisis_verified, verifiedLabel(resource.lastVerifiedAt)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChannelButton(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        // 48dp minimum touch target; padding is tight so four channels can share a row.
        modifier = Modifier.heightIn(min = 48.dp),
        contentPadding = PaddingValues(horizontal = 8.dp),
    ) {
        Text(label)
    }
}

@Composable
private fun MetaLine(labelRes: Int, value: String) {
    if (value.isBlank()) return
    Text(
        stringResource(labelRes, value),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Opens a verified channel. Wrapped: a device with no dialer or browser (tablet class) must
 * leave this screen usable, never crash the one surface that must open instantly. Nothing is
 * dialed or sent without the user completing the action in the target app.
 */
internal fun openUri(context: Context, action: String, uri: String?) {
    uri ?: return
    runCatching { context.startActivity(Intent(action, uri.toUri())) }
}

@Composable
private fun filterLabel(filter: String): String = stringResource(
    when (filter) {
        CrisisResources.FILTER_URGENT -> R.string.recovery_crisis_filter_urgent
        CrisisResources.FILTER_GAMBLING -> R.string.recovery_crisis_filter_gambling
        CrisisResources.FILTER_PORN -> R.string.recovery_crisis_filter_porn
        CrisisResources.FILTER_GAMING -> R.string.recovery_crisis_filter_gaming
        CrisisResources.FILTER_MENTAL_HEALTH -> R.string.recovery_crisis_filter_mental_health
        else -> R.string.recovery_crisis_filter_all
    },
)

@Composable
private fun sectionLabel(section: String): String = stringResource(
    when (section) {
        CrisisResources.SECTION_URGENT -> R.string.recovery_crisis_section_urgent
        CrisisResources.SECTION_GAMBLING -> R.string.recovery_crisis_section_gambling
        CrisisResources.SECTION_PORN -> R.string.recovery_crisis_section_porn
        CrisisResources.SECTION_GAMING -> R.string.recovery_crisis_section_gaming
        CrisisResources.SECTION_SUBSTANCE -> R.string.recovery_crisis_section_substance
        CrisisResources.SECTION_PROFESSIONAL -> R.string.recovery_crisis_section_professional
        CrisisResources.SECTION_MORE -> R.string.recovery_crisis_section_more
        else -> R.string.recovery_crisis_section_mental_health
    },
)

@Composable
private fun regionLabel(region: String): String = when {
    region.equals("KE", ignoreCase = true) -> stringResource(R.string.recovery_crisis_region_kenya)
    region.equals("US", ignoreCase = true) -> stringResource(R.string.recovery_crisis_region_us)
    region == CrisisResources.GLOBAL_REGION -> stringResource(R.string.recovery_crisis_region_international)
    else -> region
}

/** "2026-11-07" → "Nov 2026"; unparseable values show as-is rather than blank. */
private fun verifiedLabel(isoDate: String): String = runCatching {
    LocalDate.parse(isoDate).format(DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault()))
}.getOrDefault(isoDate)

internal const val ASSET = "crisis_resources.json"
