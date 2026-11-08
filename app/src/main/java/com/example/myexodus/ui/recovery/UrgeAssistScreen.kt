package com.example.myexodus.ui.recovery

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myexodus.R
import com.example.myexodus.data.db.entity.CoachingStep
import com.example.myexodus.data.db.entity.RecoverySession
import com.example.myexodus.recovery.CheckInOutcome
import com.example.myexodus.recovery.CrisisChannels
import com.example.myexodus.recovery.CrisisResources
import com.example.myexodus.recovery.RecoveryResourceCatalog
import com.example.myexodus.recovery.RecoverySessionRepository
import com.example.myexodus.recovery.UrgeAction
import com.example.myexodus.recovery.UrgeCatalog
import com.example.myexodus.recovery.UrgeCategory
import com.example.myexodus.recovery.UrgeCheckIn
import com.example.myexodus.recovery.UrgeEpisode
import com.example.myexodus.recovery.UrgeReason
import com.example.myexodus.recovery.UrgeRecommendation
import com.example.myexodus.recovery.UrgeRunner
import com.example.myexodus.recovery.UrgeSelectionEngine
import com.example.myexodus.recovery.UrgeSelectionInput
import com.example.myexodus.recovery.isLateNight
import com.example.myexodus.ui.BackRow
import com.example.myexodus.ui.SecureSurface
import com.example.myexodus.ui.WindowWidth
import com.example.myexodus.ui.rememberWindowWidth
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Urge Assist (plan §E2.2-E2.5): the in-the-moment flow. One suggestion at a time with the
 * reason it was chosen, a check-in afterwards, and an escalation menu that never sends anything
 * on its own. Everything here is local and ungated, and nothing on this screen reads the
 * network.
 *
 * One episode runs from opening the screen to leaving it: "same" and "worse" answers narrow what
 * [UrgeEpisode] will offer next, and every completed round is one session row carrying the
 * category, the action, and the check-in answer.
 */
