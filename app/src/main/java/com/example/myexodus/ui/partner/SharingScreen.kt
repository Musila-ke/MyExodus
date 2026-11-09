package com.example.myexodus.ui.partner

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.SelfImprovement
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.myexodus.R
import com.example.myexodus.streaks.HabitRepository
import com.example.myexodus.streaks.HabitSharingItem
import com.example.myexodus.streaks.ProfileSharingFlags
import com.example.myexodus.streaks.SharingMatrix
import com.example.myexodus.streaks.StreakSharingFlags
import com.example.myexodus.ui.BackRow
import com.example.myexodus.ui.settings.IndexRow
import com.example.myexodus.ui.settings.RowDivider
import com.example.myexodus.ui.settings.StatusTone
import com.example.myexodus.ui.settings.ToggleRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The "Other information" pseudo-item: profile-wide categories have no habit id, so the
 *  overview needs one stable id for them; habit ids are UUIDs and can never collide. */
private const val ITEM_OTHER = "other"

/**
 * The consent surface (v16/ADR #135, owner mandate 2026-10-04 — "one partner sees this streak,
 * another sees that streak"): every sharing decision is made per (item, partner). An item is a
 * streak or the profile-wide "Other information" bundle. Three levels: the overview lists the
 * items with a live "who sees this" summary, tapping one shows a row per partner with what that
 * partner sees, and tapping a partner opens the switches for that exact pair. With exactly one
 * partner there is no choice to make at the middle level, so it collapses — item straight to
 * switches (the same shape v15 had).
 *
 * Nothing is shared until a switch is turned on ("nothing until you choose", owner ruling
 * 2026-10-04), and every switch gates the journal's write path — off means the event is never
 * journalled, never signed, never delivered. Local Room only; reads and writes ride
 * [HabitRepository.sharingMatrix] / [setPairStreakSharing] / [setPairProfileSharing].
 */
