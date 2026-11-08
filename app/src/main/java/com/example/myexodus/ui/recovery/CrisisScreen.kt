package com.example.myexodus.ui.recovery

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Casino
import androidx.compose.material.icons.outlined.Emergency
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.SupportAgent
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
 * Composition (owner sign-off 2026-11-08): the single most useful call sits in the filled
 * "right now" card at the top, so the screen answers before it lists. The directory below is
 * scannable: one action per entry, everything else behind a Details disclosure, two entries
 * per section until asked for more.
 *
 * Field Ledger grammar: section eyebrows over hairline rules, no icon discs, no card boxes per
 * entry. A field the source did not state is omitted rather than filled with "Not stated": the
 * directory carries only what was checked, and the verification date says when.
 *
 * [initialFilter] lets Urge Assist preselect the current behavior (plan E2.10); it defaults to
 * All so every entry is reachable from the hub.
 */
@Composable
fun CrisisScreen(
    prefs: RecoveryPrefs,
    modifier: Modifier = Modifier,
    initialFilter: String = CrisisResources.FILTER_ALL,
) {
    // No gate (owner ruling 2026-11-08), but it still shows verified personal directory data
    // and a panic tap, so screenshot protection stays.
    SecureSurface()
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val parsed = remember {
        val json = runCatching {
            context.assets.open(ASSET).bufferedReader().use { it.readText() }
        }.getOrDefault("")
        CrisisResources.parse(json)
    }
    // Kenya-first default (owner ruling 2026-11-08), not the device locale: a phone bought or
    // configured abroad would otherwise open on another country's lines. The region control is
    // the override; telephony country would need a location or phone-state permission we do not hold.
    var region by rememberSaveable {
        mutableStateOf(prefs.crisisRegion ?: CrisisResources.DEFAULT_REGION)
    }
    var regionDialogOpen by remember { mutableStateOf(false) }
    val allResources = remember(parsed, region) { CrisisResources.forRegion(parsed, region) }
    val hero = remember(allResources, region) { heroEntry(allResources, region) }
    val emergency = remember(allResources) { emergencyEntry(allResources) }
    var filter by rememberSaveable(initialFilter) { mutableStateOf(initialFilter) }
    // Sections the user asked to see in full; entries whose Details are open. Both are plain
    // string lists so they survive process death without a custom Saver.
    var expandedSections by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var expandedEntries by rememberSaveable { mutableStateOf(emptyList<String>()) }
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
        HeroCard(hero = hero, emergency = emergency)

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // One scrolling row rather than wrapped rows: the directory below owns the page, and
            // the chips are a refinement of it, not a second navigation layer.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CrisisResources.FILTERS.forEach { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = { filter = option },
                        label = { Text(filterLabel(option)) },
                        leadingIcon = { ChipIcon(filterIcon(option)) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
            // Which country's verified lines are listed, and a one-tap way to change it.
            TextButton(
                onClick = { regionDialogOpen = true },
                modifier = Modifier.heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Public,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.recovery_crisis_region_row, regionLabel(region)),
                    style = MaterialTheme.typography.bodyMedium,
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
            // A chosen filter already narrows the list to the entries worth showing in full.
            val expanded = filter != CrisisResources.FILTER_ALL || section in expandedSections
            val shown = if (expanded) entries else entries.take(COLLAPSED_PREVIEW)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    sectionLabel(section).uppercase(locale),
                    style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.6.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
                shown.forEachIndexed { index, resource ->
                    CrisisEntryRow(
                        resource = resource,
                        region = region,
                        detailsOpen = resource.id in expandedEntries,
                        onToggleDetails = {
                            expandedEntries = if (resource.id in expandedEntries) {
                                expandedEntries - resource.id
                            } else {
                                expandedEntries + resource.id
                            }
                        },
                    )
                    if (index < shown.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                }
                val hidden = entries.size - shown.size
                if (hidden > 0) {
                    TextButton(
                        onClick = { expandedSections = expandedSections + section },
                        modifier = Modifier.heightIn(min = 48.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text(
                            pluralStringResource(R.plurals.recovery_crisis_show_more, hidden, hidden),
                            style = MaterialTheme.typography.bodyMedium,
                        )
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

    if (regionDialogOpen) {
        AlertDialog(
            onDismissRequest = { regionDialogOpen = false },
            title = { Text(stringResource(R.string.recovery_crisis_region_title)) },
            text = {
                Column {
                    (CrisisResources.regions(parsed) + CrisisResources.REGION_ALL).forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = option == region,
                                    role = Role.RadioButton,
                                    onClick = {
                                        region = option
                                        prefs.crisisRegion = option
                                        regionDialogOpen = false
                                    },
                                )
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = option == region, onClick = null)
                            Text(
                                regionLabel(option),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { regionDialogOpen = false }) {
                    Text(stringResource(R.string.db_health_cancel))
                }
            },
        )
    }
}

/** Entries per section before "Show N more"; the directory answers first, lists second. */
private const val COLLAPSED_PREVIEW = 2

/**
 * The screen's one loud surface, porting the hub's "right now" card: the first verified
 * regional helpline with a number, and the emergency number where the directory has one.
 * Both come from the asset, so nothing is hard-coded to a country.
 */
@Composable
private fun HeroCard(hero: CrisisResource?, emergency: CrisisResource?) {
    val context = LocalContext.current
    val ink = MaterialTheme.colorScheme.onPrimary
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.recovery_now_eyebrow).uppercase(),
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.6.sp),
            color = ink.copy(alpha = 0.72f),
        )
        Text(
            stringResource(R.string.recovery_crisis_hero_title),
            style = MaterialTheme.typography.headlineLarge,
            color = ink,
        )
        if (hero != null) {
            Text(hero.name, style = MaterialTheme.typography.titleSmall, color = ink)
            if (hero.description.isNotBlank()) {
                Text(
                    hero.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ink.copy(alpha = 0.85f),
                )
            }
            val primary = channelsOf(hero).firstOrNull()
            if (primary != null) {
                Button(
                    onClick = { openUri(context, primary.action, primary.uri) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onPrimary,
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                    contentPadding = PaddingValues(horizontal = 20.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(channelLabel(primary), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.weight(1f))
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = ink.copy(alpha = 0.2f))
        if (emergency != null && emergency.phone.isNotBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button) {
                        openUri(context, Intent.ACTION_DIAL, CrisisChannels.telUri(emergency.phone))
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Emergency,
                    contentDescription = null,
                    tint = ink.copy(alpha = 0.85f),
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    stringResource(R.string.recovery_crisis_emergency_line, emergency.phone),
                    style = MaterialTheme.typography.titleSmall,
                    color = ink,
                )
            }
        } else {
            Text(
                stringResource(R.string.recovery_crisis_emergency_generic),
                style = MaterialTheme.typography.bodySmall,
                color = ink.copy(alpha = 0.9f),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CrisisEntryRow(
    resource: CrisisResource,
    region: String,
    detailsOpen: Boolean,
    onToggleDetails: () -> Unit,
) {
    val context = LocalContext.current
    val channels = channelsOf(resource)
    val primary = channels.firstOrNull()
    val secondary = channels.drop(1)
    val hasDetails = secondary.isNotEmpty() ||
        resource.hours.isNotBlank() ||
        resource.cost.isNotBlank() ||
        resource.eligibility.isNotBlank() ||
        resource.limitations.isNotBlank() ||
        resource.lastVerifiedAt.isNotBlank()
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
            if (!resource.region.equals(region, ignoreCase = true)) {
                Text(
                    regionLabel(resource.region),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            primary?.let { channel ->
                ChannelButton(channelLabel(channel)) { openUri(context, channel.action, channel.uri) }
            }
            if (hasDetails) {
                val stateText = stringResource(
                    if (detailsOpen) R.string.recovery_assist_expanded else R.string.recovery_assist_collapsed,
                )
                TextButton(
                    onClick = onToggleDetails,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics { stateDescription = stateText },
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Text(stringResource(R.string.recovery_crisis_details))
                    Icon(
                        imageVector = if (detailsOpen) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        if (detailsOpen) {
            if (secondary.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    secondary.forEach { channel ->
                        ChannelButton(channelLabel(channel)) { openUri(context, channel.action, channel.uri) }
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
}

/**
 * One verified entry point in the order a person would try them. The first channel is the
 * row's single visible action; everything else waits behind Details.
 */
private data class CrisisChannel(
    val labelRes: Int,
    val value: String,
    val action: String,
    val uri: String?,
)

private fun channelsOf(resource: CrisisResource): List<CrisisChannel> = buildList {
    if (resource.phone.isNotBlank()) {
        add(CrisisChannel(R.string.recovery_crisis_call, resource.phone, Intent.ACTION_DIAL, CrisisChannels.telUri(resource.phone)))
    }
    if (resource.sms.isNotBlank()) {
        add(CrisisChannel(R.string.recovery_crisis_sms, resource.sms, Intent.ACTION_SENDTO, CrisisChannels.smsUri(resource.sms)))
    }
    if (resource.whatsapp.isNotBlank()) {
        add(CrisisChannel(R.string.recovery_crisis_whatsapp, resource.whatsapp, Intent.ACTION_VIEW, CrisisChannels.whatsappUri(resource.whatsapp)))
    }
    if (resource.url.isNotBlank()) {
        add(CrisisChannel(R.string.recovery_crisis_open, "", Intent.ACTION_VIEW, resource.url))
    }
}

/**
 * The hero is the first verified regional helpline with a number; where a region has none,
 * the global directory takes its place so "Talk to someone now" is never a dead button.
 */
internal fun heroEntry(resources: List<CrisisResource>, region: String): CrisisResource? =
    resources.firstOrNull { it.region.equals(region, ignoreCase = true) && it.kind == CrisisResources.KIND_HELPLINE && it.phone.isNotBlank() }
        ?: resources.firstOrNull { it.kind == CrisisResources.KIND_DIRECTORY && it.url.isNotBlank() }
        ?: resources.firstOrNull { it.kind == CrisisResources.KIND_HELPLINE && it.url.isNotBlank() }
        ?: resources.firstOrNull { it.phone.isNotBlank() }
        ?: resources.firstOrNull { it.url.isNotBlank() }

internal fun emergencyEntry(resources: List<CrisisResource>): CrisisResource? =
    resources.firstOrNull { it.kind == CrisisResources.KIND_EMERGENCY && it.phone.isNotBlank() }

@Composable
private fun channelLabel(channel: CrisisChannel): String =
    if (channel.value.isBlank()) {
        stringResource(channel.labelRes)
    } else {
        stringResource(channel.labelRes, channel.value)
    }

@Composable
private fun ChipIcon(icon: ImageVector) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.size(FilterChipDefaults.IconSize),
    )
}

private fun filterIcon(filter: String): ImageVector = when (filter) {
    CrisisResources.FILTER_URGENT -> Icons.Outlined.Emergency
    CrisisResources.FILTER_GAMBLING -> Icons.Outlined.Casino
    CrisisResources.FILTER_PORN -> Icons.Outlined.VisibilityOff
    CrisisResources.FILTER_GAMING -> Icons.Outlined.SportsEsports
    CrisisResources.FILTER_MENTAL_HEALTH -> Icons.Outlined.SupportAgent
    else -> Icons.Outlined.Apps
}

@Composable
private fun ChannelButton(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        // 48dp minimum touch target; padding is tight so the primary action and Details share
        // one row without either shrinking below the accessible size.
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
    region == CrisisResources.REGION_ALL -> stringResource(R.string.recovery_crisis_region_all)
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
