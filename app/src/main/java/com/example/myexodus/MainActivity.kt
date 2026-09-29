package com.example.myexodus

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.example.myexodus.blocking.AttemptRecorder
import com.example.myexodus.blocking.EnhancedModeHealthMonitor
import com.example.myexodus.blocking.EnforcementForegroundService
import com.example.myexodus.blocking.ProtectionStatusMonitor
import com.example.myexodus.blocking.ProtectionStatusStore
import com.example.myexodus.blocking.RulesRepository
import com.example.myexodus.accountability.BilateralDeletion
import com.example.myexodus.accountability.activity.PartnerActivityJournal
import com.example.myexodus.accountability.approval.ApprovalFlow
import com.example.myexodus.accountability.crypto.AccountabilityKeyStore
import com.example.myexodus.accountability.crypto.AccountabilitySigner
import com.example.myexodus.accountability.crypto.BiometricEnrollmentMonitor
import com.example.myexodus.accountability.crypto.IdentityMigration
import com.example.myexodus.accountability.crypto.NoSignatureReason
import com.example.myexodus.accountability.crypto.SigningPath
import com.example.myexodus.accountability.crypto.chooseSigningPath
import com.example.myexodus.accountability.enrollment.EnrollmentDao
import com.example.myexodus.accountability.enrollment.PairingCeremonyTelemetry
import com.example.myexodus.accountability.enrollment.PairingFlow
import com.example.myexodus.accountability.enrollment.PartnerLabel
import com.example.myexodus.accountability.serialization.EventPayload
import com.example.myexodus.accountability.sync.PairUidResolver
import com.example.myexodus.accountability.sync.PartnerSyncCoordinator
import com.example.myexodus.accountability.sync.PartnerRolloutGate
import com.example.myexodus.accountability.sync.PushInstallationRegistrar
import com.example.myexodus.accountability.sync.runPartnerSyncPass
import com.example.myexodus.R
import com.example.myexodus.blocking.recovery.RecoveryStateStore
import com.example.myexodus.recovery.RecoveryPlanRepository
import com.example.myexodus.recovery.RecoverySessionRepository
import com.example.myexodus.ui.recovery.RecoveryPrefs
import com.example.myexodus.blocking.usage.AccountingOutageSource
import com.example.myexodus.blocking.usage.PlatformUsageReader
import com.example.myexodus.blocking.usage.UsageAccountingEngine
import com.example.myexodus.domainusage.DomainUsageEngine
import com.example.myexodus.domainusage.PublicSuffixList
import com.example.myexodus.data.db.AppRulesStore
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.DatabaseHealth
import com.example.myexodus.data.db.DatabaseHealthStore
import com.example.myexodus.data.db.RecoveryManager
import com.example.myexodus.settings.biometricEnrollSettingsIntent
import com.example.myexodus.settings.EnhancedModeSettings
import com.example.myexodus.settings.SetupStateStore
import com.example.myexodus.settings.notificationSettingsIntent
import com.example.myexodus.settings.StrictModeManager
import com.example.myexodus.settings.WidgetSettings
import com.example.myexodus.settings.readProtectionChecks
import com.example.myexodus.telemetry.CrashReporting
import com.example.myexodus.notifications.NotificationAccess
import com.example.myexodus.ui.AppShell
import com.example.myexodus.ui.NavKeys
import com.example.myexodus.ui.SecureSurfaces
import com.example.myexodus.ui.partner.PairingCeremonyDeps
import com.example.myexodus.ui.partner.PartnerDashboardSource
import com.example.myexodus.ui.partner.PartnerSelection
import com.example.myexodus.ui.partner.UnpairOutcome
import com.example.myexodus.widget.ProtectionSnapshotWriter
import com.example.myexodus.ui.setup.SetupScreen
import com.example.myexodus.ui.streaks.StreaksViewModel
import com.example.myexodus.ui.theme.MyExodusTheme
import dagger.hilt.android.AndroidEntryPoint
import java.security.Signature
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    /** Interception Escape Hatch target: the block overlays deep-link straight to the
     *  Recovery dashboard (one tap from the block surface). */
    private var requestedTab by mutableStateOf<String?>(null)

    /** Milestone notifications deep-link the habit they celebrate (multi-habit): the shell
     *  selects it before showing Home so the splash card belongs to the right habit. */
    private var requestedHabit by mutableStateOf<String?>(null)

    /** Which surface sent the user to the Recovery tab (block screen vs. widget). Labels the
     *  session rows the visit creates (v3 W7 `RecoverySession.entry`). */
    private var requestedRecoveryEntry by mutableStateOf<String?>(null)

    /** §5.20/§12.3 (ADR #112): set when no signing prompt could produce a signature —
     *  biometrics unusable with no screen-lock fallback, or the fallback key refusing.
     *  Drives the explicit-message dialog — a promptless device must never read as a
     *  silent cancel. */
    private var biometricUnavailable by mutableStateOf<NoSignatureReason?>(null)

    /** §8.2/§9: an enrollment change or a dead-key signature attempt reset the device
     *  identity; without this dialog the severed partner connections would read as a silent
     *  disappearance. */
    private var identityResetNotice by mutableStateOf(false)

    /** Single-flight guard: the resume check and a sign-time detection can race. */
    @Volatile private var identityMigrationInFlight = false

    /** Bridges the screen-lock confirm flow's RESULT_OK back into the signing coroutine. */
    private var credentialContinuation: CancellableContinuation<Boolean>? = null

    /** Registered at construction — ComponentActivity rejects registration after STARTED. */
    private val credentialLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            credentialContinuation?.let { cont ->
                credentialContinuation = null
                if (cont.isActive) cont.resume(result.resultCode == RESULT_OK)
            }
        }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedTab = intent.getStringExtra(EXTRA_START_TAB)
        requestedHabit = intent.getStringExtra(EXTRA_START_HABIT)
        requestedRecoveryEntry = intent.getStringExtra(EXTRA_RECOVERY_ENTRY)
    }

    @Inject lateinit var rulesStoreFactory: AppRulesStoreFactory
    @Inject lateinit var rulesRepository: RulesRepository
    @Inject lateinit var usageAccountingEngine: UsageAccountingEngine
    // OPD #28(a): the platform's own foreground number, read at the rule save/enable seams.
    @Inject lateinit var platformUsageReader: PlatformUsageReader
    /** §8.7/D1: the ledger-derived interruption report feeding the notification, Home banner,
     *  and the Protection pane's exemption offer. */
    @Inject lateinit var accountingOutageSource: AccountingOutageSource
    // S9: the website-limits engine (status banner + "Used today" rows) and the PSL the
    // RuleEditor's save-time normalization needs.
    @Inject lateinit var domainUsageEngine: DomainUsageEngine
    @Inject lateinit var publicSuffixList: PublicSuffixList
    @Inject lateinit var recoveryStore: RecoveryStateStore
    @Inject lateinit var attemptRecorder: AttemptRecorder
    @Inject lateinit var recoveryManager: RecoveryManager
    @Inject lateinit var healthStore: DatabaseHealthStore
    @Inject lateinit var monitor: EnhancedModeHealthMonitor
    @Inject lateinit var strictMode: StrictModeManager
    @Inject lateinit var settings: EnhancedModeSettings
    @Inject lateinit var protectionStatus: ProtectionStatusStore
    @Inject lateinit var protectionStatusMonitor: ProtectionStatusMonitor
    @Inject lateinit var crashReporting: CrashReporting
    @Inject lateinit var setupStateStore: SetupStateStore
    @Inject lateinit var widgetSettings: WidgetSettings
    @Inject lateinit var partnerDashboardSource: PartnerDashboardSource
    // §19.4 item 4: the production pairing protocol behind the ceremony screen, and the §19.5
    // supported-user pick the dashboard and ceremony share.
    @Inject lateinit var pairingFlow: PairingFlow
    @Inject lateinit var partnerSelection: PartnerSelection
    // Owner report 2026-09-28: legacy pairs carry no exchanged label — the roster's local caption
    // write goes straight through the enrollment store.
    @Inject lateinit var enrollmentDao: EnrollmentDao
    // Step 13: approve/reject signs a chain event — the decision itself is the biometric prompt.
    @Inject lateinit var approvalFlow: ApprovalFlow
    // Step 14: the unpair protocol (journal → deliver → shred) plus the uid resolution its
    // mailbox write needs (uid is deliberately never persisted on-device).
    @Inject lateinit var bilateralDeletion: BilateralDeletion
    @Inject lateinit var pairUidResolver: PairUidResolver
    @Inject lateinit var accountabilityKeyStore: AccountabilityKeyStore
    // §8.2: enrollment-change polling the Keystore cannot provide for a time-bound key, and
    // the §7.6 migration it triggers (rotation is revocation). The signer instance gates the
    // §9 permanent-invalidation seam at both signing paths.
    @Inject lateinit var biometricEnrollmentMonitor: BiometricEnrollmentMonitor
    @Inject lateinit var identityMigration: IdentityMigration
    @Inject lateinit var accountabilitySigner: AccountabilitySigner
    // The dashboard's "Check now" runs the production sync pass in-process — WorkManager
    // dispatch is never performed on XOS (ADR #27), so a WM-based button would be dead there.
    @Inject lateinit var partnerSyncCoordinator: PartnerSyncCoordinator
    @Inject lateinit var pushInstallationRegistrar: PushInstallationRegistrar
    @Inject lateinit var partnerRolloutGate: PartnerRolloutGate
    // Already constructed by MyExodusApp at process start; injected here only to re-publish the
    // snapshot the moment the discreet/detailed toggle flips (widget spec §5 Decision 10).
    @Inject lateinit var widgetSnapshotWriter: ProtectionSnapshotWriter
    // Recovery-resource feature (v3 W1/W2/W7): the plan items shown during a technique, the
    // session log, and the haptic-pacing preference.
    @Inject lateinit var recoveryPlanRepository: RecoveryPlanRepository
    @Inject lateinit var recoverySessions: RecoverySessionRepository
    @Inject lateinit var recoveryPrefs: RecoveryPrefs

    private val streaksViewModel: StreaksViewModel by viewModels()

    /** Plan §8 Step 5 / Decision 9: the foreground evaluation trigger lives here, at the
     *  Activity lifecycle boundary — never inside composition. The ViewModel's own `init`
     *  covers the first launch. */
    override fun onResume() {
        super.onResume()
        streaksViewModel.refresh()
        // §8.2: enrollment changes cannot invalidate the key itself (time-bound keys sit
        // outside setInvalidatedByBiometricEnrollment's scope — android-36 javadoc), so the
        // poll runs here: startup and every ON_RESUME.
        lifecycleScope.launch { checkIdentityEnrollment() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedTab = intent.getStringExtra(EXTRA_START_TAB)
        requestedHabit = intent.getStringExtra(EXTRA_START_HABIT)
        requestedRecoveryEntry = intent.getStringExtra(EXTRA_RECOVERY_ENTRY)
        enableEdgeToEdge()
        setContent {
            MyExodusTheme {
                // §8.2: permission/degradation state is re-checked on every resume of
                // MainActivity — the banner never relies on a stale read.
                var degradation by remember { mutableStateOf(protectionStatus.degradation) }
                // Finding #13: the status chip and the degradation banner must read the same
                // authority in the same frame — the chip's own tri-state comes from here.
                val authority by protectionStatusMonitor.authority.collectAsStateWithLifecycle()
                // §9 row 468 / acceptance line 631: the partner dashboard's banner is a
                // persistent, live state — re-checked on every resume, never a stale read.
                var notificationsEnabled by remember {
                    mutableStateOf(NotificationAccess.canPost(this@MainActivity))
                }
                // §8 Step 16 flow: rationale (the banner body) → request → denied → settings.
                // Whether the request can still raise the system dialog is session-scoped state
                // (the setup wizard's identical pattern): once asked, the action becomes the
                // settings deep link, since a repeat request would be a silent no-op.
                var notificationsRequested by rememberSaveable { mutableStateOf(false) }
                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { _ ->
                    notificationsRequested = true
                    notificationsEnabled = NotificationAccess.canPost(this@MainActivity)
                }
                val canRequestNotifications =
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        !notificationsRequested &&
                        ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.POST_NOTIFICATIONS,
                        ) != PackageManager.PERMISSION_GRANTED
                DisposableEffect(Unit) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            refreshProtectionChecks()
                            degradation = protectionStatus.degradation
                            notificationsEnabled = NotificationAccess.canPost(this@MainActivity)
                        }
                    }
                    lifecycle.addObserver(observer)
                    onDispose { lifecycle.removeObserver(observer) }
                }
                // W6/INV-11: any composed secure surface turns on FLAG_SECURE for the shared
                // window (the recovery tab, session history, coaching editor each declare it).
                val secureSurfaces by SecureSurfaces.composed.collectAsStateWithLifecycle()
                LaunchedEffect(secureSurfaces > 0) {
                    val secure = secureSurfaces > 0
                    if (secure) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }
                    // API 33+: the recents thumbnail bypasses FLAG_SECURE on some OEMs.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        setRecentsScreenshotEnabled(!secure)
                    }
                }
                // The shell's store handle resolves OFF Main and is re-resolved per health
                // transition: open() is a synchronous SQLCipher open (key derivation +
                // migrations + schema prime) that blocks seconds behind another opener's
                // lock — on Main during composition that is an ANR (device-caught 2026-09-19,
                // ADR #63). Health-keyed re-resolution also hands the shell a fresh handle
                // after a user-confirmed reset or a successful retry instead of the stale
                // (closed) instance it cached before.
                var rulesStore by remember { mutableStateOf<AppRulesStore?>(null) }
                val health = healthStore.health.collectAsStateWithLifecycle().value
                // EncryptionUpgradePending is deliberately usable (plan §8 Step 0: pending
                // conversion must not block the app — it runs on the plaintext store meanwhile).
                val usable = health == DatabaseHealth.Ready ||
                    health == DatabaseHealth.EncryptionUpgradePending
                LaunchedEffect(health) {
                    if (usable) rulesStore = withContext(Dispatchers.IO) { rulesStoreFactory.open() }
                }
                // V1 ships no onboarding; rules FK a profile row, so the single local profile
                // is created on first launch (idempotent — see RulesRepository).
                LaunchedEffect(health) {
                    if (usable) {
                        runCatching { withContext(Dispatchers.IO) { rulesRepository.ensureActiveProfile() } }
                            .onFailure { Log.w(TAG, "Profile bootstrap failed", it) }
                    }
                }
                // Plan §8 Step 7 gate: the wizard runs once, only while a required mechanism
                // is missing. Evaluated-and-complete is recorded so a later revocation never
                // re-opens onboarding — Settings rows and the degraded banner own those fixes.
                var showSetup by remember { mutableStateOf<Boolean?>(null) }
                LaunchedEffect(Unit) {
                    showSetup = withContext(Dispatchers.IO) {
                        if (setupStateStore.setupSeen) {
                            false
                        } else {
                            val checks = readProtectionChecks(this@MainActivity)
                            val needed = !checks.usageAccess || !checks.overlayGranted ||
                                !monitor.isServiceEnabled()
                            if (!needed) setupStateStore.setupSeen = true
                            needed
                        }
                    }
                }
                // §19.4 item 4: what the ceremony needs from the Activity — the protocol, the
                // uid (anonymous sign-in), the device's self-chosen name, and the biometric
                // prompt. Built once: none of these change for the Activity's lifetime.
                val ceremonyDeps = remember {
                    PairingCeremonyDeps(
                        flow = pairingFlow,
                        myUid = { pairUidResolver.ensureUid() },
                        // The placeholder profile name is not a name: it means the owner never
                        // chose one, so the field starts empty and no label is exchanged.
                        loadName = {
                            rulesRepository.ensureActiveProfile().name
                                .takeIf { it != RulesRepository.DEFAULT_PROFILE_NAME }
                        },
                        // Blank clears the local name back to the placeholder — the label on
                        // the wire is sanitized in the flow (blank -> null -> the partner
                        // falls back to the key fragment, never to a fabricated name).
                        saveName = { raw ->
                            rulesRepository.updateProfileName(
                                PartnerLabel.sanitize(raw) ?: RulesRepository.DEFAULT_PROFILE_NAME,
                            )
                        },
                        sign = { title -> signWithAuthPrompt(title) },
                        // §14 bullet 1 (task #37): one outcome event per ceremony terminal,
                        // on the same Crashlytics rail as every other §14 client metric.
                        telemetry = PairingCeremonyTelemetry { event -> crashReporting.nonFatal(event) },
                    )
                }
                // Local read: a delegated property cannot be smart-cast, and AppShell now takes
                // the store non-null — it is only ever composed once the store is open.
                val openStore = rulesStore
                if (usable && showSetup == true) {
                    SetupScreen(
                        monitor = monitor,
                        onFinish = {
                            setupStateStore.setupSeen = true
                            showSetup = false
                            // Plan §8 Step 7 ends the wizard by starting enforcement while
                            // the app is foregrounded.
                            refreshProtectionChecks()
                        },
                    )
                } else if (usable && openStore != null) {
                    AppShell(
                        store = openStore,
                        encryptionPending = health == DatabaseHealth.EncryptionUpgradePending,
                        repository = rulesRepository,
                        usageAccountingEngine = usageAccountingEngine,
                        platformUsage = platformUsageReader,
                        domainUsageEngine = domainUsageEngine,
                        psl = publicSuffixList,
                        accountingOutage = accountingOutageSource,
                        recoveryStore = recoveryStore,
                        attempts = attemptRecorder,
                        monitor = monitor,
                        strictMode = strictMode,
                        settings = settings,
                        crashReporting = crashReporting,
                        widgetSettings = widgetSettings,
                        onWidgetSettingsChanged = widgetSnapshotWriter::publish,
                        degradation = degradation,
                        authority = authority,
                        streaks = streaksViewModel,
                        partnerSource = partnerDashboardSource,
                        partnerSelection = partnerSelection,
                        ceremonyDeps = ceremonyDeps,
                        partnerNotificationsEnabled = notificationsEnabled,
                        partnerNotificationsRequestable = canRequestNotifications,
                        onFixNotifications = {
                            if (canRequestNotifications) {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                startActivity(notificationSettingsIntent(this@MainActivity))
                            }
                        },
                        onPartnerCheckNow = {
                            isOnline() && runCatching {
                                runPartnerSyncPass(
                                    pushInstallationRegistrar,
                                    partnerRolloutGate,
                                    partnerSyncCoordinator,
                                )
                            }.isSuccess
                        },
                        onRespondToApproval = { requestEventId, approve ->
                            respondToApproval(requestEventId, approve)
                        },
                        onUnpair = { pairId -> endPartnership(pairId) },
                        onDismissUnpair = { pairId -> bilateralDeletion.dismiss(pairId) },
                        onRenameUser = { pairId, label ->
                            enrollmentDao.updatePartnerLabel(pairId, PartnerLabel.sanitize(label))
                        },
                        // Req 15: journal-first, same as every other shared event — the message
                        // is written locally now and leaves on the next sync pass, so this call
                        // touches no network and needs no signing window. Addressed to the
                        // selected partner (#96): the row carries targetPairId, so no other
                        // supported user's chain can ever be handed this message.
                        onSendMessage = { pairId, text ->
                            val store = rulesStoreFactory.open()
                            val profileId = store?.userProfileDao()?.getActive()?.userId
                            profileId != null && PartnerActivityJournal.emitForProfile(
                                store,
                                profileId,
                                EventPayload.TYPE_MOTIVATIONAL_MESSAGE,
                                occurredAtMs = System.currentTimeMillis(),
                                text = text,
                                targetPairId = pairId,
                            )
                        },
                        requestedTab = requestedTab,
                        onTabConsumed = { requestedTab = null },
                        requestedHabit = requestedHabit,
                        onHabitConsumed = { requestedHabit = null },
                        planRepository = recoveryPlanRepository,
                        sessions = recoverySessions,
                        prefs = recoveryPrefs,
                        recoveryEntry = requestedRecoveryEntry,
                        onRecoveryEntryConsumed = { requestedRecoveryEntry = null },
                        // Req 10: logging an urge keeps the restriction and hands the device
                        // back to the user's home screen. OEM home intents are the known trap,
                        // so a resolver miss is logged rather than crashing the flow.
                        onRouteHome = {
                            runCatching {
                                startActivity(
                                    Intent(Intent.ACTION_MAIN)
                                        .addCategory(Intent.CATEGORY_HOME)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }.onFailure { Log.w(TAG, "Route-home intent unresolved", it) }
                        },
                        onReenable = {
                            protectionStatus.reenable()
                            // Recompute right away: clearing APP_STOPPED can immediately
                            // reveal another degraded reason (or none).
                            protectionStatusMonitor.refresh()
                            degradation = protectionStatus.degradation
                        },
                    )
                } else {
                    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                        DatabaseHealthScreen(
                            health = health,
                            onDiscardCorrupt = {
                                lifecycleScope.launch(Dispatchers.IO) { recoveryManager.discardCorruptFiles() }
                            },
                            onReset = {
                                // Drop the handle first: the reset closes the old database,
                                // and the health-keyed effect above re-resolves the new one.
                                rulesStore = null
                                lifecycleScope.launch(Dispatchers.IO) { rulesStoreFactory.resetAndReopen() }
                            },
                            onRetry = {
                                lifecycleScope.launch(Dispatchers.IO) { rulesStoreFactory.open() }
                            },
                            modifier = Modifier.padding(innerPadding),
                        )
                    }
                }
                // §5.20/§12.3 (ADR #112): the explicit message when no signing prompt could
                // produce a signature — biometrics unusable with no screen-lock fallback, or
                // the fallback refused by a pre-ruling key. The enrollable cases get the one
                // real fix. The signing call already returned null, so the underlying action
                // reads as "nothing happened" and the screen stays exactly where it was.
                biometricUnavailable?.let { reason ->
                    BiometricUnavailableDialog(
                        reason = reason,
                        onOpenSettings = {
                            biometricUnavailable = null
                            biometricEnrollSettingsIntent(this@MainActivity)
                        },
                        onDismiss = { biometricUnavailable = null },
                    )
                }
                // §8.2/§9: the identity reset explanation — why the partner connections
                // vanished (or why the tapped approval did nothing).
                if (identityResetNotice) {
                    IdentityResetDialog(onDismiss = { identityResetNotice = false })
                }
            }
        }
    }

    /**
     * Step 13 responder decision: biometric-gated signing, then the CAS-guarded respond.
     * False (refused/cancelled) leaves the request open — the screen reloads and re-renders
     * the same pending row, which is the honest state.
     */
    private suspend fun respondToApproval(requestEventId: String, approve: Boolean): Boolean {
        val title = getString(
            if (approve) R.string.partner_approval_prompt_approve else R.string.partner_approval_prompt_reject,
        )
        val signature = signWithAuthPrompt(title) ?: return false
        return when (approvalFlow.respond(requestEventId, approve, signature)) {
            is ApprovalFlow.RespondResult.Decided -> true
            is ApprovalFlow.RespondResult.Refused -> false
        }
    }

    /**
     * Step 14 initiator: resolve the pair's uids, biometric-gate the signed deletion request,
     * then run [BilateralDeletion.initiate] — journal, deliver, shred. A cancelled prompt or
     * refused signature aborts before anything is journaled, so the pair stays fully intact
     * and the screen reports the cancel as nothing having happened, not as a failure.
     *
     * An unresolvable identity (ADR #114, §9 "partner unreachable": the pair document is gone
     * or unreadable by this account) no longer traps the user in the pair: the request can
     * never be delivered without a partner uid, but the local shred is unconditional — the
     * attempt proceeds with both uids null and rides the 7-day timeout to an honest
     * LOCAL_ONLY_TERMINATED report.
     */
    private suspend fun endPartnership(pairId: String): UnpairOutcome {
        val identities = pairUidResolver.resolve(pairId)
        if (identities == null) {
            Log.w(TAG, "pair $pairId: partner identity unresolved — local-only unpair")
        }
        val signature = signWithAuthPrompt(getString(R.string.partner_unpair_action))
            ?: return UnpairOutcome.CANCELLED
        return when (
            bilateralDeletion.initiate(
                pairId,
                identities?.first,
                identities?.second,
                signature,
            )
        ) {
            is BilateralDeletion.DeletionResult.Deleted -> UnpairOutcome.UNPAIRED
            is BilateralDeletion.DeletionResult.Failed -> UnpairOutcome.FAILED
        }
    }

    /**
     * Signing gate with the owner ruling 2026-09-28 (ADR #112): a working strong-biometric
     * prompt is used whenever it can run; otherwise a secure screen lock
     * (PIN/pattern/password) authorizes the key through the platform's confirm-credential
     * flow. The explicit-message dialog shows only when neither can produce a signature.
     */
    private suspend fun signWithAuthPrompt(title: String): Signature? {
        val status = BiometricManager.from(this)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        val deviceCredentialPossible =
            getSystemService(KeyguardManager::class.java)?.isDeviceSecure() == true
        return when (val path = chooseSigningPath(
            biometricsAvailable = status == BiometricManager.BIOMETRIC_SUCCESS,
            biometricsNoneEnrolled = status == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
            deviceCredentialPossible = deviceCredentialPossible,
        )) {
            is SigningPath.Blocked -> {
                Log.w(TAG, "no signing prompt possible (${path.reason}) — explicit message")
                withContext(Dispatchers.Main.immediate) { biometricUnavailable = path.reason }
                null
            }

            is SigningPath.DeviceCredential -> signWithDeviceCredential(title)

            is SigningPath.Biometric -> when (val outcome = biometricSign(title)) {
                is BiometricOutcome.Signed -> outcome.signature
                // Lockout is what "fall back to whatever you have" is for; a plain cancel
                // stays a cancel — the underlying action reads as nothing having happened.
                BiometricOutcome.LockedOut ->
                    if (deviceCredentialPossible) signWithDeviceCredential(title) else null

                // §9: not a cancel — the key is permanently dead and migration is the fix.
                BiometricOutcome.PermanentlyInvalidated -> {
                    onKeyPermanentlyInvalidated()
                    null
                }

                BiometricOutcome.Cancelled -> null
            }
        }
    }

    private sealed interface BiometricOutcome {
        data class Signed(val signature: Signature) : BiometricOutcome
        data object Cancelled : BiometricOutcome
        data object LockedOut : BiometricOutcome
        data object PermanentlyInvalidated : BiometricOutcome
    }

    /**
     * BiometricPrompt signing, same two-path contract the pairing harness proved on the
     * X6532: when initSign succeeds, authenticate with the Signature as a CryptoObject;
     * when keystore2 rejects initSign without a fresh auth token, prompt WITHOUT crypto and
     * initSign inside the success callback (the 900s window, §NFR4, makes that valid).
     *
     * The prompt must be raised on the main thread: every caller reaches this from
     * `Dispatchers.IO`, and BiometricPrompt's FragmentManager throws "Must be called from main
     * thread of fragment host" if constructed or authenticated off-main. Keystore work stays on
     * the caller's dispatcher (NFR3).
     */
    private suspend fun biometricSign(title: String): BiometricOutcome {
        val signature = Signature.getInstance(AccountabilitySigner.SIGNATURE_ALGORITHM)
        val beforePrompt = runCatching { signature.initSign(accountabilityKeyStore.signingPrivateKey()) }
        val failure = beforePrompt.exceptionOrNull()
        if (failure != null) {
            // §9: a permanently dead key cannot be revived by any prompt — no point raising one.
            if (accountabilitySigner.isPermanentlyInvalidated(failure)) {
                Log.w(TAG, "signing key permanently invalidated before the prompt", failure)
                return BiometricOutcome.PermanentlyInvalidated
            }
            Log.w(TAG, "initSign before the prompt failed — asserting without CryptoObject", failure)
        }
        val cryptoReady = beforePrompt.isSuccess
        return withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { cont ->
                val prompt = BiometricPrompt(
                    this@MainActivity,
                    ContextCompat.getMainExecutor(this@MainActivity),
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            if (cryptoReady) {
                                val signed = result.cryptoObject?.signature
                                cont.resume(
                                    if (signed != null) BiometricOutcome.Signed(signed)
                                    else BiometricOutcome.Cancelled,
                                )
                            } else {
                                // A failure here is read as "cancelled" upstream; keep the
                                // real reason in logcat (diagnosed blind on the missing-key rig,
                                // 2026-09-28). A permanent invalidation is the exception — it
                                // cannot be a cancel, the caller must migrate (§9).
                                val signed = runCatching {
                                    signature.initSign(accountabilityKeyStore.signingPrivateKey())
                                }
                                    .fold(
                                        onSuccess = { BiometricOutcome.Signed(signature) as BiometricOutcome },
                                        onFailure = { e ->
                                            Log.w(TAG, "biometric sign after authentication failed", e)
                                            if (accountabilitySigner.isPermanentlyInvalidated(e)) {
                                                BiometricOutcome.PermanentlyInvalidated
                                            } else {
                                                BiometricOutcome.Cancelled
                                            }
                                        },
                                    )
                                cont.resume(signed)
                            }
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            Log.w(TAG, "approval biometric prompt: $errString")
                            val outcome = when (errorCode) {
                                BiometricPrompt.ERROR_LOCKOUT,
                                BiometricPrompt.ERROR_LOCKOUT_PERMANENT,
                                -> BiometricOutcome.LockedOut

                                else -> BiometricOutcome.Cancelled
                            }
                            cont.resume(outcome)
                        }

                        override fun onAuthenticationFailed() = Unit
                    },
                )
                cont.invokeOnCancellation { prompt.cancelAuthentication() }
                val promptInfo = BiometricPrompt.PromptInfo.Builder()
                    .setTitle(title)
                    .setSubtitle(getString(R.string.partner_approval_prompt_subtitle))
                    .setNegativeButtonText(getString(android.R.string.cancel))
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    .build()
                if (cryptoReady) {
                    prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(signature))
                } else {
                    prompt.authenticate(promptInfo)
                }
            }
        }
    }

    /**
     * The owner-ruled fallback (ADR #112): raise the platform's confirm-credential flow —
     * the resolver the Keystore documentation names for time-bound keys — then initialize
     * the signature. A successful confirm deposits the auth token that authorizes the key
     * for the 900s window, so initSign is the real gate: a pre-ruling key (biometric-only,
     * immutable) refuses it, and that refusal gets the explicit message rather than a
     * silent cancel. A cancelled lock screen is a plain cancel, like a dismissed prompt.
     */
    private suspend fun signWithDeviceCredential(title: String): Signature? {
        val confirm = getSystemService(KeyguardManager::class.java)
            ?.createConfirmDeviceCredentialIntent(
                title,
                getString(R.string.partner_signing_screen_lock_subtitle),
            )
        if (confirm == null) {
            Log.w(TAG, "no screen-lock confirm flow available")
            withContext(Dispatchers.Main.immediate) { biometricUnavailable = NoSignatureReason.UNAVAILABLE }
            return null
        }
        val confirmed = withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { cont ->
                credentialContinuation = cont
                cont.invokeOnCancellation { credentialContinuation = null }
                runCatching { credentialLauncher.launch(confirm) }
                    .onFailure { e ->
                        Log.w(TAG, "screen-lock confirm could not be raised", e)
                        credentialContinuation = null
                        if (cont.isActive) cont.resume(false)
                    }
            }
        }
        if (!confirmed) return null
        val signature = Signature.getInstance(AccountabilitySigner.SIGNATURE_ALGORITHM)
        return runCatching { signature.initSign(accountabilityKeyStore.signingPrivateKey()) }
            .fold(
                onSuccess = {
                    Log.i(TAG, "screen lock authorized the signing key")
                    signature
                },
                onFailure = { e ->
                    if (accountabilitySigner.isPermanentlyInvalidated(e)) {
                        // §9: not a pre-ruling-key refusal — the key is permanently gone.
                        onKeyPermanentlyInvalidated()
                        null
                    } else {
                        Log.w(TAG, "screen lock verified but the signing key refused the signature", e)
                        withContext(Dispatchers.Main.immediate) {
                            biometricUnavailable = NoSignatureReason.SCREEN_LOCK_REJECTED
                        }
                        null
                    }
                },
            )
    }

    /**
     * §8.2/NFR4: compare the live enrollment state against the last committed one and run the
     * §7.6 migration on any change. A failed rotation stays uncommitted and retries next
     * resume; with nothing severed (reEnrollmentRequired == 0) the rotation is invisible, so
     * the notice dialog is owed only when connections were actually cut.
     */
    private suspend fun checkIdentityEnrollment() {
        if (!biometricEnrollmentMonitor.enrollmentStateChanged()) {
            biometricEnrollmentMonitor.commitEnrollmentState() // baseline, or refresh
            return
        }
        Log.i(TAG, "enrollment change detected — running identity migration")
        val result = runIdentityMigration()
        if (result is IdentityMigration.MigrationResult.Migrated && result.reEnrollmentRequired > 0) {
            identityResetNotice = true
        }
    }

    /**
     * §9: a signature attempt proved the key permanently invalidated (the OS does this when
     * the secure lock screen is removed or forcibly reset). No prompt can revive it, and the
     * attempted action can never complete on this identity — run the migration the plan names
     * ("immediately") and say so.
     */
    private suspend fun onKeyPermanentlyInvalidated() {
        Log.w(TAG, "signing key permanently invalidated — running identity migration")
        runIdentityMigration()
        identityResetNotice = true
    }

    /**
     * §7.6/§8.2/§9 single-flight identity migration (rotation + epoch bump + enrollment
     * clear — [IdentityMigration]'s contract; Keystore and DB work off the main thread per
     * NFR3). Returns null when another migration is already running.
     */
    private suspend fun runIdentityMigration(): IdentityMigration.MigrationResult? {
        if (identityMigrationInFlight) return null
        identityMigrationInFlight = true
        return try {
            val result = withContext(Dispatchers.IO) {
                identityMigration.migrate { accountabilityKeyStore.rotateKeys() }
            }
            when (result) {
                is IdentityMigration.MigrationResult.Migrated -> {
                    biometricEnrollmentMonitor.commitEnrollmentState()
                    Log.i(
                        TAG,
                        "identity migration done: epoch=${result.epoch} " +
                            "reEnrollment=${result.reEnrollmentRequired}",
                    )
                }

                is IdentityMigration.MigrationResult.Failed ->
                    Log.w(TAG, "identity migration failed — retries next resume (${result.reason})")
            }
            result
        } finally {
            identityMigrationInFlight = false
        }
    }

    /** "Check now" is a promise, and Firestore cannot keep it honestly offline: queued writes
     *  complete locally and the sync pass returns a clean report, so the button would claim a
     *  sync that never left the device. The radio is the only truthful pre-check, and the
     *  failure copy already tells the user to check the connection. (Rationale from Firestore's
     *  documented offline-write-queue semantics; no air-gapped device run backs this line.) */
    private fun isOnline(): Boolean {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return true
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** §8 Step 6: protection status is re-evaluated on every resume through the shared
     *  arbiter (which also runs on the FGS's own 30s cycle — revocation while the app is
     *  never opened is still detected). */
    private fun refreshProtectionChecks() {
        protectionStatusMonitor.refresh()
        if (settings.enhancedModeEnabled) {
            EnforcementForegroundService.start(this)
        }
    }

    companion object {
        const val EXTRA_START_TAB = "extra_start_tab"

        /** Milestone notification deep link: which habit's Home to show. */
        const val EXTRA_START_HABIT = "extra_start_habit"

        /** Which surface opened the Recovery tab — a `RecoverySession.ENTRY_*` constant. */
        const val EXTRA_RECOVERY_ENTRY = "extra_recovery_entry"

        // Tab deep-link values (ADR #91): one definition shared with the shell's tab list —
        // `NavKeys` — so the extra carries a protocol key, never a display label.

        const val TAB_RECOVERY = NavKeys.RECOVERY

        /** Milestone notifications land here: the splash card renders at the top of Home
         *  (plan §8 Step 5), so the notification's "open the app" call-to-action must show it. */
        const val TAB_HOME = NavKeys.HOME

        /** The widget's day count opens here: the number should land on the streak it names,
         *  not on Home. Matched against the shell's tab keys (case-insensitive). */
        const val TAB_STREAKS = NavKeys.STREAKS

        /** Enforcement degradation alerts (§8 Step 6) land here: the Settings protection
         *  rows name each mechanism and carry its fix. */
        const val TAB_SETTINGS = NavKeys.SETTINGS

        private const val TAG = "MyExodus"
    }
}

