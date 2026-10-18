package com.example.myexodus.ui.rules

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myexodus.R
import com.example.myexodus.blocking.RulesRepository
import com.example.myexodus.blocking.usage.PlatformUsageReader
import com.example.myexodus.blocking.usage.UsageAccountingEngine
import com.example.myexodus.data.db.AppRulesStore
import com.example.myexodus.data.db.entity.RestrictionRule
import com.example.myexodus.data.db.entity.isExpiredAt
import com.example.myexodus.domainusage.DomainUsageEngine
import com.example.myexodus.domainusage.EngineMode
import com.example.myexodus.domainusage.EngineStatus
import com.example.myexodus.domainusage.PublicSuffixList
import com.example.myexodus.ui.APP_MARK_SIZE
import com.example.myexodus.ui.AppMark
import com.example.myexodus.ui.BackRow
import com.example.myexodus.ui.LinkRow
import com.example.myexodus.ui.WindowWidth
import com.example.myexodus.ui.preloadAppLabels
import com.example.myexodus.ui.rememberAppLabel
import com.example.myexodus.ui.rememberWindowWidth
import com.example.myexodus.ui.ruleRowState
import com.example.myexodus.ui.scheduleSummary
import com.example.myexodus.vpn.DnsSinkholeVpnService
import com.example.myexodus.vpn.VpnEngineState
import com.example.myexodus.vpn.VpnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val ROUTE_LIST = "list"
private const val ROUTE_EDIT = "edit"

private const val TAG = "RulesScreen"

/**
 * V1 Rules screen (plan v2 §2, executed as UI-spec only): CRUD over the shipped
 * [RestrictionRule] model and engine. Every write goes through [RulesRepository], so the
 * policy version bumps and the enforcement snapshot republishes in the same transaction — a
 * saved change is live immediately. Nothing shows as saved unless the write committed: the
 * pause switch reverts and the banner names the failure (plan §6.1/§9).
 */
