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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.example.myexodus.applock.markExpectedExternal
import com.example.myexodus.blocking.RulesRepository
import com.example.myexodus.blocking.RulesetLimitException
import com.example.myexodus.blocking.usage.PlatformUsageReader
import com.example.myexodus.blocking.usage.UsageAccountingEngine
import com.example.myexodus.data.db.AppRulesStore
import com.example.myexodus.data.db.vaultClosedFlow
import com.example.myexodus.data.db.entity.RestrictionRule
import com.example.myexodus.data.db.entity.hasLiveWebsiteRule
import com.example.myexodus.data.db.entity.isExpiredAt
import com.example.myexodus.data.db.entity.isStaleFor
import com.example.myexodus.domainusage.DomainUsageEngine
import com.example.myexodus.domainusage.EngineMode
import com.example.myexodus.domainusage.EngineStatus
import com.example.myexodus.domainusage.PublicSuffixList
import com.example.myexodus.ui.APP_MARK_SIZE
import com.example.myexodus.ui.AppMark
import com.example.myexodus.ui.BackRow
import com.example.myexodus.ui.LinkRow
import com.example.myexodus.ui.WindowWidth
import com.example.myexodus.ui.partner.PartnerDashboardSource
import com.example.myexodus.ui.partner.ReachOutTarget
import com.example.myexodus.ui.preloadAppLabels
import com.example.myexodus.ui.rememberAppLabel
import com.example.myexodus.ui.rememberInstalledAppPackages
import com.example.myexodus.ui.rememberWindowWidth
import com.example.myexodus.ui.ruleRowState
import com.example.myexodus.ui.scheduleSummary
import com.example.myexodus.ui.visibleRules
import com.example.myexodus.vpn.DnsSinkholeVpnService
import com.example.myexodus.vpn.VpnEngineState
import com.example.myexodus.vpn.VpnLifecycle
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val ROUTE_LIST = "list"
private const val ROUTE_EDIT = "edit"
private const val ROUTE_BATCH = "batch"

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
    /** Plan §2.1 req 1: the live pairs a rule can be assigned to (the editor's assignee list). */
    partnerSource: PartnerDashboardSource,
    modifier: Modifier = Modifier,
    openRuleId: String? = null,
    onOpenRuleConsumed: () -> Unit = {},
) {
    val profile by vaultClosedFlow { store.userProfileDao().observeActive() }
        .collectAsStateWithLifecycle(initialValue = null)
    val profileId = profile?.userId
    // Owner directive 2026-10-21: the list owns an explicit loading / loaded / failed state.
    // A failed local read must render as a state with a retry, never as "no blocks" or a crash.
    var loadRetry by remember { mutableIntStateOf(0) }
    val loadState by remember(profileId, loadRetry) {
        profileId?.let { id ->
            vaultClosedFlow { store.restrictionRuleDao().observeByProfile(id) }
                .map<List<RestrictionRule>, RulesLoad> { RulesLoad.Loaded(it) }
                .catch { failure ->
                    Log.e(TAG, "Rules load failed", failure)
                    emit(RulesLoad.Failed)
                }
        } ?: flowOf(RulesLoad.Loading)
    }.collectAsStateWithLifecycle(initialValue = RulesLoad.Loading)
    val rules = (loadState as? RulesLoad.Loaded)?.rules
    // Owner ruling 2026-11-01: an uninstalled app's block is hidden but kept — it returns
    // active on reinstall. Raw `rules` still feed the editor and the preset duplicate check.
    val installedPackages = rememberInstalledAppPackages()
    val shownRules = rules?.let { visibleRules(it, installedPackages) }

    var route by rememberSaveable { mutableStateOf(ROUTE_LIST) }
    var editRuleId by rememberSaveable { mutableStateOf<String?>(null) }
    var editBatchId by rememberSaveable { mutableStateOf<String?>(null) }
    // System back exits the editor the way its BackRow does; at the list the shell takes over.
    BackHandler(enabled = route != ROUTE_LIST) { route = ROUTE_LIST }
    var editTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var editTargetType by rememberSaveable { mutableStateOf("APP") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Per-rule partner assignment (plan §2.1 req 1): the live pairs, read once per Rules entry
    // — pairs only change on the Partner tab, so this page cannot go stale mid-visit.
    var partners by remember { mutableStateOf<List<ReachOutTarget>>(emptyList()) }
    LaunchedEffect(Unit) {
        partners = withContext(Dispatchers.IO) {
            runCatching { partnerSource.pairRoster() }.getOrDefault(emptyList())
        }
    }

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

    // Owner report 2026-11-08: a spent daily limit must read "Blocked now", and a limited app
    // row must show today's usage. The accounting ledger is what the engine blocks on. Rows
    // anchored to another day or zone are skipped, never shown as this morning's zero.
    val usageRows by remember(profileId) {
        profileId?.let { id -> vaultClosedFlow { store.dailyUsageTrackerDao().observeForProfile(id) } }
            ?: flowOf(emptyList())
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val usageByPackage = remember(usageRows, nowMillis) {
        val today = LocalDate.now().toString()
        val zone = ZoneId.systemDefault().id
        usageRows.filterNot { it.isStaleFor(today, zone) }
            .associate { it.packageName to it.msUsedToday }
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
    var confirmDeleteBatch by remember { mutableStateOf<PendingBatchDelete?>(null) }
    // Batch master switches carry the same optimistic-with-revert contract as a rule's.
    var pendingBatchPause by remember { mutableStateOf(emptyMap<String, Boolean>()) }
    LaunchedEffect(rules, pendingBatchPause) {
        if (pendingBatchPause.isNotEmpty()) {
            pendingBatchPause = pendingBatchPause.filterNot { (batchId, want) ->
                val members = rules.orEmpty().filter { it.batchId == batchId }
                members.isNotEmpty() && members.all { it.isEnabled == want }
            }
        }
    }

    // Category presets (owner request 2026-10-21). The sheet is open while categoryId is
    // non-null; a confirmed list is held in pendingPresetSites only while the engine-off
    // prompt is up, so the same insert path runs whether protection is on or off.
    var presetCategoryId by rememberSaveable { mutableStateOf<String?>(null) }
    var presetSaving by remember { mutableStateOf(false) }
    var presetError by remember { mutableStateOf<String?>(null) }
    var pendingPresetSites by remember { mutableStateOf<List<String>?>(null) }
    val deviceZone = remember { ZoneId.systemDefault().id }

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
    val engineWatched = hasLiveWebsite ||
        (route == ROUTE_EDIT && editTargetType == "WEBSITE") ||
        route == ROUTE_BATCH ||
        presetCategoryId != null
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
            VpnLifecycle.consentIntent(context)?.let { consent ->
                context.markExpectedExternal("vpn_consent")
                consentLauncher.launch(consent)
            } ?: VpnLifecycle.ensure(context, hasRules = true)
        }
    }

    val openAddWebsite: () -> Unit = {
        presetCategoryId = null
        presetError = null
        editRuleId = null
        editTarget = null
        editTargetType = "WEBSITE"
        route = ROUTE_EDIT
    }

    // One insert path for both prompt answers and the no-prompt case. Already-blocked hosts
    // are filtered here as well as in the sheet: the sheet's snapshot can lag the list by one
    // emission, and the table's unique (profile_id, blocked_package) index would reject a
    // duplicate mid-batch, turning a retry into a partial failure.
    fun blockPresetSites(sites: List<String>) {
        val taken = rules.orEmpty().asSequence()
            .filter { it.targetType == "WEBSITE" }
            .map { targetKey(it.blockedPackage, "WEBSITE") }
            .toSet()
        val pending = sites.filterNot { targetKey(it, "WEBSITE") in taken }
        if (pending.isEmpty()) {
            presetCategoryId = null
            return
        }
        presetSaving = true
        presetError = null
        scope.launch(Dispatchers.IO) {
            var inserted = 0
            val failure = runCatching {
                val profileId = repository.ensureActiveProfile().userId
                pending.forEach { host ->
                    repository.insertRule(websitePresetRule(profileId, host, deviceZone))
                    inserted++
                }
            }.exceptionOrNull()
            presetSaving = false
            if (failure == null) {
                presetCategoryId = null
            } else {
                val message = presetFailureMessage(failure, inserted, pending.size, context)
                presetError = message
                if (presetCategoryId == null) {
                    // The sheet was dismissed mid-batch, so its own error line left with it —
                    // a partial write must still be reported somewhere the user can see.
                    errorMessage = message
                }
            }
        }
    }

    when (route) {
        ROUTE_EDIT -> {
            val editing = editRuleId?.let { id -> rules?.firstOrNull { it.ruleId == id } }
            if (loadState == RulesLoad.Failed) {
                // A failed read is not "the rule is gone" — say what really happened.
                RulesLoadError(
                    onRetry = { loadRetry++ },
                    onBack = { route = ROUTE_LIST },
                    modifier = modifier,
                )
            } else if (editRuleId != null && rules == null) {
                // Still loading is not "the rule is gone" either: process-death restore and
                // the Home block-row deep link both enter this route before the Flow's first
                // emission, and "no longer exists" would be a claim this screen cannot make yet.
                RulesLoading(modifier = modifier)
            } else if (editRuleId != null && editing == null) {
                // Loaded, and the id really is absent — the rule was deleted elsewhere.
                EditorUnavailable(onBack = { route = ROUTE_LIST }, modifier = modifier)
            } else {
                RuleEditorScreen(
                    initial = editing,
                    targetType = editTargetType,
                    target = editTarget,
                    existingRules = rules.orEmpty(),
                    partners = partners,
                    repository = repository,
                    usageAccountingEngine = usageAccountingEngine,
                    platformUsage = platformUsage,
                    psl = psl,
                    websiteEngineBlocker = engineBlocker,
                    websiteLimitsMode = websiteLimitsMode,
                    onEnableWebsiteEngine = enableWebsiteEngine,
                    onBrowsePresets = { categoryId ->
                        presetError = null
                        presetCategoryId = categoryId
                    },
                    onDone = { route = ROUTE_LIST },
                    modifier = modifier,
                )
            }
        }

        ROUTE_BATCH -> when {
            loadState == RulesLoad.Failed -> RulesLoadError(
                onRetry = { loadRetry++ },
                onBack = { route = ROUTE_LIST },
                modifier = modifier,
            )
            rules == null -> RulesLoading(modifier = modifier)
            editBatchId != null && rules.orEmpty().none { it.batchId == editBatchId } ->
                EditorUnavailable(onBack = { route = ROUTE_LIST }, modifier = modifier)
            else -> BatchEditorScreen(
                initial = editBatchId?.let { id -> rules.orEmpty().filter { it.batchId == id } },
                existingRules = rules.orEmpty(),
                partners = partners,
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

        else -> when (val loaded = loadState) {
            RulesLoad.Loading -> RulesLoading(modifier = modifier)
            RulesLoad.Failed -> RulesLoadError(
                onRetry = { loadRetry++ },
                modifier = modifier,
            )
            is RulesLoad.Loaded -> {
                RulesList(
                    rules = shownRules.orEmpty(),
                    hiddenAppCount = loaded.rules.size - shownRules.orEmpty().size,
                    pendingPause = pendingPause,
                    nowMillis = nowMillis,
                    partners = partners,
                    domainUsageEngine = domainUsageEngine,
                    usageByPackage = usageByPackage,
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
                    onAddWebsite = openAddWebsite,
                    onOpenPresets = { presetError = null; presetCategoryId = it },
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
                    onAddBatch = { editBatchId = null; route = ROUTE_BATCH },
                    onEditBatch = { batchId ->
                        editBatchId = batchId
                        route = ROUTE_BATCH
                    },
                    pendingBatchPause = pendingBatchPause,
                    onToggleBatch = { batchId, enable ->
                        val members = rules.orEmpty().filter { it.batchId == batchId }
                        pendingBatchPause = pendingBatchPause + (batchId to enable)
                        scope.launch(Dispatchers.IO) {
                            runCatching { repository.setBatchEnabled(batchId, enable) }
                                .onSuccess {
                                    // Same counting seam as a single rule's resume (OPD #28(a)).
                                    if (enable) {
                                        members
                                            .filter { it.targetType == "APP" && it.dailyLimitMs > 0L }
                                            .forEach { member ->
                                                runCatching {
                                                    usageAccountingEngine.reconcileWithPlatform(
                                                        member.blockedPackage,
                                                        platformUsage.todayForegroundMs(member.blockedPackage),
                                                    )
                                                }.onFailure { Log.w(TAG, "Platform usage reconcile failed", it) }
                                            }
                                    }
                                }
                                .onFailure { failure ->
                                    pendingBatchPause = pendingBatchPause - batchId
                                    errorMessage = notSavedMessage(failure, context)
                                }
                        }
                    },
                    onRemoveBatchPartner = { batchId ->
                        scope.launch(Dispatchers.IO) {
                            runCatching { repository.removeBatchPartner(batchId) }
                                .onFailure { errorMessage = notSavedMessage(it, context) }
                        }
                    },
                    onAssignBatchPartner = { batchId, pairId ->
                        scope.launch(Dispatchers.IO) {
                            runCatching { repository.assignBatchPartner(batchId, pairId) }
                                .onFailure { errorMessage = notSavedMessage(it, context) }
                        }
                    },
                    onDeleteBatch = { batch ->
                        confirmDeleteBatch = PendingBatchDelete(batch.batchId, batch.name, batch.members.size)
                    },
                    modifier = modifier,
                )
            }
        }
    }

    // #18 cleanup (#rule list's "Remove all"): one confirm, then the batch delete. Count is
    // read at confirm time, so the dialog copy can never disagree with what's deleted.
    if (confirmCleanup) {
        // Batch members leave as one batch (their card's Delete batch), never one by one.
        // Hidden (uninstalled-app) rules are not on screen — a sweep of what the user can see
        // must not delete a stored block they never saw.
        val doomed = shownRules.orEmpty().filter { !it.isEnabled && it.batchId == null }
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

    confirmDeleteBatch?.let { doomed ->
        AlertDialog(
            onDismissRequest = { confirmDeleteBatch = null },
            title = { Text(stringResource(R.string.rules_batch_delete_title)) },
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.rules_batch_delete_body, doomed.count, doomed.name, doomed.count,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteBatch = null
                    scope.launch(Dispatchers.IO) {
                        runCatching { repository.deleteBatch(doomed.batchId) }
                            .onFailure { errorMessage = notSavedMessage(it, context) }
                    }
                }) {
                    Text(
                        stringResource(R.string.rules_batch_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteBatch = null }) {
                    Text(stringResource(R.string.rules_cancel))
                }
            },
        )
    }

    presetCategoryId?.let { categoryId ->
        WebsitePresetSheet(
            initialCategoryId = categoryId,
            existingRules = rules.orEmpty(),
            saving = presetSaving,
            error = presetError,
            onDismiss = {
                presetCategoryId = null
                presetError = null
            },
            onConfirm = { sites ->
                if (engineBlocker != null) {
                    pendingPresetSites = sites
                } else {
                    blockPresetSites(sites)
                }
            },
            onAddOwnSite = openAddWebsite,
        )
    }

    // Same decision the editor asks before saving one website rule — asked once for the whole
    // batch: turn protection on now, or block anyway and let the standing Rules row remind.
    pendingPresetSites?.let { sites ->
        AlertDialog(
            onDismissRequest = { pendingPresetSites = null },
            title = { Text(stringResource(R.string.rules_presets_engine_title)) },
            text = { Text(stringResource(R.string.rules_presets_engine_body)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingPresetSites = null
                    enableWebsiteEngine()
                    blockPresetSites(sites)
                }) { Text(stringResource(R.string.rules_presets_engine_turn_on)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingPresetSites = null
                    blockPresetSites(sites)
                }) { Text(stringResource(R.string.rules_presets_engine_anyway)) }
            },
        )
    }
}

/** The Rules list's own load state (owner directive 2026-10-21). Loading and failure are
 *  first-class renders, not a blank frame or a false "nothing blocked". */
private sealed interface RulesLoad {
    data object Loading : RulesLoad
    data class Loaded(val rules: List<RestrictionRule>) : RulesLoad
    data object Failed : RulesLoad
}

/** Failure copy for a preset batch: what happened, the repository's reason, what to do. A
 *  partial batch says how many landed, because rows really were written. */
private fun presetFailureMessage(
    failure: Throwable,
    inserted: Int,
    total: Int,
    context: android.content.Context,
): String {
    val reason = when (failure) {
        is RulesetLimitException ->
            failure.message ?: context.getString(R.string.error_limit_reached)
        else -> context.getString(R.string.rules_presets_write_failed)
    }
    return if (inserted > 0) {
        context.getString(R.string.rules_presets_partial, inserted, total, reason)
    } else {
        context.getString(R.string.rules_presets_error, reason)
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
    /** App rules kept out of the list because their app is not installed (owner ruling
     *  2026-11-01) — the caption says they are stored, not gone. */
    hiddenAppCount: Int,
    pendingPause: Map<String, Boolean>,
    nowMillis: Long,
    partners: List<ReachOutTarget>,
    domainUsageEngine: DomainUsageEngine,
    /** Today's accounted usage per package, stale anchors already dropped. */
    usageByPackage: Map<String, Long>,
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
    onOpenPresets: (categoryId: String) -> Unit,
    onEdit: (RestrictionRule) -> Unit,
    onTogglePause: (RestrictionRule, Boolean) -> Unit,
    onCleanup: () -> Unit,
    onAddBatch: () -> Unit,
    onEditBatch: (batchId: String) -> Unit,
    pendingBatchPause: Map<String, Boolean>,
    onToggleBatch: (batchId: String, enable: Boolean) -> Unit,
    onRemoveBatchPartner: (batchId: String) -> Unit,
    onAssignBatchPartner: (batchId: String, pairId: String) -> Unit,
    onDeleteBatch: (BatchGroup) -> Unit,
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
    val batchesHeader = stringResource(R.string.rules_batches_header)
    val deactivatedHeader = stringResource(R.string.rules_deactivated_header)
    val deactivatedCaption = stringResource(R.string.rules_deactivated_caption)
    val cleanupLabel = stringResource(R.string.rules_cleanup)
    // Batch members are managed through their batch card, never as loose rows: one card per
    // named batch (apps and websites together), with the master switch and their one partner.
    val batches = remember(rules) {
        rules.filter { it.batchId != null }
            .groupBy { it.batchId!! }
            .map { (batchId, members) ->
                BatchGroup(
                    batchId = batchId,
                    name = members.firstNotNullOfOrNull { it.batchName?.takeIf(String::isNotBlank) }
                        ?: batchId,
                    members = members,
                    assignedPairId = members.firstOrNull()?.assignedPairId,
                )
            }
    }

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
            // Three equal tiles instead of wrapping buttons (owner report 2026-11-01): the row
            // never breaks 2+1 and each action reads as one card, like a quick-action strip.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickAddTile(
                    icon = Icons.Filled.Apps,
                    label = stringResource(R.string.rules_add_app),
                    onClick = onAddApp,
                    modifier = Modifier.weight(1f),
                )
                QuickAddTile(
                    icon = Icons.Filled.Language,
                    label = stringResource(R.string.rules_add_website),
                    onClick = onAddWebsite,
                    modifier = Modifier.weight(1f),
                )
                QuickAddTile(
                    icon = Icons.Outlined.GridView,
                    label = stringResource(R.string.rules_add_batch),
                    onClick = onAddBatch,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        // Owner directive 2026-10-21: each section renders its own state — the Apps and
        // Websites sections exist even when empty and say what they hold. Deactivated stays
        // hidden when empty: it is a cleanup area derived from the other two, not a third
        // destination, and a "nothing paused" card would only add weight.
        val appRules = rules.filter { it.targetType == "APP" && it.isEnabled && it.batchId == null }
        val websiteRules = rules.filter { it.targetType == "WEBSITE" && it.isEnabled && it.batchId == null }
        val deactivated = rules.filter { !it.isEnabled && it.batchId == null }

        if (batches.isNotEmpty()) {
            sectionHeader(header = batchesHeader, key = "batches")
            itemsIndexed(batches, key = { _, batch -> "batch-${batch.batchId}" }) { _, batch ->
                val enabled = pendingBatchPause[batch.batchId] ?: batch.members.any { it.isEnabled }
                BatchCard(
                    batch = batch,
                    partners = partners,
                    enabled = enabled,
                    awaitingWrite = pendingBatchPause.containsKey(batch.batchId),
                    nowMillis = nowMillis,
                    usageByPackage = usageByPackage,
                    onToggle = { onToggleBatch(batch.batchId, it) },
                    onEdit = { onEditBatch(batch.batchId) },
                    onRemovePartner = { onRemoveBatchPartner(batch.batchId) },
                    onAssignPartner = { onAssignBatchPartner(batch.batchId, it) },
                    onDelete = { onDeleteBatch(batch) },
                )
            }
        }

        sectionHeader(header = appsHeader, key = "apps")
        item(key = "apps-body") {
            if (appRules.isEmpty()) {
                EmptySectionCard(
                    icon = Icons.Filled.Apps,
                    title = stringResource(R.string.rules_apps_empty_title),
                    body = stringResource(R.string.rules_apps_empty_body),
                    actionLabel = stringResource(R.string.rules_apps_empty_action),
                    onAction = onAddApp,
                )
            } else {
                RuleRowsCard(
                    rules = appRules,
                    pendingPause = pendingPause,
                    nowMillis = nowMillis,
                    partners = partners,
                    domainUsageEngine = domainUsageEngine,
                    usageByPackage = usageByPackage,
                    onEdit = onEdit,
                    onTogglePause = onTogglePause,
                )
            }
        }

        if (hiddenAppCount > 0) {
            // The blocks are stored, not gone (owner ruling 2026-11-01): say so quietly so a
            // missing row reads as "app not installed" rather than "my block was lost".
            item(key = "apps-hidden") {
                Text(
                    pluralStringResource(
                        R.plurals.rules_hidden_uninstalled, hiddenAppCount, hiddenAppCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        sectionHeader(
            header = websitesHeader,
            key = "websites",
            // Once rows exist, the empty state's chips are gone — this is the always-there
            // door back into the same reviewed lists.
            trailing = if (websiteRules.isNotEmpty()) {
                {
                    TextButton(onClick = { onOpenPresets(WebsitePresets.categories.first().id) }) {
                        Text(stringResource(R.string.rules_websites_categories))
                    }
                }
            } else {
                null
            },
        )
        item(key = "websites-body") {
            if (websiteRules.isEmpty()) {
                WebsiteEmptySection(onOpenPresets = onOpenPresets, onAddWebsite = onAddWebsite)
            } else {
                RuleRowsCard(
                    rules = websiteRules,
                    pendingPause = pendingPause,
                    nowMillis = nowMillis,
                    partners = partners,
                    domainUsageEngine = domainUsageEngine,
                    usageByPackage = usageByPackage,
                    onEdit = onEdit,
                    onTogglePause = onTogglePause,
                )
            }
        }

        // #18: Deactivated (paused + duration-lapsed) rules leave the live sections — their
        // switches tell non-enforcement there, beside a way to sweep them out.
        ruleSection(
            header = deactivatedHeader,
            caption = deactivatedCaption,
            rules = deactivated,
            pendingPause = pendingPause,
            nowMillis = nowMillis,
            partners = partners,
            domainUsageEngine = domainUsageEngine,
            usageByPackage = usageByPackage,
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

private fun LazyListScope.sectionHeader(
    header: String,
    key: String,
    trailing: (@Composable () -> Unit)? = null,
) {
    item(key = "header-$key") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                header,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            trailing?.invoke()
        }
    }
}

/** A named batch as its Rules-page card shows it (owner mandate 2026-10-31). */
internal data class BatchGroup(
    val batchId: String,
    val name: String,
    val members: List<RestrictionRule>,
    val assignedPairId: String?,
)

private data class PendingBatchDelete(val batchId: String, val name: String, val count: Int)

/**
 * One named batch: everything that goes together stays together — the name the partner sees,
 * its items, its one partner, and the master switch. Members are not loose rows: pausing here
 * is one batch-level event ("Social media was turned off"), not N rule events.
 */
@Composable
private fun BatchCard(
    batch: BatchGroup,
    partners: List<ReachOutTarget>,
    enabled: Boolean,
    awaitingWrite: Boolean,
    nowMillis: Long,
    usageByPackage: Map<String, Long>,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onRemovePartner: () -> Unit,
    onAssignPartner: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var assignPickerOpen by remember { mutableStateOf(false) }
    val supervisor = partners.firstOrNull { it.pairId == batch.assignedPairId }
    val unsupervised = batch.assignedPairId != null && supervisor == null
    val pauseLabel = stringResource(R.string.rules_batch_pause, batch.name)
    val resumeLabel = stringResource(R.string.rules_batch_resume, batch.name)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(
                    Modifier
                        .weight(1f)
                        .clickable(onClick = onEdit),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        batch.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        pluralStringResource(
                            R.plurals.batch_item_count, batch.members.size, batch.members.size,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    when {
                        supervisor != null -> Text(
                            stringResource(R.string.rules_batch_watched_by, supervisor.name),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        unsupervised -> Text(
                            stringResource(R.string.rules_unsupervised),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        partners.isNotEmpty() -> Text(
                            stringResource(R.string.rules_not_shared),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onToggle,
                    enabled = !awaitingWrite,
                    modifier = Modifier.semantics {
                        contentDescription = if (enabled) pauseLabel else resumeLabel
                    },
                )
            }
            LazyColumn(Modifier.heightIn(max = RULES_CARD_MAX_HEIGHT)) {
                itemsIndexed(batch.members, key = { _, member -> member.ruleId }) { index, member ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = APP_MARK_SIZE + 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                    BatchMemberRow(
                        rule = member,
                        nowMillis = nowMillis,
                        usageTodayMs = usageByPackage[member.blockedPackage],
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (batch.assignedPairId != null) {
                    TextButton(onClick = onRemovePartner) {
                        Text(stringResource(R.string.rules_batch_remove_partner))
                    }
                } else if (partners.isNotEmpty()) {
                    TextButton(onClick = { assignPickerOpen = true }) {
                        Text(stringResource(R.string.rules_batch_add_partner))
                    }
                }
                TextButton(onClick = onDelete) {
                    Text(
                        stringResource(R.string.rules_batch_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
    if (assignPickerOpen) {
        AlertDialog(
            onDismissRequest = { assignPickerOpen = false },
            title = { Text(stringResource(R.string.rules_batch_add_partner_title, batch.name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    partners.forEach { partner ->
                        TextButton(
                            onClick = {
                                assignPickerOpen = false
                                onAssignPartner(partner.pairId)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(partner.name, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { assignPickerOpen = false }) {
                    Text(stringResource(R.string.rules_cancel))
                }
            },
        )
    }
}

@Composable
private fun BatchMemberRow(rule: RestrictionRule, nowMillis: Long, usageTodayMs: Long?) {
    val label = rememberAppLabel(rule.blockedPackage)
    val res = LocalResources.current
    val state = remember(rule, nowMillis, res, usageTodayMs) {
        ruleRowState(rule, nowMillis, res, usageTodayMs)
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AppMark(
            packageName = if (rule.targetType == "WEBSITE") null else rule.blockedPackage,
            fallback = if (rule.targetType == "WEBSITE") Icons.Default.Language else Icons.Default.Apps,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (rule.isEnabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                scheduleSummary(rule, res),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            state.label?.let { StatePill(label = it, active = state.active && rule.isEnabled) }
        }
    }
}

/**
 * One section's data card. Lazy for the 2,000-rule cap, bounded because it is an item of the
 * page LazyColumn (task #13 — an unbounded nested LazyColumn is measured at infinity).
 */
@Composable
private fun RuleRowsCard(
    rules: List<RestrictionRule>,
    pendingPause: Map<String, Boolean>,
    nowMillis: Long,
    partners: List<ReachOutTarget>,
    domainUsageEngine: DomainUsageEngine,
    usageByPackage: Map<String, Long>,
    onEdit: (RestrictionRule) -> Unit,
    onTogglePause: (RestrictionRule, Boolean) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
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
                    partners = partners,
                    domainUsageEngine = domainUsageEngine,
                    usageTodayMs = usageByPackage[rule.blockedPackage],
                    onEdit = { onEdit(rule) },
                    onTogglePause = { enable -> onTogglePause(rule, enable) },
                )
            }
        }
    }
}

/** The grammar of every empty section: what this section holds, and the one action that
 *  fills it. */
@Composable
private fun EmptySectionCard(
    icon: ImageVector,
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = onAction, modifier = Modifier.fillMaxWidth()) {
                Text(actionLabel)
            }
        }
    }
}

/** The website section's empty state is the preset entry point (owner request 2026-10-21):
 *  categories first, so nobody has to visit a site to learn its address. */
@Composable
private fun WebsiteEmptySection(
    onOpenPresets: (categoryId: String) -> Unit,
    onAddWebsite: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Language,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        stringResource(R.string.rules_websites_empty_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(R.string.rules_websites_empty_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            CategoryPresetChips(onOpenPresets = onOpenPresets)
            TextButton(onClick = onAddWebsite, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.rules_websites_add_own))
            }
        }
    }
}

/** Loading: the page's own shape as skeleton blocks (UX timing rule: unknown duration with
 *  structure gets a skeleton, not a spinner), with the reading line as the honest caption. */
@Composable
private fun RulesLoading(modifier: Modifier = Modifier) {
    val sidePadding = if (rememberWindowWidth() == WindowWidth.COMPACT) 16.dp else 24.dp
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = sidePadding, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.rules_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.rules_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.rules_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SkeletonCard(lines = 3)
        SkeletonCard(lines = 2)
    }
}

@Composable
private fun SkeletonCard(lines: Int) {
    val bar = MaterialTheme.colorScheme.surfaceContainerHighest
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(lines) { index ->
                Box(
                    Modifier
                        .fillMaxWidth(if (index == 0) 0.55f else 1f)
                        .height(14.dp)
                        .background(bar, RoundedCornerShape(6.dp)),
                )
            }
        }
    }
}

/** Failure: says what happened and why, and offers the one action that can fix it. The
 *  optional back action exists for the editor route, where system back must still leave. */
@Composable
private fun RulesLoadError(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    val sidePadding = if (rememberWindowWidth() == WindowWidth.COMPACT) 16.dp else 24.dp
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = sidePadding, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (onBack != null) {
            BackRow(label = stringResource(R.string.rules_title), onBack = onBack)
        } else {
            Text(stringResource(R.string.rules_title), style = MaterialTheme.typography.headlineSmall)
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(MaterialTheme.colorScheme.errorContainer, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            stringResource(R.string.rules_load_error_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            stringResource(R.string.rules_load_error_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.rules_load_retry))
                }
            }
        }
    }
}

@Composable
private fun QuickAddTile(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun LazyListScope.ruleSection(
    header: String,
    rules: List<RestrictionRule>,
    pendingPause: Map<String, Boolean>,
    nowMillis: Long,
    partners: List<ReachOutTarget>,
    domainUsageEngine: DomainUsageEngine,
    usageByPackage: Map<String, Long>,
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
        RuleRowsCard(
            rules = rules,
            pendingPause = pendingPause,
            nowMillis = nowMillis,
            partners = partners,
            domainUsageEngine = domainUsageEngine,
            usageByPackage = usageByPackage,
            onEdit = onEdit,
            onTogglePause = onTogglePause,
        )
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
    partners: List<ReachOutTarget>,
    domainUsageEngine: DomainUsageEngine,
    /** Today's accounted usage for an app rule; website rules read their own ledger below. */
    usageTodayMs: Long?,
    onEdit: () -> Unit,
    onTogglePause: (Boolean) -> Unit,
) {
    val label = rememberAppLabel(rule.blockedPackage)
    val res = LocalResources.current
    // One usage source per row: the website meter attributes per rule, the app ledger per
    // package — the same figure the engine consults before it blocks.
    val usedTodayMs = if (rule.targetType == "WEBSITE") {
        domainUsageEngine.usageTodayMs(rule.ruleId)
    } else {
        usageTodayMs
    }
    val state = remember(rule, enabled, nowMillis, res, usedTodayMs) {
        ruleRowState(rule, nowMillis, res, usedTodayMs)
    }
    // §2.1 req 1/23: an assignment whose pair is gone reads UNSUPERVISED — the rule stays
    // enforced and visible; the row says who used to watch it is no longer paired.
    val supervisor = partners.firstOrNull { it.pairId == rule.assignedPairId }
    val unsupervised = rule.assignedPairId != null && supervisor == null
    val pauseLabel = stringResource(R.string.rules_pause, label)
    val resumeLabel = stringResource(R.string.rules_resume, label)
    val editLabel = stringResource(R.string.rules_edit)
    // Owner report 2026-11-01: the row used to spend its width on a fixed pill and the switch,
    // squeezing the title and the partner line into ellipses. The switch now owns the only
    // fixed-width slot; status sits under the text where it can wrap.
    val partnerLine: String? = when {
        supervisor != null -> stringResource(R.string.rules_watched_by, supervisor.name)
        unsupervised -> stringResource(R.string.rules_unsupervised)
        // With a partner on the roster, "who sees this?" is answered on every row — the quiet
        // state is what makes an assignment discoverable at all.
        partners.isNotEmpty() -> stringResource(R.string.rules_not_shared)
        else -> null
    }
    val partnerColor = when {
        supervisor != null -> MaterialTheme.colorScheme.primary
        unsupervised -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = editLabel, onClick = onEdit)
            .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // The blocked app's own icon where we can read it — a generic glyph says nothing about
        // which app a row is (same technique as the picker that chose it). An uninstalled
        // package, or a website, falls back to the glyph.
        AppMark(
            packageName = if (rule.targetType == "WEBSITE") null else rule.blockedPackage,
            fallback = if (rule.targetType == "WEBSITE") Icons.Default.Language else Icons.Default.Apps,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
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
            // S9.3 / owner report 2026-11-08: the row's own progress against its limit, for
            // apps and websites alike. Null until the ledger attaches — hiding the line beats
            // claiming a false zero.
            if (rule.dailyLimitMs > 0L && !rule.isAlwaysOn && enabled) {
                usedTodayMs?.let { usedMs ->
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
            if (state.label != null || partnerLine != null) {
                // Stacked, not side by side: the state pill consumed the width the partner
                // line needed and still cut it ("Not shared with a par…", device-caught
                // 2026-11-01). The pill is short and fixed; the sentence gets its own line.
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    state.label?.let { StatePill(label = it, active = state.active && enabled) }
                    partnerLine?.let { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall,
                            color = partnerColor,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
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