@Composable
internal fun UrgeAssistScreen(
    sessions: RecoverySessionRepository,
    prefs: RecoveryPrefs,
    planItems: List<CoachingStep>,
    entry: String,
    habitId: String?,
    habitLabel: String?,
    inRiskWindow: Boolean,
    partnerName: String?,
    onReachOut: () -> Unit,
    partnerMessageSent: Boolean,
    partnerMessageStatus: String?,
    onRouteHome: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The parent sets this too; declaring it here keeps the surface protected if it is ever
    // hosted outside the Recovery tab (SecureSurfacesTest enforces one declaration per host).
    SecureSurface()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sidePadding = if (rememberWindowWidth() == WindowWidth.COMPACT) 16.dp else 24.dp
    var categoryId by rememberSaveable { mutableStateOf<String?>(null) }
    val category = UrgeCategory.fromId(categoryId)
    // null = not answered. The gambling question is asked, never inferred from the chip alone.
    var inTransaction by rememberSaveable { mutableStateOf<Boolean?>(null) }
    // Not saveable: the round and its row id die with the process, so a restored RUNNING or
    // CHECK_IN would render an empty runner. A fresh start lands on the suggestion instead.
    var phase by remember { mutableStateOf(AssistPhase.SUGGEST) }
    var episode by remember { mutableStateOf(UrgeEpisode()) }
    var advice by remember { mutableStateOf<UrgeRecommendation?>(null) }
    var round by remember { mutableStateOf<UrgeAction?>(null) }
    var answerPending by remember { mutableStateOf(false) }
    var roundToken by remember { mutableIntStateOf(0) }
    var showAll by remember { mutableStateOf(false) }
    var sessionId by remember { mutableStateOf<String?>(null) }
    var quiet by rememberSaveable { mutableStateOf(prefs.quietPreferred) }
    var messageRequested by remember { mutableStateOf(false) }
    var logging by remember { mutableStateOf(false) }
    var directoryFilter by remember { mutableStateOf(CrisisResources.FILTER_ALL) }
    var directoryReturn by remember { mutableStateOf(AssistPhase.SUGGEST) }
    var dangerReported by remember { mutableStateOf(false) }

    fun selectAdvice() {
        advice = UrgeSelectionEngine.select(
            UrgeSelectionInput(
                category = category,
                lateNight = isLateNight(LocalTime.now().hour),
                inRiskWindow = inRiskWindow,
                canMove = prefs.canMove,
                quietPreferred = quiet,
                hasPartner = partnerName != null,
                inTransaction = inTransaction == true,
                immediateDanger = dangerReported,
                dislikedActions = prefs.dislikedActions,
                excludedActions = episode.excludedActions,
                helpfulActions = prefs.helpfulActions,
                lastModality = episode.lastModality,
            ),
        )
        showAll = false
        phase = if (dangerReported) AssistPhase.EMERGENCY else AssistPhase.SUGGEST
    }

    // A category answer is the only thing that re-ranks an open suggestion. The Quiet chip
    // deliberately does not: it must never swap the action out from under the user.
    LaunchedEffect(categoryId, inTransaction) {
        if (phase == AssistPhase.SUGGEST || phase == AssistPhase.EMERGENCY) selectAdvice()
    }

    fun startRow(action: UrgeAction) {
        val token = ++roundToken
        scope.launch {
            val id = withContext(Dispatchers.IO) {
                sessions.start(
                    technique = RecoverySession.TECHNIQUE_NONE,
                    intensityStart = null,
                    entry = entry,
                    zoneId = ZoneId.systemDefault().id,
                    nowEpochMs = System.currentTimeMillis(),
                    habitId = habitId,
                    category = category?.id,
                    actionId = action.id,
                )
            }
            // The user may have backed out, or started another round, before the insert
            // landed; a stale row must not be left STARTED or steal the newer round's id.
            if (roundToken == token && phase == AssistPhase.RUNNING && round?.id == action.id) {
                sessionId = id
            } else {
                sessions.abandonQuietly(id)
            }
        }
    }

    suspend fun writeRow(action: UrgeAction) = withContext(Dispatchers.IO) {
        val id = sessions.start(
            technique = RecoverySession.TECHNIQUE_NONE,
            intensityStart = null,
            entry = entry,
            zoneId = ZoneId.systemDefault().id,
            nowEpochMs = System.currentTimeMillis(),
            habitId = habitId,
            category = category?.id,
            actionId = action.id,
        )
        sessions.finish(id, RecoverySession.STATE_COMPLETED, null, System.currentTimeMillis())
    }

    fun finishRow(checkIn: String?, next: () -> Unit) {
        val id = sessionId
        sessionId = null
        scope.launch {
            if (id != null) {
                withContext(Dispatchers.IO) {
                    sessions.finish(
                        id,
                        RecoverySession.STATE_COMPLETED,
                        null,
                        System.currentTimeMillis(),
                        checkIn,
                    )
                }
            }
            next()
        }
    }

    fun choose(action: UrgeAction) {
        when (action.runner) {
            UrgeRunner.LOG_ONLY -> logging = true
            UrgeRunner.LINK -> if (action.id == UrgeCatalog.SAFETY_CHECK) {
                dangerReported = true
                selectAdvice()
            } else {
                // Opening a verified list is the whole action: the row completes now, with no
                // check-in, because no technique was practised.
                scope.launch { writeRow(action) }
                directoryFilter =
                    action.directoryFilter ?: CrisisResources.filterForCategory(category)
                directoryReturn = phase
                phase = AssistPhase.DIRECTORY
            }
            else -> {
                round = action
                messageRequested = false
                phase = AssistPhase.RUNNING
                startRow(action)
            }
        }
    }

    fun answerCheckIn(result: UrgeCheckIn) {
        // The phase only moves after the row write, so a double-tap would otherwise advance
        // the episode twice and fire its end twice.
        if (answerPending) return
        val action = round ?: return
        answerPending = true
        if (result == UrgeCheckIn.BETTER) prefs.markHelpful(action.id)
        val transition = episode.checkIn(result, action.id, action.modality)
        episode = transition.episode
        finishRow(result.id) {
            answerPending = false
            when (transition.outcome) {
                CheckInOutcome.EXTEND -> phase = AssistPhase.EXTENDED
                CheckInOutcome.NEW_ACTION -> selectAdvice()
                CheckInOutcome.ESCALATE -> phase = AssistPhase.ESCALATION
                CheckInOutcome.FINISH -> onBack()
            }
        }
    }

    fun assistBack() {
        when (phase) {
            AssistPhase.DIRECTORY -> phase = directoryReturn
            AssistPhase.RUNNING -> {
                sessionId?.let(sessions::abandonQuietly)
                sessionId = null
                selectAdvice()
            }
            // The round is over but unanswered: keep it as completed, not abandoned.
            AssistPhase.CHECK_IN -> finishRow(null) { onBack() }
            AssistPhase.EMERGENCY -> {
                dangerReported = false
                selectAdvice()
            }
            else -> onBack()
        }
    }

    BackHandler(enabled = phase != AssistPhase.SUGGEST) { assistBack() }
    DisposableEffect(Unit) {
        onDispose { sessionId?.let(sessions::abandonQuietly) }
    }

    // A sent message is the check-in trigger for the message runner (plan §E2.4); the row
    // stays STARTED until the answer, so the answer is what lands in the record.
    LaunchedEffect(partnerMessageSent, messageRequested) {
        if (messageRequested && partnerMessageSent && phase == AssistPhase.RUNNING) {
            messageRequested = false
            phase = AssistPhase.CHECK_IN
        }
    }

    if (phase == AssistPhase.DIRECTORY) {
        // The directory scrolls itself; nesting it in this screen's scroll would measure it
        // against an infinite height.
        Column(modifier.fillMaxSize()) {
            BackRow(label = stringResource(R.string.recovery_title), onBack = { assistBack() })
            CrisisScreen(initialFilter = directoryFilter)
        }
    } else {
        val scroll = rememberScrollState()
        LaunchedEffect(phase) { scroll.scrollTo(0) }
        Column(modifier = modifier.fillMaxSize().verticalScroll(scroll)) {
            BackRow(label = stringResource(R.string.recovery_title), onBack = { assistBack() })
            Column(
                modifier = Modifier.padding(horizontal = sidePadding, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.recovery_assist_title),
                        style = MaterialTheme.typography.headlineLarge,
                    )
                    Text(
                        stringResource(R.string.recovery_assist_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                when (phase) {
                    AssistPhase.SUGGEST -> SuggestionPanel(
                        category = category,
                        inTransaction = inTransaction,
                        advice = advice,
                        showAll = showAll,
                        quiet = quiet,
                        partnerName = partnerName,
                        onCategory = { categoryId = it?.id },
                        onTransaction = { inTransaction = it },
                        onQuiet = { quiet = it },
                        onPrimary = ::choose,
                        onCant = {
                            episode = episode.cantDo(advice?.primary?.id ?: return@SuggestionPanel)
                            selectAdvice()
                        },
                        onShowAll = { showAll = it },
                        onReachOut = onReachOut,
                        onGetHelp = {
                            directoryFilter = CrisisResources.filterForCategory(category)
                            directoryReturn = AssistPhase.SUGGEST
                            phase = AssistPhase.DIRECTORY
                        },
                        onDanger = {
                            dangerReported = true
                            selectAdvice()
                        },
                    )
                    AssistPhase.RUNNING -> RunnerPanel(
                        action = round,
                        planItems = planItems,
                        quiet = quiet,
                        hapticEnabled = prefs.hapticPacing,
                        onHapticChange = { prefs.hapticPacing = it },
                        partnerMessageSent = partnerMessageSent,
                        partnerMessageStatus = partnerMessageStatus,
                        onMessage = {
                            messageRequested = true
                            onReachOut()
                        },
                        onDone = { phase = AssistPhase.CHECK_IN },
                        onCant = {
                            val action = round
                            if (action != null) {
                                // Could not do it is an abandoned round, whatever the runner.
                                sessionId?.let(sessions::abandonQuietly)
                                sessionId = null
                                episode = episode.cantDo(action.id)
                                selectAdvice()
                            }
                        },
                        onGetHelp = {
                            directoryFilter = CrisisResources.FILTER_ALL
                            directoryReturn = AssistPhase.RUNNING
                            phase = AssistPhase.DIRECTORY
                        },
                    )
                    AssistPhase.CHECK_IN -> CheckInPanel(onAnswer = ::answerCheckIn)
                    AssistPhase.EXTENDED -> ExtendedPanel(
                        onKeepGoing = { round?.let(::choose) },
                        onFinish = onBack,
                    )
                    AssistPhase.ESCALATION -> EscalationPanel(
                        partnerName = partnerName,
                        onReachOut = onReachOut,
                        onSupportLine = {
                            directoryFilter = CrisisResources.filterForCategory(category)
                            directoryReturn = AssistPhase.ESCALATION
                            phase = AssistPhase.DIRECTORY
                        },
                        onGetHelp = {
                            directoryFilter = CrisisResources.FILTER_ALL
                            directoryReturn = AssistPhase.ESCALATION
                            phase = AssistPhase.DIRECTORY
                        },
                        onStop = onBack,
                        onDanger = {
                            dangerReported = true
                            selectAdvice()
                        },
                    )
                    AssistPhase.EMERGENCY -> EmergencyPanel(
                        onGetHelp = {
                            directoryFilter = CrisisResources.FILTER_ALL
                            directoryReturn = AssistPhase.EMERGENCY
                            phase = AssistPhase.DIRECTORY
                        },
                    )
                    AssistPhase.DIRECTORY -> Unit
                }
            }
        }
    }

    if (logging) {
        UrgeLogSheet(
            onDismiss = { logging = false },
            habitLabel = habitLabel,
            onSubmit = { intensity ->
                // Close first: a sheet still on screen during the write takes a second tap
                // and writes a second row.
                logging = false
                val actionId = advice?.primary?.id ?: UrgeCatalog.LOG_ONLY
                scope.launch {
                    withContext(Dispatchers.IO) {
                        sessions.logOnly(
                            intensity = intensity,
                            entry = entry,
                            zoneId = ZoneId.systemDefault().id,
                            nowEpochMs = System.currentTimeMillis(),
                            habitId = habitId,
                            category = category?.id,
                            actionId = actionId,
                        )
                    }
                    onRouteHome()
                }
            },
        )
    }
}

private enum class AssistPhase { SUGGEST, RUNNING, CHECK_IN, EXTENDED, ESCALATION, DIRECTORY, EMERGENCY }

/**
 * Suggestion (plan §E2.2, revised 2026-11-07): category chips, one leading action with its
 * reason, a one-tap breathing shortcut, and the rest of the ranked catalogue underneath. The
 * first few alternates show inline; the remainder is one tap away rather than hidden entirely.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SuggestionPanel(
    category: UrgeCategory?,
    inTransaction: Boolean?,
    advice: UrgeRecommendation?,
    showAll: Boolean,
    quiet: Boolean,
    partnerName: String?,
    onCategory: (UrgeCategory?) -> Unit,
    onTransaction: (Boolean?) -> Unit,
    onQuiet: (Boolean) -> Unit,
    onPrimary: (UrgeAction) -> Unit,
    onCant: () -> Unit,
    onShowAll: (Boolean) -> Unit,
    onReachOut: () -> Unit,
    onGetHelp: () -> Unit,
    onDanger: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.recovery_assist_question),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            UrgeCategory.entries.forEach { option ->
                FilterChip(
                    selected = category == option,
                    onClick = { onCategory(if (category == option) null else option) },
                    label = { Text(categoryLabel(option)) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
            FilterChip(
                selected = category == null,
                onClick = { onCategory(null) },
                label = { Text(stringResource(R.string.urge_category_unsure)) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }

        // Asked only for betting, and never assumed: "Not sure" leaves the ranking as it was.
        if (category == UrgeCategory.GAMBLING) {
            Text(
                stringResource(R.string.recovery_assist_gambling_question),
                style = MaterialTheme.typography.bodyMedium,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FilterChip(
                    selected = inTransaction == true,
                    onClick = { onTransaction(if (inTransaction == true) null else true) },
                    label = { Text(stringResource(R.string.recovery_assist_yes)) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
                FilterChip(
                    selected = inTransaction == false,
                    onClick = { onTransaction(if (inTransaction == false) null else false) },
                    label = { Text(stringResource(R.string.recovery_assist_no)) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
                FilterChip(
                    selected = inTransaction == null,
                    onClick = { onTransaction(null) },
                    label = { Text(stringResource(R.string.urge_category_unsure)) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }

        val primary = advice?.primary ?: return@Column
        Hairline()
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionEyebrow(stringResource(R.string.recovery_assist_eyebrow))
            Text(
                stringResource(primary.labelRes),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                stringResource(primary.descriptionRes),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                whyLine(advice.reason, category),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(
            onClick = { onPrimary(primary) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(stringResource(primaryLabel(primary)))
        }
        // Paced breathing is the one move that works without thinking, so it stays one tap
        // away even when the ranking put something else first.
        val breathing = advice.alternatives.firstOrNull { it.id == UrgeCatalog.BREATHING }
        if (breathing != null) {
            OutlinedButton(
                onClick = { onPrimary(breathing) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.recovery_assist_breathe_now))
            }
        }
        TextButton(onClick = onCant, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.recovery_assist_cant))
        }
        // A chip, not a preference: it changes this run only, and never the chosen action.
        if (primary.runner == UrgeRunner.TECHNIQUE) {
            FilterChip(
                selected = quiet,
                onClick = { onQuiet(!quiet) },
                label = { Text(stringResource(R.string.recovery_assist_quiet)) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
            Text(
                stringResource(R.string.recovery_assist_quiet_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // The rest of the catalogue, ranked for this category. Four rows inline keep the choice
        // visible; the remainder sits behind one tap instead of disappearing.
        val rest = if (breathing != null) advice.alternatives - breathing else advice.alternatives
        if (rest.isNotEmpty()) {
            Hairline()
            SectionEyebrow(stringResource(R.string.recovery_assist_more_title))
            rest.take(if (showAll) rest.size else INLINE_ALTERNATES).forEach { alternate ->
                AlternateRow(action = alternate, onClick = { onPrimary(alternate) })
            }
            if (rest.size > INLINE_ALTERNATES) {
                TextButton(
                    onClick = { onShowAll(!showAll) },
                    modifier = Modifier.heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 0.dp),
                ) {
                    Text(
                        stringResource(
                            if (showAll) R.string.recovery_assist_show_less
                            else R.string.recovery_assist_show_all,
                            rest.size,
                        ),
                    )
                }
            }
        }

        Hairline()
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (partnerName != null) {
                AssistLink(
                    title = stringResource(R.string.recovery_reach_out_named, partnerName),
                    tone = MaterialTheme.colorScheme.primary,
                    onClick = onReachOut,
                )
            } else {
                Text(
                    stringResource(R.string.recovery_assist_pair_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AssistLink(
                title = stringResource(R.string.recovery_crisis_title),
                tone = MaterialTheme.colorScheme.error,
                onClick = onGetHelp,
            )
            TextButton(
                onClick = onDanger,
                modifier = Modifier.heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 0.dp),
            ) {
                Text(
                    stringResource(R.string.recovery_assist_danger),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** One ranked alternate: title plus its one-line explanation, tappable as a whole row. */
@Composable
private fun AlternateRow(action: UrgeAction, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                stringResource(action.labelRes),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(action.descriptionRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

private const val INLINE_ALTERNATES = 4

/**
 * The action host (plan §E2.3). One runner at a time: the existing technique runners, a
 * one-sentence step, the existing partner message dialog, or the verified directory. An unsent
 * message never hides the way to help.
 */
@Composable
private fun RunnerPanel(
    action: UrgeAction?,
    planItems: List<CoachingStep>,
    quiet: Boolean,
    hapticEnabled: Boolean,
    onHapticChange: (Boolean) -> Unit,
    partnerMessageSent: Boolean,
    partnerMessageStatus: String?,
    onMessage: () -> Unit,
    onDone: () -> Unit,
    onCant: () -> Unit,
    onGetHelp: () -> Unit,
) {
    val current = action ?: return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(current.labelRes),
            style = MaterialTheme.typography.headlineSmall,
        )
        when (current.runner) {
            UrgeRunner.TECHNIQUE -> {
                val technique = current.techniqueId?.let { RecoveryResourceCatalog.byId(it) }
                if (technique != null) {
                    TechniqueRunner(
                        technique = technique,
                        planItems = planItems,
                        hapticEnabled = hapticEnabled && !quiet,
                        onHapticChange = onHapticChange,
                        onCompleted = onDone,
                        onStop = onCant,
                    )
                } else {
                    StepBody(current, planItems, onDone)
                }
            }
            UrgeRunner.STEP -> StepBody(current, planItems, onDone)
            UrgeRunner.MESSAGE -> {
                Text(
                    stringResource(current.descriptionRes),
                    style = MaterialTheme.typography.bodyMedium,
                )
                partnerMessageStatus?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Button(
                    onClick = onMessage,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.recovery_assist_message))
                }
                if (!partnerMessageSent) {
                    TextButton(onClick = onGetHelp, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.recovery_crisis_title))
                    }
                }
            }
            UrgeRunner.LINK, UrgeRunner.LOG_ONLY -> Unit
        }
    }
}