/** §8.2/§9: enrollment change or dead key — the identity reset is never silent. */
@Composable
private fun IdentityResetDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.identity_reset_title)) },
        text = { Text(stringResource(R.string.identity_reset_body)) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.partner_biometric_close))
            }
        },
    )
}

/** Plan §5.20/§12.3 (ADR #112): the explicit message — never a silent cancel. */
@Composable
private fun BiometricUnavailableDialog(
    reason: NoSignatureReason,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val title = when (reason) {
        NoSignatureReason.NOT_ENROLLED -> R.string.partner_biometric_not_enrolled_title
        NoSignatureReason.UNAVAILABLE -> R.string.partner_biometric_unavailable_title
        NoSignatureReason.SCREEN_LOCK_REJECTED -> R.string.partner_sign_lock_rejected_title
    }
    val body = when (reason) {
        NoSignatureReason.NOT_ENROLLED -> R.string.partner_biometric_not_enrolled_body
        NoSignatureReason.UNAVAILABLE -> R.string.partner_biometric_unavailable_body
        NoSignatureReason.SCREEN_LOCK_REJECTED -> R.string.partner_sign_lock_rejected_body
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(body)) },
        confirmButton = {
            // NOT_ENROLLED and SCREEN_LOCK_REJECTED are both fixed by the same Settings
            // trip (enroll a biometric); UNAVAILABLE has nothing actionable to offer.
            if (reason != NoSignatureReason.UNAVAILABLE) {
                TextButton(onClick = onOpenSettings) {
                    Text(stringResource(R.string.partner_biometric_open_settings))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.partner_biometric_close))
            }
        },
    )
}