@Composable
fun RulesScreen(
    store: AppRulesStore,
    repository: RulesRepository,
    usageAccountingEngine: UsageAccountingEngine,
    platformUsage: PlatformUsageReader,
    domainUsageEngine: DomainUsageEngine,
    psl: PublicSuffixList,
    modifier: Modifier = Modifier,
    openRuleId: String? = null,
    onOpenRuleConsumed: () -> Unit = {},
) {
    val profile by store.userProfileDao().observeActive().collectAsStateWithLifecycle(initialValue = null)
    val profileId = profile?.userId
    // null = not yet emitted: an empty list here would flash "No blocks yet" while loading.
    val rules by remember(profileId) {
        profileId?.let { store.restrictionRuleDao().observeByProfile(it) } ?: emptyFlow()
    }.collectAsStateWithLifecycle(initialValue = null)

    var route by rememberSaveable { mutableStateOf(ROUTE_LIST) }
    var editRuleId by rememberSaveable { mutableStateOf<String?>(null) }
    // System back exits the editor the way its BackRow does; at the list the shell takes over.
    BackHandler(enabled = route != ROUTE_LIST) { route = ROUTE_LIST }
    var editTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var editTargetType by rememberSaveable { mutableStateOf("APP") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Finding #19: a Home block row hands over its rule id — open that editor directly.
    LaunchedEffect(openRuleId) {
        if (openRuleId != null) {
            editRuleId = openRuleId
            route = ROUTE_EDIT
            onOpenRuleConsumed()
        }
    }

    // Optimistic pause state: shown immediately, dropped once the committed value matches,
    // cleared outright when the write fails (switch snaps back).
    var pendingPause by remember { mutableStateOf(emptyMap<String, Boolean>()) }
    LaunchedEffect(rules, pendingPause) {
        if (pendingPause.isNotEmpty()) {
            pendingPause = pendingPause.filterNot { (id, want) ->
                rules?.firstOrNull { it.ruleId == id }?.isEnabled == want
            }
        }
    }

    // Live pills flip at schedule boundaries; a slow tick keeps them honest while on screen.
    // S9.3 sets the floor for the usage figures below: they refresh at most every 5 s.
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            nowMillis = System.currentTimeMillis()
        }
    }

    // #18 (owner ruling 2026-10-04): a rule whose duration lapsed flips its own toggle off in
    // the DB — the switch then tells the same truth the engine already acts on (Rule 3 ignores
    // expired rules). Keyed on the exact id set, so it fires when any rule crosses its end
    // (not every 5 s tick), and a failed sweep retries when the set changes again.
    val expiredEnabledIds = rules.orEmpty()
        .filter { it.isEnabled && it.isExpiredAt(nowMillis) }
        .map { it.ruleId }
        .sorted()
    LaunchedEffect(expiredEnabledIds) {
        if (expiredEnabledIds.isNotEmpty()) {
            runCatching { repository.sweepExpired(System.currentTimeMillis()) }
                .onFailure { Log.w(TAG, "Expired-rule sweep failed", it) }
        }
    }

    var confirmCleanup by remember { mutableStateOf(false) }

    // S9.4 banner copy. The engine's own status is the source; an unknown future reason falls
    // back to the generic line rather than showing nothing.
    val engineStatus by domainUsageEngine.status.collectAsStateWithLifecycle()
    // Audit A-03: the engine's runtime mode is the same value every enforcement path reads
    // (S1.3 override included). It is a volatile, not a State — the 5 s tick above already
    // recomposes this screen, so a mode changed at the next engine start is picked up.
    val websiteLimitsMode: EngineMode = domainUsageEngine.mode
    val engineBannerMessage = when (val s = engineStatus) {
        is EngineStatus.Degraded -> when (s.reason) {
            EngineStatus.Reason.PRIVATE_DNS -> stringResource(R.string.domain_usage_banner_private_dns)
            EngineStatus.Reason.DB_ERROR, EngineStatus.Reason.INTERNAL_ERROR ->
                stringResource(R.string.domain_usage_banner_off)
        }
        // Shown only when a limited rule exists (the `.takeIf { hasLimitedSite }` below).
        else -> if (websiteLimitMechanismAvailable(isApp = false, mode = websiteLimitsMode)) {
            null
        } else {
            stringResource(R.string.domain_usage_banner_unavailable)
        }
    }

    // #26 (activation): does anything live need the tunnel, and is it up? The tunnel serves
    // every website rule — block-only ("Always on") as much as daily-limited.
    val hasLiveWebsite = rules?.let { hasLiveWebsiteRule(it, nowMillis) } ?: false
    // The engine/usage surfaces below stay limit-scoped (S9.4): with no limited rule the
    // engine's status explains nothing.
    val hasLimitedSite = rules?.let { hasLimitedSiteRule(it, nowMillis) } ?: false
    // Keyed on the list itself (not on a predicate): while it is still loading, `rules` is
    // null and the effect must NOT decide — an unknown rule set is not "no rules", and
    // deciding on it would stop a healthy tunnel (device-found 2026-10-04).
    LaunchedEffect(rules, engineStatus) {
        val loaded = rules ?: return@LaunchedEffect
        // Starts a consented tunnel the moment the first website rule appears (or on entry,
        // if it was down); stops it the moment the last one leaves. Consent-less is a NONE
        // inside `ensure` — the row below is the only path into the system dialog, never
        // automatic.
        VpnLifecycle.ensure(context, hasRules = hasLiveWebsiteRule(loaded, System.currentTimeMillis()))
    }
    // Consent is read fresh at composition (the 5 s tick re-reads it): the old remembered
    // copy went stale right after a grant — the tunnel established while the card still said
    // "off", and its tap silently no-op'ed (stress-found 2026-10-15).
    // The editor watches the engine too (owner report 2026-10-17): a new website rule is inert
    // until the tunnel is on, so the same blocker drives the editor's warning and save prompt.
    val engineWatched = hasLiveWebsite || (route == ROUTE_EDIT && editTargetType == "WEBSITE")
    val engineBlocker = if (engineWatched) {
        VpnLifecycle.websiteEngineBlocker(VpnLifecycle.consented(context), DnsSinkholeVpnService.state)
    } else {
        null
    }
    val engineOffMessage = when {
        !hasLiveWebsite -> null
        engineBlocker == VpnLifecycle.WebsiteEngineBlocker.NEEDS_CONSENT ->
            stringResource(R.string.domain_usage_off_body)
        engineBlocker != null -> stringResource(R.string.domain_usage_off_retry)
        else -> null
    }
    // Non-null intent: no consent on file — walk the system dialog. Null: consent is already
    // on file — an establish retry, `ensure`-routed so a startable verdict is the service's
    // decision to make. Shared by the list row and the editor's fix-it actions.
    //
    // The dialog MUST be launched for result: ConfirmDialog reads getCallingPackage(), which
    // is null for a plain start. It then calls Vpn.prepare(null, null), which short-circuits
    // to true "for backward compatibility" — the dialog sets RESULT_OK and finishes without
    // ever rendering (device-found 2026-10-17, tap did nothing).
    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { VpnLifecycle.ensure(context, hasRules = true) }
    val enableWebsiteEngine: () -> Unit = remember(context, consentLauncher) {
        {
            VpnLifecycle.consentIntent(context)?.let(consentLauncher::launch)
                ?: VpnLifecycle.ensure(context, hasRules = true)
        }
    }

    when (route) {
        ROUTE_EDIT -> {
            val editing = editRuleId?.let { id -> rules?.firstOrNull { it.ruleId == id } }
            if (editRuleId != null && editing == null) {
                // Config change / process death before the Flow re-emitted; a rule that was
                // really deleted simply leaves the back action available.
                EditorUnavailable(onBack = { route = ROUTE_LIST }, modifier = modifier)
            } else {
                RuleEditorScreen(
                    initial = editing,
                    targetType = editTargetType,
                    target = editTarget,
                    existingRules = rules.orEmpty(),
                    repository = repository,
                    usageAccountingEngine = usageAccountingEngine,
                    platformUsage = platformUsage,
                    psl = psl,
                    websiteEngineBlocker = engineBlocker,
                    websiteLimitsMode = websiteLimitsMode,
                    onEnableWebsiteEngine = enableWebsiteEngine,
                    onDone = { route = ROUTE_LIST },
                    modifier = modifier,
                )
            }
        }

        else -> {
            val loaded = rules
            if (loaded == null) {
                Box(modifier.fillMaxSize())
            } else {
                RulesList(
                    rules = loaded,
                    pendingPause = pendingPause,
                    nowMillis = nowMillis,
                    domainUsageEngine = domainUsageEngine,
                    engineBannerMessage = engineBannerMessage.takeIf { hasLimitedSite },
                    engineOffMessage = engineOffMessage,
                    onEnableEngine = enableWebsiteEngine,
                    errorMessage = errorMessage,
                    onDismissError = { errorMessage = null },
                    onAddApp = {
                        editRuleId = null
                        editTarget = null
                        editTargetType = "APP"
                        route = ROUTE_EDIT
                    },
                    onAddWebsite = {
                        editRuleId = null
                        editTarget = null
                        editTargetType = "WEBSITE"
                        route = ROUTE_EDIT
                    },
                    onEdit = { rule ->
                        editRuleId = rule.ruleId
                        editTargetType = rule.targetType
                        editTarget = rule.blockedPackage
                        route = ROUTE_EDIT
                    },
                    onTogglePause = { rule, enable ->
                        val target = if (enable) resumedRule(rule, System.currentTimeMillis()) else rule
                        pendingPause = pendingPause + (rule.ruleId to enable)
                        scope.launch(Dispatchers.IO) {
                            runCatching { repository.setEnabled(target, enable) }
                                .onSuccess {
                                    // OPD #28(a): enabling is a start-of-counting seam — whatever
                                    // the app did while paused (or before the rule existed) is
                                    // invisible to the engine. Reconcile against the platform so
                                    // a re-enabled limit counts the day's real usage.
                                    if (enable && rule.targetType == "APP" && rule.dailyLimitMs > 0L) {
                                        runCatching {
                                            usageAccountingEngine.reconcileWithPlatform(
                                                rule.blockedPackage,
                                                platformUsage.todayForegroundMs(rule.blockedPackage),
                                            )
                                        }.onFailure { Log.w(TAG, "Platform usage reconcile failed", it) }
                                    }
                                }
                                .onFailure { failure ->
                                    pendingPause = pendingPause - rule.ruleId
                                    errorMessage = notSavedMessage(failure, context)
                                }
                        }
                    },
                    onCleanup = { confirmCleanup = true },
                    modifier = modifier,
                )
            }
        }
    }

    // #18 cleanup (#rule list's "Remove all"): one confirm, then the batch delete. Count is
    // read at confirm time, so the dialog copy can never disagree with what's deleted.
    if (confirmCleanup) {
        val doomed = rules.orEmpty().filter { !it.isEnabled }
        AlertDialog(
            onDismissRequest = { confirmCleanup = false },
            title = { Text(stringResource(R.string.rules_cleanup_title)) },
            text = { Text(pluralStringResource(R.plurals.rules_cleanup_body, doomed.size, doomed.size)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmCleanup = false
                    scope.launch(Dispatchers.IO) {
                        runCatching { repository.deleteRules(doomed) }
                            .onFailure { errorMessage = notSavedMessage(it, context) }
                    }
                }) {
                    // Destructive confirm wears the error role (finding #18).
                    Text(
                        stringResource(R.string.rules_cleanup_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmCleanup = false }) { Text(stringResource(R.string.rules_cancel)) }
            },
        )
    }
}