@Composable
fun SharingScreen(
    habitRepository: HabitRepository,
    loadPartnerNames: suspend () -> Map<String, String>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    // null = still loading; null matrix after load = the store refused to open (an honest
    // error, never "nothing shared" — that is a real answer with every switch off).
    var matrix by remember { mutableStateOf<SharingMatrix?>(null) }
    var partnerNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var loaded by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    var openItemId by remember { mutableStateOf<String?>(null) }
    var openPairId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            matrix = runCatching { habitRepository.sharingMatrix() }.getOrNull()
            partnerNames = runCatching { loadPartnerNames() }.getOrDefault(emptyMap())
            loaded = true
        }
    }

    /** Optimistic toggle: the switch answers the finger; a failed write reverts it and the
     *  pane shows why. Writes are disabled while one is in flight, so a batch of fast taps
     *  can never interleave stale full-state writes. */
    fun persistStreak(item: HabitSharingItem, pairId: String, flags: StreakSharingFlags) {
        val before = matrix ?: return
        if (saving) return
        matrix = before.copy(habits = before.habits.map {
            if (it.progressId == item.progressId) it.copy(perPair = it.perPair + (pairId to flags)) else it
        })
        saving = true
        saveFailed = false
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    habitRepository.setPairStreakSharing(
                        pairId, item.progressId,
                        flags.shareStreak, flags.shareRelapses, flags.shareMilestones,
                    )
                }.getOrDefault(false)
            }
            if (!ok) {
                matrix = before
                saveFailed = true
            }
            saving = false
        }
    }

    fun persistProfile(pairId: String, flags: ProfileSharingFlags) {
        val before = matrix ?: return
        if (saving) return
        matrix = before.copy(profile = before.profile + (pairId to flags))
        saving = true
        saveFailed = false
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    habitRepository.setPairProfileSharing(
                        pairId, flags.shareCheckIns, flags.shareRecovery, flags.shareAvailability,
                    )
                }.getOrDefault(false)
            }
            if (!ok) {
                matrix = before
                saveFailed = true
            }
            saving = false
        }
    }

    // All levels derive from the loaded state, so toggles repaint from one source of truth; an
    // item that vanished mid-view falls back to the overview. These BackHandlers are composed
    // after SettingsScreen's pane handler, so LIFO gives them precedence — back walks
    // overview → partners → switches, then leaves the pane. With one partner the middle level
    // is skipped, so one back step returns straight to the overview.
    val partners = partnerNames.toList()
    val partnerLevelShown = partners.size > 1
    val openItem: OpenItem? = when (openItemId) {
        null -> null
        ITEM_OTHER -> OpenItem.Other
        else -> matrix?.habits?.firstOrNull { it.progressId == openItemId }?.let { OpenItem.Streak(it) }
    }
    val backOneLevel = {
        if (openPairId != null) {
            openPairId = null
            if (!partnerLevelShown) openItemId = null
        } else {
            openItemId = null
        }
    }
    BackHandler(enabled = openPairId != null) { backOneLevel() }
    BackHandler(enabled = openItem != null && openPairId == null) { backOneLevel() }

    Column(modifier = modifier.fillMaxSize()) {
        BackRow(
            label = when (val item = openItem) {
                is OpenItem.Streak -> item.item.label
                OpenItem.Other -> stringResource(R.string.settings_sharing_other_header)
                null -> stringResource(R.string.partner_tile_sharing)
            },
            onBack = { if (openItemId != null) backOneLevel() else onBack() },
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val current = matrix
            if (saveFailed) {
                Text(
                    stringResource(R.string.settings_sharing_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            when {
                !loaded -> Box(
                    Modifier.fillMaxWidth().padding(top = 48.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                current == null -> Text(
                    stringResource(R.string.settings_sharing_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )

                partners.isEmpty() -> Text(
                    stringResource(R.string.settings_sharing_no_partners),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                openItem != null && openPairId != null -> {
                    // Delegated state never smart-casts; the branch condition carries the guarantee.
                    val pairId = checkNotNull(openPairId)
                    ItemDetail(
                        item = openItem,
                        pairName = partners.first { it.first == pairId }.second,
                        saving = saving,
                        streakFlags = (openItem as? OpenItem.Streak)?.item?.perPair
                            ?.get(pairId) ?: StreakSharingFlags.None,
                        profileFlags = current.profile[pairId] ?: ProfileSharingFlags.None,
                        onStreakChange = { flags ->
                            (openItem as OpenItem.Streak).let { persistStreak(it.item, pairId, flags) }
                        },
                        onProfileChange = { flags -> persistProfile(pairId, flags) },
                    )
                }

                openItem != null -> {
                    Text(
                        stringResource(R.string.settings_sharing_choose_partner),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column {
                            partners.forEachIndexed { index, (pairId, name) ->
                                if (index > 0) RowDivider()
                                val shared = when (openItem) {
                                    is OpenItem.Streak ->
                                        (openItem.item.perPair[pairId] ?: StreakSharingFlags.None).shareStreak
                                    OpenItem.Other ->
                                        (current.profile[pairId] ?: ProfileSharingFlags.None).let {
                                            it.shareCheckIns || it.shareRecovery || it.shareAvailability
                                        }
                                }
                                IndexRow(
                                    icon = Icons.Outlined.Person,
                                    title = name,
                                    subtitle = when (openItem) {
                                        is OpenItem.Streak -> streakSummaryForPair(
                                            openItem.item.perPair[pairId] ?: StreakSharingFlags.None,
                                        )
                                        OpenItem.Other -> profileSummaryForPair(
                                            current.profile[pairId] ?: ProfileSharingFlags.None,
                                        )
                                    },
                                    // Owner directive 2026-11-09: the summary line already names
                                    // who sees it; the dot makes "shared" scannable at a glance.
                                    tone = if (shared) StatusTone.GOOD else StatusTone.INFO,
                                    onClick = { openPairId = pairId },
                                )
                            }
                        }
                    }
                }

                else -> {
                    Text(
                        stringResource(R.string.settings_sharing_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    SectionHeader(stringResource(R.string.settings_sharing_streaks_header))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column {
                            if (current.habits.isEmpty()) {
                                Text(
                                    stringResource(R.string.settings_sharing_empty),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(16.dp),
                                )
                            }
                            current.habits.forEachIndexed { index, item ->
                                if (index > 0) RowDivider()
                                IndexRow(
                                    icon = Icons.Outlined.LocalFireDepartment,
                                    title = item.label,
                                    subtitle = overviewSummary(
                                        partners.filter { item.perPair[it.first]?.shareStreak == true }
                                            .map { it.second },
                                    ),
                                    tone = if (partners.any { item.perPair[it.first]?.shareStreak == true }) {
                                        StatusTone.GOOD
                                    } else {
                                        StatusTone.INFO
                                    },
                                    onClick = {
                                        openItemId = item.progressId
                                        if (!partnerLevelShown) openPairId = partners.first().first
                                    },
                                )
                            }
                        }
                    }

                    SectionHeader(stringResource(R.string.settings_sharing_other_header))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        IndexRow(
                            icon = Icons.Outlined.Block,
                            title = stringResource(R.string.settings_sharing_other_title),
                            subtitle = overviewSummary(
                                partners.filter {
                                    val flags = current.profile[it.first] ?: ProfileSharingFlags.None
                                    flags.shareCheckIns || flags.shareRecovery || flags.shareAvailability
                                }.map { it.second },
                            ),
                            tone = if (partners.any {
                                    val flags = current.profile[it.first] ?: ProfileSharingFlags.None
                                    flags.shareCheckIns || flags.shareRecovery || flags.shareAvailability
                                }
                            ) {
                                StatusTone.GOOD
                            } else {
                                StatusTone.INFO
                            },
                            onClick = {
                                openItemId = ITEM_OTHER
                                if (!partnerLevelShown) openPairId = partners.first().first
                            },
                        )
                    }
                }
            }
        }
    }
}

/** The middle level's item: one streak, or the profile-wide bundle. */
private sealed interface OpenItem {
    data class Streak(val item: HabitSharingItem) : OpenItem

    data object Other : OpenItem
}

/** The detail level: the switches for one (item, partner), headed by whose view this is. */
@Composable
private fun ItemDetail(
    item: OpenItem,
    pairName: String,
    saving: Boolean,
    streakFlags: StreakSharingFlags,
    profileFlags: ProfileSharingFlags,
    onStreakChange: (StreakSharingFlags) -> Unit,
    onProfileChange: (ProfileSharingFlags) -> Unit,
) {
    Text(
        stringResource(R.string.settings_sharing_with_partner, pairName),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            when (item) {
                is OpenItem.Streak -> {
                    ToggleRow(
                        icon = Icons.Outlined.LocalFireDepartment,
                        title = stringResource(R.string.settings_sharing_master_title),
                        subtitle = stringResource(R.string.settings_sharing_streak_body),
                        checked = streakFlags.shareStreak,
                        enabled = !saving,
                        onCheckedChange = { onStreakChange(streakFlags.copy(shareStreak = it)) },
                    )
                    RowDivider()
                    ToggleRow(
                        icon = Icons.Outlined.RestartAlt,
                        title = stringResource(R.string.settings_sharing_relapses_title),
                        subtitle = stringResource(R.string.settings_sharing_relapses_body, item.item.label),
                        checked = streakFlags.shareRelapses,
                        enabled = !saving && streakFlags.shareStreak,
                        onCheckedChange = { onStreakChange(streakFlags.copy(shareRelapses = it)) },
                    )
                    RowDivider()
                    ToggleRow(
                        icon = Icons.Outlined.EmojiEvents,
                        title = stringResource(R.string.settings_sharing_milestones_title),
                        subtitle = stringResource(R.string.settings_sharing_milestones_body, item.item.label),
                        checked = streakFlags.shareMilestones,
                        enabled = !saving && streakFlags.shareStreak,
                        onCheckedChange = { onStreakChange(streakFlags.copy(shareMilestones = it)) },
                    )
                }

                OpenItem.Other -> {
                    ToggleRow(
                        icon = Icons.Outlined.Block,
                        title = stringResource(R.string.settings_sharing_checkins_title),
                        subtitle = stringResource(R.string.settings_sharing_checkins_body),
                        checked = profileFlags.shareCheckIns,
                        enabled = !saving,
                        onCheckedChange = { onProfileChange(profileFlags.copy(shareCheckIns = it)) },
                    )
                    RowDivider()
                    ToggleRow(
                        icon = Icons.Outlined.SelfImprovement,
                        title = stringResource(R.string.settings_sharing_recovery_title),
                        subtitle = stringResource(R.string.settings_sharing_recovery_body),
                        checked = profileFlags.shareRecovery,
                        enabled = !saving,
                        onCheckedChange = { onProfileChange(profileFlags.copy(shareRecovery = it)) },
                    )
                    RowDivider()
                    ToggleRow(
                        icon = Icons.Outlined.VerifiedUser,
                        title = stringResource(R.string.settings_sharing_availability_title),
                        subtitle = stringResource(R.string.settings_sharing_availability_body),
                        checked = profileFlags.shareAvailability,
                        enabled = !saving,
                        onCheckedChange = { onProfileChange(profileFlags.copy(shareAvailability = it)) },
                    )
                    // Step 10: the switch and what this device does are already live — this is
                    // only the server-side convergence report, so it reads as pending, not error.
                    if (profileFlags.availabilityPending) {
                        Text(
                            stringResource(R.string.settings_sharing_availability_pending),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 56.dp, end = 16.dp, bottom = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

/** One partner row's "what this partner sees for this streak" line. Master off reads
 *  "Not shared": the category flags are gated by the master and nothing is sent while it is
 *  off, whatever their own stored values say. */
@Composable
private fun streakSummaryForPair(flags: StreakSharingFlags): String {
    if (!flags.shareStreak) return stringResource(R.string.settings_sharing_summary_off)
    val parts = buildList {
        add(stringResource(R.string.settings_sharing_summary_streak))
        if (flags.shareRelapses) add(stringResource(R.string.settings_sharing_relapses_title))
        if (flags.shareMilestones) add(stringResource(R.string.settings_sharing_milestones_title))
    }
    return parts.joinToString(", ")
}

@Composable
private fun profileSummaryForPair(flags: ProfileSharingFlags): String {
    if (!flags.shareCheckIns && !flags.shareRecovery && !flags.shareAvailability) {
        return stringResource(R.string.settings_sharing_summary_off)
    }
    val parts = buildList {
        if (flags.shareCheckIns) add(stringResource(R.string.settings_sharing_checkins_title))
        if (flags.shareRecovery) add(stringResource(R.string.settings_sharing_recovery_title))
        if (flags.shareAvailability) add(stringResource(R.string.settings_sharing_availability_title))
    }
    return parts.joinToString(", ")
}

/** The overview row's "who sees this" line: the name when exactly one partner has it on, a
 *  count beyond that (five names stop being a summary), "Not shared" when nobody does. */
@Composable
private fun overviewSummary(sharingNames: List<String>): String = when {
    sharingNames.isEmpty() -> stringResource(R.string.settings_sharing_summary_off)
    sharingNames.size == 1 -> stringResource(R.string.settings_sharing_shared_with_one, sharingNames[0])
    else -> stringResource(R.string.settings_sharing_shared_with_many, sharingNames.size)
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 4.dp),
    )
}