@Composable
private fun DatabaseHealthScreen(
    health: DatabaseHealth,
    onDiscardCorrupt: () -> Unit,
    onReset: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showResetDialog by remember { mutableStateOf(false) }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text(stringResource(R.string.db_health_reset_dialog_title)) },
            text = { Text(stringResource(R.string.db_health_reset_dialog_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showResetDialog = false
                    onReset()
                }) { Text(stringResource(R.string.db_health_reset_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text(stringResource(R.string.db_health_cancel))
                }
            },
        )
    }

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when (health) {
            DatabaseHealth.Opening -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.db_health_opening))
            }
            DatabaseHealth.Ready -> Unit
            DatabaseHealth.EncryptionUpgradePending -> {
                Text(
                    stringResource(R.string.encryption_pending_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.db_health_upgrade_body))
                Spacer(Modifier.height(16.dp))
                Button(onClick = onRetry) { Text(stringResource(R.string.db_health_retry)) }
            }
            DatabaseHealth.RecoverableDataFound -> {
                Text(
                    stringResource(R.string.db_health_recoverable_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.db_health_recoverable_body))
                Spacer(Modifier.height(16.dp))
                Button(onClick = onDiscardCorrupt) { Text(stringResource(R.string.db_health_discard)) }
            }
            DatabaseHealth.DatabaseIssue -> {
                Text(
                    stringResource(R.string.db_health_issue_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.db_health_issue_body))
                Spacer(Modifier.height(16.dp))
                Button(onClick = { showResetDialog = true }) {
                    Text(stringResource(R.string.db_health_reset_action))
                }
            }
            DatabaseHealth.KeyUnavailable -> {
                Text(
                    stringResource(R.string.db_health_key_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.db_health_key_body))
                Spacer(Modifier.height(16.dp))
                Button(onClick = { showResetDialog = true }) {
                    Text(stringResource(R.string.db_health_reset_action))
                }
            }
            DatabaseHealth.Transient -> {
                Text(
                    stringResource(R.string.db_health_storage_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.db_health_storage_body))
                Spacer(Modifier.height(16.dp))
                Button(onClick = onRetry) { Text(stringResource(R.string.db_health_retry)) }
                Spacer(Modifier.height(8.dp))
                Button(onClick = { showResetDialog = true }) {
                    Text(stringResource(R.string.db_health_reset_action))
                }
            }
        }
    }
}