/**
 * S9.4 (user ruling 2026-09-20): the status banner shows only when at least one live website
 * rule actually carries a limit — with none configured, the engine's status explains nothing
 * and a red banner would be a false alarm. "Live" mirrors the publisher's own filter
 * (`RulesSnapshot`): enabled and not expired.
 */
internal fun hasLimitedSiteRule(rules: List<RestrictionRule>, nowMillis: Long): Boolean =
    rules.any {
        it.targetType == "WEBSITE" && it.dailyLimitMs > 0L &&
            it.isEnabled && !it.isExpiredAt(nowMillis)
    }

/**
 * #26: "the tunnel has something to do" — any website rule the publisher would publish
 * (enabled, unexpired), limit or block-only. One predicate per purpose: the limited variant
 * above still gates the engine's own status surfaces.
 */
internal fun hasLiveWebsiteRule(rules: List<RestrictionRule>, nowMillis: Long): Boolean =
    rules.any {
        it.targetType == "WEBSITE" && it.isEnabled && !it.isExpiredAt(nowMillis)
    }

/**
 * #18 resume semantics (owner ruling 2026-10-04): "on" after a lapsed end date means "on
 * again, no end date" — keeping the old expiry would make the toggle dead (the engine treats
 * the rule as expired and the sweep would flip it right back off). A future expiry is the
 * user's own setting and survives.
 */