@Composable
private fun StepBody(
    action: UrgeAction,
    planItems: List<CoachingStep>,
    onDone: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(action.descriptionRes),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (action.showsPlanItems && planItems.isNotEmpty()) {
            PlanItems(items = planItems, activeWindowId = null, onAdd = {})
        }
        Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.setup_done))
        }
    }
}

/** Check-in (plan §E2.4): four one-tap answers. None of them is worded as a failure. */
@Composable
private fun CheckInPanel(onAnswer: (UrgeCheckIn) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Hairline()
        Text(
            stringResource(R.string.recovery_assist_check_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        listOf(
            UrgeCheckIn.BETTER to R.string.recovery_assist_check_better,
            UrgeCheckIn.SAME to R.string.recovery_assist_check_same,
            UrgeCheckIn.WORSE to R.string.recovery_assist_check_worse,
        ).forEach { (result, label) ->
            OutlinedButton(
                onClick = { onAnswer(result) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(stringResource(label))
            }
        }
        TextButton(
            onClick = { onAnswer(UrgeCheckIn.SKIPPED) },
            modifier = Modifier.heightIn(min = 48.dp),
            contentPadding = PaddingValues(horizontal = 0.dp),
        ) {
            Text(stringResource(R.string.recovery_assist_check_skip))
        }
    }
}

/** The single extension a "better" answer earns (plan §E2.4). */
@Composable
private fun ExtendedPanel(onKeepGoing: () -> Unit, onFinish: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Hairline()
        Text(
            stringResource(R.string.recovery_assist_extend_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Button(
            onClick = onKeepGoing,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.recovery_assist_keep_going))
        }
        OutlinedButton(
            onClick = onFinish,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.recovery_assist_finish))
        }
    }
}

