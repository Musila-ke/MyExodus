package com.example.myexodus

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.lifecycle.repeatOnLifecycle
import com.example.myexodus.blocking.AttemptRecorder
import com.example.myexodus.blocking.EnhancedModeHealthMonitor
import com.example.myexodus.blocking.EnforcementForegroundService
import com.example.myexodus.blocking.ProtectionStatusMonitor
import com.example.myexodus.blocking.ProtectionStatusStore
import com.example.myexodus.blocking.RulesRepository
import com.example.myexodus.blocking.RulesSnapshotPublisher
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
import com.example.myexodus.accountability.monitoring.PartnerDmsClient
import com.example.myexodus.accountability.plan.PartnerPlanRepository
import com.example.myexodus.accountability.serialization.EventPayload
import com.example.myexodus.accountability.state.AccountabilityDatabase
import com.example.myexodus.accountability.sync.AccountCallables
import com.example.myexodus.accountability.sync.MessageSendOutcome
import com.example.myexodus.accountability.sync.PairUidResolver
import com.example.myexodus.accountability.sync.messageSendOutcome
import com.example.myexodus.accountability.sync.PartnerAlertHealth
import com.example.myexodus.accountability.sync.PartnerNotifications
import com.example.myexodus.accountability.sync.PartnerSyncCoordinator
import com.example.myexodus.accountability.sync.PartnerLiveSync
import com.example.myexodus.accountability.sync.PartnerNudgePush
import com.example.myexodus.accountability.sync.PartnerRolloutGate
import com.example.myexodus.accountability.sync.PartnerSyncSignals
import com.example.myexodus.accountability.sync.PushInstallationRegistrar
import com.example.myexodus.accountability.sync.runPartnerSyncPass
import com.google.firebase.functions.FirebaseFunctions
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
import com.example.myexodus.settings.HomeSettings
import com.example.myexodus.settings.SetupStateStore
import com.example.myexodus.settings.ThemeSettings
import com.example.myexodus.settings.notificationSettingsIntent
import com.example.myexodus.settings.StrictModeManager
import com.example.myexodus.vpn.VpnLifecycle
import com.example.myexodus.streaks.HabitRepository
import com.example.myexodus.settings.WidgetSettings
import com.example.myexodus.telemetry.CrashReporting
import com.example.myexodus.update.UpdateCheckWorker
import com.example.myexodus.telemetry.StartupTrace
import com.example.myexodus.notifications.NotificationAccess
import com.example.myexodus.ui.AppShell
import com.example.myexodus.ui.NavKeys
import com.example.myexodus.ui.recovery.RECOVERY_VIEW_ASSIST
import com.example.myexodus.ui.SecureSurfaces
import com.example.myexodus.ui.home.NudgeInboxViewModel
import com.example.myexodus.ui.settings.AccountViewModel
import com.example.myexodus.ui.settings.BackupKeyViewModel
import com.example.myexodus.ui.settings.NudgeSettingsViewModel
import com.example.myexodus.ui.settings.UpdateViewModel
import com.example.myexodus.ui.home.SosViewModel
import com.example.myexodus.ui.partner.PairingCeremonyDeps
import com.example.myexodus.ui.partner.PartnerDashboardSource
import com.example.myexodus.ui.partner.PartnerSelection
import com.example.myexodus.ui.partner.UnpairOutcome
import com.example.myexodus.widget.ProtectionSnapshotWriter
import com.example.myexodus.ui.setup.OnboardingScreen
import com.example.myexodus.ui.insights.InsightsViewModel
import com.example.myexodus.ui.streaks.StreaksViewModel
import com.example.myexodus.ui.theme.MyExodusTheme
import com.example.myexodus.ui.theme.rememberDarkTheme
import dagger.hilt.android.AndroidEntryPoint
import java.security.Signature
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /** ADR #193 deep link: a plan notification names the plan it is about, and the tap opens
     *  that plan — the Partner tab's Plans subpage with the detail already shown. */
    private var requestedPlan by mutableStateOf<String?>(null)

    /** Owner report 2026-10-31: a partner notification names ONE partner; its tap must show
     *  that partner's dashboard, never whichever roster entry happened to be selected. */
    private var requestedPartner by mutableStateOf<String?>(null)

    /** Owner ask 2026-11-02: a partner notification also names the surface it concerns; the
     *  tap opens that subpage (activity, rules, requests) instead of the hub. */
    private var requestedPartnerView by mutableStateOf<String?>(null)

    /** Which surface sent the user to the Recovery tab (block screen vs. widget). Labels the
     *  session rows the visit creates (v3 W7 `RecoverySession.entry`). */
    private var requestedRecoveryEntry by mutableStateOf<String?>(null)

    /** Phase 4: the block screen and the widget land directly on Urge Assist, not the hub. */
    private var requestedRecoveryView by mutableStateOf<String?>(null)

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

    private val startupTrace = StartupTrace("Activity")

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
        requestedPlan = intent.getStringExtra(EXTRA_OPEN_PLAN)
        requestedPartner = intent.getStringExtra(EXTRA_OPEN_PARTNER)
        requestedPartnerView = intent.getStringExtra(EXTRA_OPEN_PARTNER_VIEW)
        requestedRecoveryView =
            if (intent.getBooleanExtra(EXTRA_OPEN_ASSIST, false)) RECOVERY_VIEW_ASSIST else null
        handleUpdateIntent(intent)
        handlePartnerNudgeIntent(intent)
    }

    /** Plan revision 2026-11-06: an update notification tap opens the Settings update dialog. */
    private fun handleUpdateIntent(intent: Intent) {
        if (!intent.getBooleanExtra(EXTRA_OPEN_UPDATE, false)) return
        requestedTab = TAB_SETTINGS
        updateViewModel.requestOpenDialog()
        intent.removeExtra(EXTRA_OPEN_UPDATE)
    }

    /**
     * SOS §8.18: a nudge launcher intent (background FCM tap, or the foreground post's tap)
     * routes to Home, and its extras are consumed — stripped from the stored intent so a
     * later recreate/state restore never re-routes. The extras are navigation hints only;
     * the card's content always comes from Firestore (never the push payload).
     */
    private fun handlePartnerNudgeIntent(intent: Intent?) {
        if (PartnerNudgePush.consumeLauncherNudge(intent ?: return) != null) {
            requestedTab = TAB_HOME
        }
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
    /** #26: "is there anything to enforce" for the tunnel ensure-pass — the blocking policy
     *  SSOT itself, so activation and the sinkhole's own blocklist can never disagree. */
    @Inject lateinit var rulesSnapshotPublisher: RulesSnapshotPublisher
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
    @Inject lateinit var themeSettings: ThemeSettings
    @Inject lateinit var widgetSettings: WidgetSettings
    @Inject lateinit var homeSettings: HomeSettings
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
    // The dashboard's "Check now" and the #14 foreground pass both run the production sync
    // pass in-process — WorkManager dispatch is never performed on XOS (ADR #27), so a
    // WM-based path would be dead there.
    @Inject lateinit var partnerSyncCoordinator: PartnerSyncCoordinator
    // Plan Step 8: the Partner tab's connection card — consent convergence rides the sync
    // pass; this client is the screen's bounded (60 s) status read.
    @Inject lateinit var partnerDmsClient: PartnerDmsClient
    /** Plan §8.10: startup compatibility read (public callable, no App Check/auth). */
    @Inject lateinit var functions: FirebaseFunctions
    @Inject lateinit var pushInstallationRegistrar: PushInstallationRegistrar
    @Inject lateinit var partnerRolloutGate: PartnerRolloutGate
    // Owner mandate 2026-10-04 (realtime): the foreground mailbox doorbell — its ring lands
    // in [startPartnerLiveSync]'s collector below as the same in-process pass.
    @Inject lateinit var partnerLiveSync: PartnerLiveSync
    // Send-kick (owner UX ruling 2026-10-01): after a message is journaled, the composer's
    // outcome is derived from whether the pair's queues actually cleared — the outbox half
    // lives in this database.
    @Inject lateinit var accountabilityDb: AccountabilityDatabase
    // Already constructed by MyExodusApp at process start; injected here only to re-publish the
    // snapshot the moment the discreet/detailed toggle flips (widget spec §5 Decision 10).
    @Inject lateinit var widgetSnapshotWriter: ProtectionSnapshotWriter
    // Recovery-resource feature (v3 W1/W2/W7): the plan items shown during a technique, the
    // session log, and the haptic-pacing preference.
    @Inject lateinit var recoveryPlanRepository: RecoveryPlanRepository
    /** ADR #193: partner plans — the shared checklist the Partner hub reaches. */
    @Inject lateinit var partnerPlanRepository: PartnerPlanRepository
    @Inject lateinit var recoverySessions: RecoverySessionRepository
    @Inject lateinit var recoveryPrefs: RecoveryPrefs
    // Owner mandate 2026-10-04: the Settings sharing pane's per-streak consent reads/writes.
    @Inject lateinit var habitRepository: HabitRepository

    private val streaksViewModel: StreaksViewModel by viewModels()

    /** Owner task #22: the Streaks tab's Insights spoke; loads only when opened. */
    private val insightsViewModel: InsightsViewModel by viewModels()

    /** SOS §8.15 (Step 8): the Reach out sender state machine. */
    private val sosViewModel: SosViewModel by viewModels()

    /** SOS §8.16 (Step 9): the recipient inbox — the listener's start/stop follows the
     *  Home tab inside the shell. */
    private val nudgeInboxViewModel: NudgeInboxViewModel by viewModels()

    /** SOS §8.19 (Step 11): the recipient's block switch on the Settings row. */
    private val nudgeSettingsViewModel: NudgeSettingsViewModel by viewModels()

    /** Account plan §8.3 (R1.8): the optional-account pane's driver. */
    private val accountViewModel: AccountViewModel by viewModels()

    /** Account plan §7 Release 2 step 12 (R2.12): the ABK key-bundle pane's driver. */
    private val backupKeyViewModel: BackupKeyViewModel by viewModels()

    /** ADR #171: GitHub Releases update check and install state. */
    private val updateViewModel: UpdateViewModel by viewModels()

    /** Plan §8 Step 5 / Decision 9: the foreground evaluation trigger lives here, at the
     *  Activity lifecycle boundary — never inside composition. The ViewModel's own `init`
     *  covers the first launch. */
    override fun onResume() {
        super.onResume()
        streaksViewModel.refresh()
        // SOS §8.19: the block switch's state is re-read at the same boundary (a pair change
        // or a write from elsewhere lands the fresh value on the way back to Settings).
        nudgeSettingsViewModel.refresh()
        // §8.2: enrollment changes cannot invalidate the key itself (time-bound keys sit
        // outside setInvalidatedByBiometricEnrollment's scope — android-36 javadoc), so the
        // poll runs here: startup and every ON_RESUME.
        lifecycleScope.launch { checkIdentityEnrollment() }
        // #14: partner sync is an automatic process — opening the app runs the same in-process
        // pass the periodic worker and the dashboard's manual path run (the cold-auth-window
        // case stops cleanly inside the pass; no biometric prompt is ever raised from here).
        refreshPartnerPushRegistration()
        // #26: the website tunnel follows the rules — up when any live website rule exists
        // (consent permitting), down when the last one leaves. Runs at this boundary so a
        // return from the consent dialog or system settings lands the freshly-valid answer.
        refreshWebsiteLimitTunnel()
        // Updater R01: foreground entry joins the 24-hour discovery check when one is due.
        UpdateCheckWorker.enqueueForegroundIfDue(this)
    }

    /**
     * #26 SSOT for "should the website tunnel run": the publisher's url rules — the blocking
     * policy itself (enabled, unexpired, limit or block-only). The fast path loads the on-disk
     * cache first (a cold app process has nothing published yet — an empty read must never
     * stop a healthy tunnel), then the authoritative rebuild lands the current truth,
     * including a rule that expired overnight.
     */
    private fun refreshWebsiteLimitTunnel() {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                rulesSnapshotPublisher.loadFastPath()
                rulesSnapshotPublisher.loadFromDb()
                val hasRules = rulesSnapshotPublisher.snapshot.urlRules.isNotEmpty()
                VpnLifecycle.ensure(applicationContext, hasRules)
            }.onFailure { Log.w(TAG, "tunnel ensure-pass failed", it) }
        }
    }

    /**
     * §8.2/#14: fire-and-forget sync pass at the lifecycle boundaries that matter to push
     * delivery — app foreground (§8.12 startup/ON_START) and a notification-permission flip
     * (§8.12 permission-result trigger: the grant changes `pushEligible`). Also covers the
     * settings-return trigger, since a return from system settings resumes this activity.
     * A failed pass leaves the dashboard's freshness state to say so; the next pass retries.
     */
    private fun refreshPartnerPushRegistration() {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                if (isOnline()) {
                    runPartnerSyncPass(
                        pushInstallationRegistrar,
                        partnerRolloutGate,
                        partnerSyncCoordinator,
                    )
                }
            }.onFailure { Log.w(TAG, "foreground sync pass failed", it) }
        }
    }

    /** ADR #216: coalesces the sync heal — a prompt already owns the screen, so a signal
     *  arriving mid-prompt (or a Check-now+signal race) must not stack a second sheet. Owner
     *  fix 2026-11-09: a plain flag made an explicit share-time delivery return false while an
     *  automatic pass was in flight (passes run every few seconds), so the user saw "queued"
     *  for a delivery that was not attempted. The automatic path skips; the explicit path
     *  waits its turn. */
    private val signingHealMutex = Mutex()

    /**
     * ADR #216 + owner ruling 2026-11-05: partner delivery is automatic. The §8.1 key refuses
     * to sign while its 900 s window is cold — a window every device authentication re-opens
     * without any app prompt — so an automatic pass signs when warm and otherwise leaves the
     * rows journaled. The periodic worker runs the same pass, so the next unlock delivers the
     * backlog: nothing waits on a tap, and no sheet exists for the subject to dismiss and
     * suppress the partner. Prompts remain only on explicit user-initiated actions (Check now,
     * pairing, unpair, message send); a partner-visible act never raises one (ADR #294).
     */
    private fun startPartnerSigningHeal() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // ADR #294: with no prompt left, the pass that turns a just-warmed §8.1
                // window (the unlock that brought the app back) into delivery runs on every
                // foreground start instead of waiting for the 15-minute worker.
                launch { deliverQueuedPartnerEvents() }
                launch {
                    // ADR #216 retry, gated on real warmth: a cold pass re-emits this signal,
                    // so retrying unconditionally looped a full network pass every ~15 s for as
                    // long as the window stayed cold (device evidence 2026-11-10).
                    PartnerSyncSignals.signingNeeded.collect {
                        if (signingWindowWarm()) deliverQueuedPartnerEvents()
                    }
                }
                launch {
                    // Owner ruling 2026-11-05: a rule save, edit, deactivate or delete is
                    // delivered automatically — never gated on a confirmation the subject can
                    // ignore. A cold window only defers the delivery, never drops it.
                    PartnerSyncSignals.ruleChangesJournaled.collect { deliverQueuedPartnerEvents() }
                }
            }
        }
    }

    /**
     * One automatic delivery pass. A cold signing window is not an error and never raises a
     * sheet: the journal row is durable and the next pass after any device authentication
     * (foreground start or the periodic worker) signs and sends it.
     */
    private suspend fun deliverQueuedPartnerEvents() {
        if (!signingHealMutex.tryLock()) return
        try {
            if (!isOnline()) return
            if (!runPartnerSyncPass(pushInstallationRegistrar, partnerRolloutGate, partnerSyncCoordinator).signingBlocked) return
            if (signingWindowWarm()) {
                runPartnerSyncPass(pushInstallationRegistrar, partnerRolloutGate, partnerSyncCoordinator)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "automatic partner delivery failed", e)
        } finally {
            signingHealMutex.unlock()
        }
    }

    /**
     * The explicit "deliver my queued updates" path (Check now). Runs the pass first — the
     * common warm-window case stays one pass with no sheet — and raises the device-key prompt
     * only when the signer is cold. Automatic delivery never comes through here.
     */
    private suspend fun healBlockedPartnerSigning(): Boolean {
        try {
            return signingHealMutex.withLock {
                if (!isOnline()) return@withLock false
                if (!runPartnerSyncPass(pushInstallationRegistrar, partnerRolloutGate, partnerSyncCoordinator).signingBlocked) {
                    return@withLock true
                }
                if (signingWindowWarm()) {
                    return@withLock !runPartnerSyncPass(pushInstallationRegistrar, partnerRolloutGate, partnerSyncCoordinator).signingBlocked
                }
                // Cancelled prompt: rows stay journaled for the next automatic pass.
                signWithAuthPrompt(getString(R.string.partner_sync_auth_title)) ?: return@withLock false
                !runPartnerSyncPass(pushInstallationRegistrar, partnerRolloutGate, partnerSyncCoordinator).signingBlocked
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "partner signing heal failed", e)
            return false
        }
    }

    /**
     * Owner mandate 2026-10-04 (realtime receive): while this screen is started, a Firestore
     * snapshot listener on our own mailbox rings the moment the partner's relay writes
     * something new, and each ring runs the same in-process pass the periodic worker runs — so
     * "hasn't even arrived" becomes "arrived in seconds". The pairing gate is re-checked here
     * because a ceremony can complete without a stop/start transition; once live, the listener
     * outlives pairing changes (the mailbox is keyed by uid, which never changes).
     */
    private fun startPartnerLiveSync() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var liveUid: String? = null
                while (liveUid == null) {
                    liveUid = partnerLiveSync.myUidIfLive()
                    if (liveUid == null) delay(PARTNER_LIVE_RECHECK_MILLIS)
                }
                val uid = liveUid
                partnerLiveSync.changes(uid).collect {
                    runCatching {
                        if (isOnline()) {
                            runPartnerSyncPass(
                                pushInstallationRegistrar,
                                partnerRolloutGate,
                                partnerSyncCoordinator,
                            )
                        }
                    }.onFailure { Log.w(TAG, "live-sync pass failed", it) }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startupTrace.lap("super")
        requestedTab = intent.getStringExtra(EXTRA_START_TAB)
        requestedHabit = intent.getStringExtra(EXTRA_START_HABIT)
        requestedRecoveryEntry = intent.getStringExtra(EXTRA_RECOVERY_ENTRY)
        requestedPlan = intent.getStringExtra(EXTRA_OPEN_PLAN)
        requestedPartner = intent.getStringExtra(EXTRA_OPEN_PARTNER)
        requestedPartnerView = intent.getStringExtra(EXTRA_OPEN_PARTNER_VIEW)
        requestedRecoveryView =
            if (intent.getBooleanExtra(EXTRA_OPEN_ASSIST, false)) RECOVERY_VIEW_ASSIST else null
        handleUpdateIntent(intent)
        handlePartnerNudgeIntent(intent)
        startPartnerLiveSync()
        startPartnerSigningHeal()
        startupTrace.lap("intents")
        enableEdgeToEdge()
        setContent {
            // Hoisted so a Settings change repaints the whole app in the same frame; the store
            // is the durable copy, this is the live one.
            var themeMode by remember { mutableStateOf(themeSettings.mode) }
            MyExodusTheme(darkTheme = rememberDarkTheme(themeMode)) {
                // Plan §8.10: new clients read the server-published minimum version at startup
                // and show the update-required message below it. This is a UX signal only —
                // rules and callables enforce independently, so a failed read is not a bypass.
                var updateRequired by remember { mutableStateOf<String?>(null) }
                var compatibilityAttempt by remember { mutableIntStateOf(0) }
                LaunchedEffect(compatibilityAttempt) {
                    // A failed read keeps the previous state: once blocked, a retry without
                    // connectivity must not unlock the app.
                    runCatching { AccountCallables.clientCompatibility(functions) }
                        .onSuccess { compatibility ->
                            updateRequired = if (compatibility.blocks(BuildConfig.VERSION_CODE)) {
                                compatibility.updateMessage?.takeIf { it.isNotBlank() }
                                    ?: getString(R.string.update_required_body)
                            } else {
                                null
                            }
                        }
                }
                // §8.2: the monitor publishes degradation reactively — the banner updates the
                // moment the FGS start() detection lands, not only on the next resume.
                val degradation by protectionStatusMonitor.degradation.collectAsStateWithLifecycle()
                // Finding #13: the status chip and the degradation banner must read the same
                // authority in the same frame — the chip's own tri-state comes from here.
                val authority by protectionStatusMonitor.authority.collectAsStateWithLifecycle()
                // §9 row 468 / acceptance line 631: the partner dashboard's banner is a
                // persistent, live state — re-checked on every resume, never a stale read.
                var notificationsEnabled by remember {
                    mutableStateOf(NotificationAccess.canPost(this@MainActivity))
                }
                // Plan Step 6: permission + the partner-activity channel + push-provider
                // support, re-read on the same boundaries as the permission itself.
                var alertHealth by remember {
                    mutableStateOf(PartnerAlertHealth.of(this@MainActivity))
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
                    alertHealth = PartnerAlertHealth.of(this@MainActivity)
                    // §8.12 permission-result trigger: the grant flips pushEligible on the
                    // installation document — re-register immediately, don't wait for resume.
                    refreshPartnerPushRegistration()
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
                            notificationsEnabled = NotificationAccess.canPost(this@MainActivity)
                            alertHealth = PartnerAlertHealth.of(this@MainActivity)
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
                // First-run onboarding (owner request 2026-11-06): the full journey — capability
                // slides, account choice, permissions — runs once per install, recorded the same
                // way as the plan §8 Step 7 wizard it replaces. A later revocation never
                // re-opens it; Settings rows and the degraded banner own those fixes.
                var showSetup by remember { mutableStateOf<Boolean?>(null) }
                LaunchedEffect(Unit) {
                    showSetup = withContext(Dispatchers.IO) { !setupStateStore.setupSeen }
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
                        // §19.5: the freshly created pair becomes the shown partner.
                        onPaired = { pairId -> partnerSelection.selectedPairId = pairId },
                    )
                }
                // Local read: a delegated property cannot be smart-cast, and AppShell now takes
                // the store non-null — it is only ever composed once the store is open.
                val openStore = rulesStore
                val blockedUpdate = updateRequired
                if (blockedUpdate != null) {
                    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                        UpdateRequiredScreen(
                            message = blockedUpdate,
                            onRetry = { compatibilityAttempt++ },
                            modifier = Modifier.padding(innerPadding),
                        )
                    }
                } else if (usable && showSetup == true) {
                    OnboardingScreen(
                        monitor = monitor,
                        accountViewModel = accountViewModel,
                        onFinish = {
                            setupStateStore.setupSeen = true
                            showSetup = false
                            // The journey ends the same way the plan §8 Step 7 wizard did:
                            // start enforcement while the app is foregrounded.
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
                        homeSettings = homeSettings,
                        widgetSettings = widgetSettings,
                        onWidgetSettingsChanged = widgetSnapshotWriter::publish,
                        themeMode = themeMode,
                        onThemeModeChange = { mode ->
                            themeSettings.mode = mode
                            themeMode = mode
                        },
                        degradation = degradation,
                        authority = authority,
                        streaks = streaksViewModel,
                        insights = insightsViewModel,
                        sos = sosViewModel,
                        nudgeInbox = nudgeInboxViewModel,
                        nudgeSettings = nudgeSettingsViewModel,
                        account = accountViewModel,
                        backupKey = backupKeyViewModel,
                        update = updateViewModel,
                        partnerSource = partnerDashboardSource,
                        onLoadPartnerStatus = { pairId -> partnerDmsClient.status(pairId) },
                        partnerSelection = partnerSelection,
                        ceremonyDeps = ceremonyDeps,
                        partnerNotificationsEnabled = notificationsEnabled,
                        partnerNotificationsRequestable = canRequestNotifications,
                        partnerAlertHealth = alertHealth,
                        onFixNotifications = {
                            when (alertHealth) {
                                // The channel has its own switch; app-level settings would
                                // bury it one screen deeper.
                                PartnerAlertHealth.State.CHANNEL_OFF -> startActivity(
                                    Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                                        .putExtra(
                                            Settings.EXTRA_CHANNEL_ID,
                                            PartnerNotifications.CHANNEL_ACCOUNTABILITY_EVENTS,
                                        ),
                                )
                                else -> if (canRequestNotifications) {
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    startActivity(notificationSettingsIntent(this@MainActivity))
                                }
                            }
                        },
                        // ADR #216: Check now is the explicit "deliver my queued updates"
                        // request, so a cold signing window raises the prompt here instead of
                        // returning a success receipt for a pass that sent nothing.
                        onPartnerCheckNow = {
                            isOnline() && runCatching {
                                healBlockedPartnerSigning()
                            }.getOrDefault(false)
                        },
                        // ADR #294: share changes deliver silently — a cold window queues the
                        // rows instead of raising the sheet over the tap.
                        onPartnerDeliverSilently = { deliverQueuedPartnerEvents() },
                        onRespondToApproval = { requestEventId, approve ->
                            respondToApproval(requestEventId, approve)
                        },
                        onUnpair = { pairId -> endPartnership(pairId) },
                        onDismissUnpair = { pairId -> bilateralDeletion.dismiss(pairId) },
                        onRenameUser = { pairId, label ->
                            enrollmentDao.updatePartnerLabel(pairId, PartnerLabel.sanitize(label))
                        },
                        // Req 15: journal-first, same as every other shared event — the message
                        // is written locally first (no signing window needed for the write).
                        // Addressed to the selected partner (#96): the row carries targetPairId,
                        // so no other supported user's chain can ever be handed this message.
                        // Owner UX ruling 2026-10-01: the write is followed by a sync kick, so
                        // the message leaves this phone now instead of waiting for the next
                        // 15-minute pass — and the outcome the composer shows is derived from
                        // the queues actually clearing, never from the local write alone.
                        onSendMessage = { pairId, text ->
                            val store = rulesStoreFactory.open()
                            val profileId = store?.userProfileDao()?.getActive()?.userId
                            val emitted = profileId != null && PartnerActivityJournal.emitForProfile(
                                store,
                                profileId,
                                EventPayload.TYPE_MOTIVATIONAL_MESSAGE,
                                occurredAtMs = System.currentTimeMillis(),
                                text = text,
                                targetPairId = pairId,
                            )
                            messageSendOutcome(
                                emitted = emitted,
                                online = isOnline(),
                                // Cold-window send (journey finding 2026-10-01): the chain
                                // signature needs the §8.1 window warm, and a normal session
                                // never raises a prompt — so tapping Send itself authorizes,
                                // same house pattern as unpair. Cancelling keeps the message
                                // journaled, so it reports QUEUED, never a failure.
                                authorize = {
                                    signingWindowWarm() ||
                                        signWithAuthPrompt(getString(R.string.partner_compose_auth_title)) != null
                                },
                                runPass = {
                                    runPartnerSyncPass(
                                        pushInstallationRegistrar,
                                        partnerRolloutGate,
                                        partnerSyncCoordinator,
                                    )
                                },
                                stillPending = { messageQueuedForPair(store, pairId) },
                            )
                        },
                        requestedTab = requestedTab,
                        onTabConsumed = { requestedTab = null },
                        requestedHabit = requestedHabit,
                        onHabitConsumed = { requestedHabit = null },
                        requestedPlan = requestedPlan,
                        onPlanConsumed = { requestedPlan = null },
                        requestedPartner = requestedPartner,
                        onPartnerConsumed = { requestedPartner = null },
                        requestedPartnerView = requestedPartnerView,
                        onPartnerViewConsumed = { requestedPartnerView = null },
                        requestedRecoveryView = requestedRecoveryView,
                        onRecoveryViewConsumed = { requestedRecoveryView = null },
                        planRepository = recoveryPlanRepository,
                        rulesRepository = rulesRepository,
                        habitRepository = habitRepository,
                        partnerPlanRepository = partnerPlanRepository,
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
                            // reveal another degraded reason (or none). The published flow
                            // clears the banner in the same frame.
                            protectionStatusMonitor.refresh()
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
        startupTrace.lap("setContent-returned")
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
                identities?.myUid,
                identities?.partnerUid,
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
     *
     * Owner ruling 2026-11-02 supersedes #17's in-sheet credential entry: the OEM sheet's
     * own "Use Numeric Password" link is SystemUI-painted and can be invisible in light
     * mode, so the sheet now carries an app-labeled negative button for the screen-lock
     * route. An extra app dialog in front of the sheet was tried and rejected the same day.
     *
     * Owner report 2026-11-08: that app label is still SystemUI-painted — androidx.biometric
     * on API 28+ hands the whole prompt to android.hardware.biometrics.BiometricPrompt, so
     * the button stays invisible in light mode on the same skins. The negative button is
     * kept (it works where SystemUI paints correctly), but no screen may rely on it for
     * discovery: the ceremony draws its own screen-lock button (PairingCeremonyScreen).
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
                // Lockout routing survives for OEM sheets that still raise it; a plain
                // cancel stays a cancel — the underlying action reads as nothing
                // having happened.
                BiometricOutcome.LockedOut ->
                    if (deviceCredentialPossible) signWithDeviceCredential(title) else null

                // §9: not a cancel — the key is permanently dead and migration is the fix.
                BiometricOutcome.PermanentlyInvalidated -> {
                    onKeyPermanentlyInvalidated()
                    null
                }

                // Owner report 2026-11-02: the app-labeled negative button replaces the
                // OEM sheet's own credential link, which renders invisible in light mode
                // on some skins (its color is SystemUI's, unreachable from here).
                BiometricOutcome.CredentialRequested -> signWithDeviceCredential(title)

                BiometricOutcome.Cancelled -> null
            }
        }
    }

    private sealed interface BiometricOutcome {
        data class Signed(val signature: Signature) : BiometricOutcome
        data object Cancelled : BiometricOutcome
        data object LockedOut : BiometricOutcome
        data object CredentialRequested : BiometricOutcome
        data object PermanentlyInvalidated : BiometricOutcome
    }

    /**
     * BiometricPrompt signing that never binds a CryptoObject (owner ruling 2026-10-04):
     * a crypto-bound prompt can only be answered by a biometric — the sheet cannot render the
     * "Use PIN/pattern" affordance, so anyone whose fingerprint won't read had no way through.
     * The sheet plus the key's 900s auth window (setUserAuthenticationParameters, §NFR4) make
     * the no-crypto path correct — the prompt authorizes the key and initSign runs in the
     * success callback (the harness-proven path, 2026-09-28; device-differential found
     * 2026-10-04: a live keystore window made the old code take the crypto branch on one
     * phone and the sheet lost its fallback — and its continuation, fingerprint-only and
     * unresumable).
     *
     * initSign is still attempted once before the prompt, ONLY to detect a permanently
     * invalidated key early (§9 — a dead key cannot be revived by any prompt).
     *
     * The prompt must be raised on the main thread: every caller reaches this from
     * `Dispatchers.IO`, and BiometricPrompt's FragmentManager throws "Must be called from main
     * thread of fragment host" if constructed or authenticated off-main.
     */
    /** Owner report 2026-11-02: the Keyguard confirm screen shows a fingerprint pane first and
     *  hides the PIN switch behind SystemUI text that renders invisible in light mode (X6532C).
     *  API 30+ can raise the credential-only system prompt instead, which opens directly on the
     *  PIN/pattern/password entry — no switch, nothing for the OEM theme to paint wrong. */
    private suspend fun deviceCredentialSign(title: String): BiometricOutcome =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            biometricSign(title, BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        } else {
            BiometricOutcome.Cancelled
        }

    private suspend fun biometricSign(
        title: String,
        authenticators: Int = BiometricManager.Authenticators.BIOMETRIC_STRONG,
    ): BiometricOutcome {
        val signature = Signature.getInstance(AccountabilitySigner.SIGNATURE_ALGORITHM)
        val probe = runCatching { signature.initSign(accountabilityKeyStore.signingPrivateKey()) }
        probe.exceptionOrNull()?.let { failure ->
            if (accountabilitySigner.isPermanentlyInvalidated(failure)) {
                Log.w(TAG, "signing key permanently invalidated before the prompt", failure)
                return BiometricOutcome.PermanentlyInvalidated
            }
        }
        val credentialOnly = authenticators == BiometricManager.Authenticators.DEVICE_CREDENTIAL
        return withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { cont ->
                val prompt = BiometricPrompt(
                    this@MainActivity,
                    ContextCompat.getMainExecutor(this@MainActivity),
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
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

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            Log.w(TAG, "approval biometric prompt: $errString")
                            val outcome = when (errorCode) {
                                // Owner report 2026-11-02: the sheet's own credential link
                                // is painted by SystemUI and can be invisible in light
                                // mode (TranSystemUI, X6532C), so the visible app-labeled
                                // negative button carries the screen-lock route instead.
                                BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                                -> BiometricOutcome.CredentialRequested

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
                // Owner ruling 2026-11-02 supersedes #17's in-sheet credential entry:
                // DEVICE_CREDENTIAL rendered its "Use Numeric Password" link through
                // SystemUI's own (untheable) style, which paints invisible on some light-mode
                // skins. The biometric sheet therefore carries an app-labeled negative button
                // (visible text path) for the screen-lock route, and the credential-only
                // prompt opens straight on PIN entry. Neither prompt is crypto-bound (owner
                // ruling 2026-10-04); the key's 900s window authorizes the initSign in the
                // success callback instead. A negative button is forbidden with
                // DEVICE_CREDENTIAL (PromptInfo.build() throws).
                val promptInfo = BiometricPrompt.PromptInfo.Builder()
                    .setTitle(title)
                    .setSubtitle(getString(R.string.partner_approval_prompt_subtitle))
                    .setAllowedAuthenticators(authenticators)
                    .apply {
                        if (!credentialOnly) {
                            setNegativeButtonText(getString(R.string.partner_signing_use_pin))
                        }
                    }
                    .build()
                prompt.authenticate(promptInfo)
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
        // API 30+: credential-only BiometricPrompt opens directly on PIN/pattern entry. The
        // KeyguardManager screen below leads with a fingerprint pane whose "Use Numeric
        // Password" switch is painted by SystemUI and reads invisible in light mode (X6532C).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return when (val outcome = deviceCredentialSign(title)) {
                is BiometricOutcome.Signed -> outcome.signature
                BiometricOutcome.PermanentlyInvalidated -> {
                    onKeyPermanentlyInvalidated()
                    null
                }
                BiometricOutcome.LockedOut -> {
                    withContext(Dispatchers.Main.immediate) {
                        biometricUnavailable = NoSignatureReason.SCREEN_LOCK_REJECTED
                    }
                    null
                }
                BiometricOutcome.CredentialRequested,
                BiometricOutcome.Cancelled,
                -> null
            }
        }
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

    /** True while the §8.1 900 s signing window is open — [AccountabilitySigner.beginSign]
     *  initializes the auth-bound cipher without a prompt only inside the window. Any failure
     *  (cold window, dead keystore) reads as not-warm; the prompted path owns both cases. */
    private fun signingWindowWarm(): Boolean =
        runCatching { accountabilitySigner.beginSign() }.isSuccess

    /** Send-kick truth check (owner UX ruling 2026-10-01): after the pass, is anything for
     *  [pairId] still queued? A message the drain delivered is linked out of the journal AND
     *  marked SENT in the outbox — either queue non-empty means something (worst case: our
     *  message) is still on this phone, so the composer must not claim [MessageSendOutcome.SENT].
     *  A read failure reads as queued too: over-reporting "will send later" is honest, a false
     *  "sent" is not. */
    private suspend fun messageQueuedForPair(store: AppRulesStore?, pairId: String): Boolean = runCatching {
        val sinceMs = enrollmentDao.all().firstOrNull { it.pairId == pairId }?.createdAtEpochMillis
        store == null || sinceMs == null ||
            store.partnerActivityDao().pendingForPair(pairId, sinceMs, 1).isNotEmpty() ||
            accountabilityDb.outboxDao().pendingFor(pairId, 1).isNotEmpty()
    }.getOrDefault(true)

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

        /** How often [startPartnerLiveSync] re-checks its gate while visible but not yet live
         *  (no pairing yet, or the kill switch is off). */
        private const val PARTNER_LIVE_RECHECK_MILLIS = 30_000L

        /** Milestone notification deep link: which habit's Home to show. */
        const val EXTRA_START_HABIT = "extra_start_habit"

        /** ADR #193 deep link: the partner plan the notification was about. */
        const val EXTRA_OPEN_PLAN = "extra_open_plan"

        /** Owner report 2026-10-31: the exact partner a notification was about. */
        const val EXTRA_OPEN_PARTNER = "extra_open_partner"

        /** Owner ask 2026-11-02: the Partner subpage a notification was about (a
         *  `PartnerScreen` VIEW_* id) — the tap lands on it, not the hub. */
        const val EXTRA_OPEN_PARTNER_VIEW = "extra_open_partner_view"

        /** Which surface opened the Recovery tab — a `RecoverySession.ENTRY_*` constant. */
        const val EXTRA_RECOVERY_ENTRY = "extra_recovery_entry"

        /** Plan §E2.2 / owner add-on 2026-11-07: land directly on Urge Assist instead of the
         *  hub. Carried by the block screen and the widget's assist action. */
        const val EXTRA_OPEN_ASSIST = "extra_open_assist"

        /** Plan revision 2026-11-06: an update notification asks for the Settings dialog. */
        const val EXTRA_OPEN_UPDATE = "extra_open_update"

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

        /** #26: tunnel paused/revoked notifications land here — the Rules screen holds the
         *  activation row whose tap re-opens the system consent dialog. */
        const val TAB_RULES = NavKeys.RULES

        /** ADR #193: shared-plan notifications land on the Partner tab, whose Plans subpage
         *  holds the checklist the alert is about. */
        const val TAB_PARTNER = NavKeys.PARTNER

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
private fun UpdateRequiredScreen(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(R.string.update_required_title),
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(8.dp))
        Text(message)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text(stringResource(R.string.update_required_retry)) }
    }
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