internal fun resumedRule(rule: RestrictionRule, nowMillis: Long): RestrictionRule =
    if (rule.isExpiredAt(nowMillis)) rule.copy(blockExpiresAt = 0L) else rule

/** Maps a write failure to copy that says what happened and what to do (plan §9). */
internal fun notSavedMessage(failure: Throwable, context: android.content.Context): String {
    val detail = when (failure) {
        is com.example.myexodus.data.db.ProtectedPackageException ->
            context.getString(R.string.error_self_block)
        is com.example.myexodus.blocking.RulesetLimitException ->
            failure.message ?: context.getString(R.string.error_limit_reached)
        else -> context.getString(R.string.error_write_failed)
    }
    return context.getString(R.string.editor_not_saved, detail)
}

@Composable
private fun EditorUnavailable(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize()) {
        BackRow(label = stringResource(R.string.editor_edit), onBack = onBack)
        Text(
            stringResource(R.string.editor_rule_gone),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

/**
 * Task #13: height cap on the rule-list cards (Home blocks card + Rules sections). The cards
 * are items of a page-level LazyColumn, so their inner LazyColumn must be bounded or it is
 * measured with infinity constraints and crashes. ~8 rows; taller lists scroll inside the
 * card. Skipped: flattening rows into the page list (drops the card surface) — revisit if
 * the nested scroll feels wrong on device.
 */
internal val RULES_CARD_MAX_HEIGHT = 480.dp

@Composable
private fun RulesList(
    rules: List<RestrictionRule>,
    pendingPause: Map<String, Boolean>,
    nowMillis: Long,
    domainUsageEngine: DomainUsageEngine,
    engineBannerMessage: String?,
    /** #26: non-null when live website rules exist but the tunnel can't serve them (consent
     *  missing, or the last establish failed) — the row that walks back to the system
     *  dialog / retry. */
    engineOffMessage: String?,
    onEnableEngine: () -> Unit,
    errorMessage: String?,
    onDismissError: () -> Unit,
    onAddApp: () -> Unit,
    onAddWebsite: () -> Unit,
    onEdit: (RestrictionRule) -> Unit,
    onTogglePause: (RestrictionRule, Boolean) -> Unit,
    onCleanup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sidePadding: Dp = if (rememberWindowWidth() == WindowWidth.COMPACT) 16.dp else 24.dp
    // Task #13: same off-main label warm-up as Home — the rules list reaches the same cap.
    val labelContext = LocalContext.current
    LaunchedEffect(rules) {
        if (rules.isNotEmpty()) preloadAppLabels(labelContext, rules.map { it.blockedPackage })
    }
    val appsHeader = stringResource(R.string.rules_apps_header)
    val websitesHeader = stringResource(R.string.rules_websites_header)
    val deactivatedHeader = stringResource(R.string.rules_deactivated_header)
    val deactivatedCaption = stringResource(R.string.rules_deactivated_caption)
    val cleanupLabel = stringResource(R.string.rules_cleanup)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = sidePadding, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "header") {
            Column {
                Text(stringResource(R.string.rules_title), style = MaterialTheme.typography.headlineSmall)
                Text(
                    stringResource(R.string.rules_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        engineBannerMessage?.let { message ->
            // S9.4: a live status claim, so it has no dismiss — it leaves when its cause does.
            item(key = "engine-status") {
                ErrorBanner(message = message, onDismiss = null)
            }
        }
        engineOffMessage?.let { body ->
            // #26: priming row for the platform's VPN consent (or an establish retry). It
            // states why the consent screen that follows appears and that the choice is
            // reversible — the tap itself is the only way into that dialog.
            item(key = "engine-off") {
                LinkRow(
                    title = stringResource(R.string.domain_usage_off_title),
                    body = body,
                    onClick = onEnableEngine,
                )
            }
        }
        errorMessage?.let { message ->
            item(key = "error") {
                ErrorBanner(message = message, onDismiss = onDismissError)
            }
        }
        item(key = "add") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AddButton(
                    label = stringResource(R.string.rules_add_app),
                    onClick = onAddApp,
                )
                AddButton(
                    label = stringResource(R.string.rules_add_website),
                    onClick = onAddWebsite,
                )
            }
        }
        if (rules.isEmpty()) {
            item(key = "empty") {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.rules_empty_title), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.rules_empty_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            // #18: Deactivated (paused + duration-lapsed) rules leave the live sections —
            // their switches tell non-enforcement there, beside a way to sweep them out.
            ruleSection(
                header = appsHeader,
                rules = rules.filter { it.targetType == "APP" && it.isEnabled },
                pendingPause = pendingPause,
                nowMillis = nowMillis,
                domainUsageEngine = domainUsageEngine,
                onEdit = onEdit,
                onTogglePause = onTogglePause,
            )
            ruleSection(
                header = websitesHeader,
                rules = rules.filter { it.targetType == "WEBSITE" && it.isEnabled },
                pendingPause = pendingPause,
                nowMillis = nowMillis,
                domainUsageEngine = domainUsageEngine,
                onEdit = onEdit,
                onTogglePause = onTogglePause,
            )
            ruleSection(
                header = deactivatedHeader,
                caption = deactivatedCaption,
                rules = rules.filter { !it.isEnabled },
                pendingPause = pendingPause,
                nowMillis = nowMillis,
                domainUsageEngine = domainUsageEngine,
                onEdit = onEdit,
                onTogglePause = onTogglePause,
                footer = {
                    TextButton(onClick = onCleanup) {
                        Text(cleanupLabel, color = MaterialTheme.colorScheme.error)
                    }
                },
            )
        }
    }
}

@Composable
private fun AddButton(label: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick) {
        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}

private fun LazyListScope.ruleSection(
    header: String,
    rules: List<RestrictionRule>,
    pendingPause: Map<String, Boolean>,
    nowMillis: Long,
    domainUsageEngine: DomainUsageEngine,
    onEdit: (RestrictionRule) -> Unit,
    onTogglePause: (RestrictionRule, Boolean) -> Unit,
    caption: String? = null,
    footer: (@Composable () -> Unit)? = null,
) {
    if (rules.isEmpty()) return
    item(key = "header-$header") {
        Text(
            header,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    caption?.let { text ->
        item(key = "caption-$header") {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    item(key = "card-$header") {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Task #13: lazy body — only visible rows compose at the 4000-rule ceiling.
            // Bounded height: this card is an item of the page LazyColumn.
            LazyColumn(modifier = Modifier.heightIn(max = RULES_CARD_MAX_HEIGHT)) {
                itemsIndexed(rules, key = { _, rule -> rule.ruleId }) { index, rule ->
                    if (index > 0) {
                        HorizontalDivider(
                            // Starts where the row text starts: row padding + mark + gap.
                            modifier = Modifier.padding(start = 16.dp + APP_MARK_SIZE + 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                    RuleRow(
                        rule = rule,
                        enabled = pendingPause[rule.ruleId] ?: rule.isEnabled,
                        awaitingWrite = pendingPause.containsKey(rule.ruleId),
                        nowMillis = nowMillis,
                        domainUsageEngine = domainUsageEngine,
                        onEdit = { onEdit(rule) },
                        onTogglePause = { enable -> onTogglePause(rule, enable) },
                    )
                }
            }
        }
    }
    footer?.let { content ->
        item(key = "footer-$header") { content() }
    }
}

@Composable
private fun RuleRow(
    rule: RestrictionRule,
    enabled: Boolean,
    /** A pause write for this rule is in flight — the switch is locked until it lands. */
    awaitingWrite: Boolean,
    nowMillis: Long,
    domainUsageEngine: DomainUsageEngine,
    onEdit: () -> Unit,
    onTogglePause: (Boolean) -> Unit,
) {
    val label = rememberAppLabel(rule.blockedPackage)
    val res = LocalResources.current
    val state = remember(rule, enabled, nowMillis, res) { ruleRowState(rule, nowMillis, res) }
    val pauseLabel = stringResource(R.string.rules_pause, label)
    val resumeLabel = stringResource(R.string.rules_resume, label)
    val editLabel = stringResource(R.string.rules_edit)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = editLabel, onClick = onEdit)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // The blocked app's own icon where we can read it — a generic glyph says nothing about
        // which app a row is (same technique as the picker that chose it). An uninstalled
        // package, or a website, falls back to the glyph.
        AppMark(
            packageName = if (rule.targetType == "WEBSITE") null else rule.blockedPackage,
            fallback = if (rule.targetType == "WEBSITE") Icons.Default.Language else Icons.Default.Apps,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                scheduleSummary(rule, res),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // Same allowance as the Home block row (finding #20): "Always on · until …"
                // must not lose the expiry date to a one-line ellipsis.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // S9.3: the row's own progress against its limit. Null until the engine's startup
            // load attaches the ledger — hiding the line beats claiming a false zero.
            if (rule.targetType == "WEBSITE" && rule.dailyLimitMs > 0L && enabled) {
                domainUsageEngine.usageTodayMs(rule.ruleId)?.let { usedMs ->
                    Text(
                        stringResource(
                            R.string.rules_used_today,
                            (usedMs / 60_000L).toInt(),
                            (rule.dailyLimitMs / 60_000L).toInt(),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        state.label?.let { StatePill(label = it, active = state.active && enabled) }
        Switch(
            checked = enabled,
            onCheckedChange = onTogglePause,
            // Finding #18 (2026-10-16): locked while this rule's write is in flight. Two
            // overlapping setEnabled writes can commit in either order (two IO threads), and an
            // inverted commit leaves the switch showing the opposite of what the engine does —
            // the pendingPause entry never clears because the committed value never matches the
            // asked one.
            enabled = !awaitingWrite,
            modifier = Modifier.semantics {
                contentDescription = if (enabled) pauseLabel else resumeLabel
            },
        )
    }
}

@Composable
private fun StatePill(label: String, active: Boolean) {
    val accent = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(8.dp).background(accent, CircleShape))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * The app's one red banner: writes that failed, and (S9.4) live engine-status claims. A null
 * [onDismiss] means the message is not the user's to clear — it belongs to a system state and
 * leaves when that state does. [actionLabel]/[onAction] add a fix-it affordance (S9.1's
 * "Use it").
 */
@Composable
internal fun ErrorBanner(
    message: String,
    onDismiss: (() -> Unit)?,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
            onDismiss?.let { dismiss ->
                // "Cancel" here read as an action on the write — which had already failed.
                TextButton(onClick = dismiss) { Text(stringResource(R.string.rules_dismiss)) }
            }
        }
    }
}