/**
 * Escalation (plan §E2.5): every option at once, a partner only when one is paired, and no
 * automatic notification of anyone. "Stop here" is a real answer, not a failure.
 */
@Composable
private fun EscalationPanel(
    partnerName: String?,
    onReachOut: () -> Unit,
    onSupportLine: () -> Unit,
    onGetHelp: () -> Unit,
    onStop: () -> Unit,
    onDanger: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Hairline()
        Text(
            stringResource(R.string.recovery_assist_escalation_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        if (partnerName != null) {
            AssistLink(
                title = stringResource(R.string.recovery_reach_out_named, partnerName),
                tone = MaterialTheme.colorScheme.primary,
                onClick = onReachOut,
            )
        }
        AssistLink(
            title = stringResource(R.string.recovery_assist_escalation_line),
            tone = MaterialTheme.colorScheme.primary,
            onClick = onSupportLine,
        )
        AssistLink(
            title = stringResource(R.string.recovery_crisis_title),
            tone = MaterialTheme.colorScheme.error,
            onClick = onGetHelp,
        )
        TextButton(
            onClick = onDanger,
            modifier = Modifier.heightIn(min = 48.dp),
            contentPadding = PaddingValues(horizontal = 0.dp),
        ) {
            Text(
                stringResource(R.string.recovery_assist_danger),
                color = MaterialTheme.colorScheme.error,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        TextButton(
            onClick = onStop,
            modifier = Modifier.heightIn(min = 48.dp),
            contentPadding = PaddingValues(horizontal = 0.dp),
        ) {
            Text(stringResource(R.string.recovery_assist_escalation_stop))
        }
    }
}

/**
 * Immediate danger (plan §E2.5): verified emergency lines first, before any technique or
 * suggestion. The numbers and names come from the directory asset, never from copy.
 */
@Composable
private fun EmergencyPanel(onGetHelp: () -> Unit) {
    val context = LocalContext.current
    val lines = remember {
        CrisisResources.forRegion(
            CrisisResources.parse(
                runCatching {
                    context.assets.open(ASSET).bufferedReader().use { it.readText() }
                }.getOrDefault(""),
            ),
            Locale.getDefault().country,
        )
            .filter { it.phone.isNotBlank() }
            .sortedBy { if (CrisisResources.matches(it, CrisisResources.FILTER_URGENT)) 0 else 1 }
            .take(2)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Hairline()
        Text(
            stringResource(R.string.recovery_assist_danger_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            stringResource(R.string.recovery_assist_danger_body),
            style = MaterialTheme.typography.bodyMedium,
        )
        lines.forEach { line ->
            Button(
                onClick = {
                    openUri(context, Intent.ACTION_DIAL, CrisisChannels.telUri(line.phone))
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.recovery_crisis_call, line.phone))
            }
            Text(
                line.name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        AssistLink(
            title = stringResource(R.string.recovery_crisis_title),
            tone = MaterialTheme.colorScheme.error,
            onClick = onGetHelp,
        )
    }
}

/** A hairline row with a chevron; tone distinguishes reaching out from emergency lines. */
@Composable
private fun AssistLink(title: String, tone: Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = tone,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Shared with History, where the optional category is a detail on the assist row. */
@Composable
internal fun categoryLabel(category: UrgeCategory): String = stringResource(
    when (category) {
        UrgeCategory.PORN -> R.string.urge_category_porn
        UrgeCategory.GAMBLING -> R.string.urge_category_gambling
        UrgeCategory.SCROLLING -> R.string.urge_category_scrolling
        UrgeCategory.SHOPPING -> R.string.urge_category_shopping
        UrgeCategory.GAMING -> R.string.urge_category_gaming
        UrgeCategory.OTHER -> R.string.urge_category_other
    },
)

@Composable
private fun whyLine(reason: UrgeReason, category: UrgeCategory?): String = when (reason) {
    UrgeReason.SAFETY -> stringResource(R.string.urge_reason_safety)
    UrgeReason.GAMBLING_TRANSACTION -> stringResource(R.string.urge_reason_gambling_transaction)
    UrgeReason.HELPFUL -> stringResource(R.string.urge_reason_helpful)
    UrgeReason.LATE_NIGHT -> stringResource(R.string.urge_reason_late_night)
    UrgeReason.RISK_WINDOW -> stringResource(R.string.urge_reason_risk_window)
    UrgeReason.CATEGORY -> stringResource(
        R.string.urge_reason_category,
        category?.let { categoryLabel(it) } ?: stringResource(R.string.urge_category_unsure),
    )
    UrgeReason.GENERIC -> stringResource(R.string.urge_reason_generic)
}

@Composable
private fun primaryLabel(action: UrgeAction): Int = when (action.runner) {
    UrgeRunner.TECHNIQUE, UrgeRunner.STEP -> R.string.recovery_assist_start
    UrgeRunner.MESSAGE -> R.string.recovery_assist_message
    UrgeRunner.LOG_ONLY -> R.string.recovery_assist_log_it
    UrgeRunner.LINK ->
        if (action.id == UrgeCatalog.SAFETY_CHECK) R.string.recovery_assist_call
        else R.string.recovery_assist_open
}
