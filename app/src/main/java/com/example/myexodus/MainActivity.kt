package com.example.myexodus

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.example.myexodus.blocking.AttemptRecorder
import com.example.myexodus.blocking.EnhancedModeHealthMonitor
import com.example.myexodus.blocking.EnforcementForegroundService
import com.example.myexodus.blocking.ProtectionStatusMonitor
import com.example.myexodus.blocking.ProtectionStatusStore
import com.example.myexodus.blocking.RulesRepository
import com.example.myexodus.accountability.BilateralDeletion
import com.example.myexodus.accountability.approval.ApprovalFlow
import com.example.myexodus.accountability.crypto.AccountabilityKeyStore
import com.example.myexodus.accountability.crypto.AccountabilitySigner
import com.example.myexodus.accountability.sync.PairUidResolver
import com.example.myexodus.accountability.sync.PartnerSyncCoordinator
import com.example.myexodus.accountability.sync.PartnerRolloutGate
import com.example.myexodus.accountability.sync.PushInstallationRegistrar
import com.example.myexodus.accountability.sync.runPartnerSyncPass
import com.example.myexodus.blocking.recovery.RecoveryStateStore
import com.example.myexodus.blocking.usage.UsageAccountingEngine
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.DatabaseHealth
import com.example.myexodus.data.db.DatabaseHealthStore
import com.example.myexodus.data.db.RecoveryManager
import com.example.myexodus.settings.EnhancedModeSettings
import com.example.myexodus.settings.SetupStateStore
import com.example.myexodus.settings.notificationSettingsIntent
import com.example.myexodus.settings.StrictModeManager
import com.example.myexodus.settings.WidgetSettings
import com.example.myexodus.settings.readProtectionChecks
import com.example.myexodus.telemetry.CrashReporting
import com.example.myexodus.notifications.NotificationAccess
import com.example.myexodus.ui.AppShell
import com.example.myexodus.ui.partner.PartnerDashboardSource
import com.example.myexodus.widget.ProtectionSnapshotWriter
import com.example.myexodus.ui.setup.SetupScreen
import com.example.myexodus.ui.streaks.StreaksViewModel
import com.example.myexodus.ui.theme.MyExodusTheme
import dagger.hilt.android.AndroidEntryPoint
import java.security.Signature
import kotlin.coroutines.resume
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedTab = intent.getStringExtra(EXTRA_START_TAB)
        requestedHabit = intent.getStringExtra(EXTRA_START_HABIT)
    }

    @Inject lateinit var rulesStoreFactory: AppRulesStoreFactory
    @Inject lateinit var rulesRepository: RulesRepository
    @Inject lateinit var usageAccountingEngine: UsageAccountingEngine
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
    // Step 13: approve/reject signs a chain event — the decision itself is the biometric prompt.
    @Inject lateinit var approvalFlow: ApprovalFlow
    // Step 14: the unpair protocol (journal → deliver → shred) plus the uid resolution its
    // mailbox write needs (uid is deliberately never persisted on-device).
    @Inject lateinit var bilateralDeletion: BilateralDeletion
    @Inject lateinit var pairUidResolver: PairUidResolver
    @Inject lateinit var accountabilityKeyStore: AccountabilityKeyStore
    // The dashboard's "Check now" runs the production sync pass in-process — WorkManager
    // dispatch is never performed on XOS (ADR #27), so a WM-based button would be dead there.
    @Inject lateinit var partnerSyncCoordinator: PartnerSyncCoordinator
    @Inject lateinit var pushInstallationRegistrar: PushInstallationRegistrar
    @Inject lateinit var partnerRolloutGate: PartnerRolloutGate
    // Already constructed by MyExodusApp at process start; injected here only to re-publish the
    // snapshot the moment the discreet/detailed toggle flips (widget spec §5 Decision 10).
    @Inject lateinit var widgetSnapshotWriter: ProtectionSnapshotWriter

    private val streaksViewModel: StreaksViewModel by viewModels()

    /** Plan §8 Step 5 / Decision 9: the foreground evaluation trigger lives here, at the
     *  Activity lifecycle boundary — never inside composition. The ViewModel's own `init`
     *  covers the first launch. */
    override fun onResume() {
        super.onResume()
        streaksViewModel.refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedTab = intent.getStringExtra(EXTRA_START_TAB)
        requestedHabit = intent.getStringExtra(EXTRA_START_HABIT)
        enableEdgeToEdge()
        setContent {
            MyExodusTheme {
                // §8.2: permission/degradation state is re-checked on every resume of
                // MainActivity — the banner never relies on a stale read.
                var degradation by remember { mutableStateOf(protectionStatus.degradation) }
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
                val rulesStore = remember { rulesStoreFactory.open() }
                val health = healthStore.health.collectAsStateWithLifecycle().value
                // EncryptionUpgradePending is deliberately usable (plan §8 Step 0: pending
                // conversion must not block the app — it runs on the plaintext store meanwhile).
                val usable = health == DatabaseHealth.Ready ||
                    health == DatabaseHealth.EncryptionUpgradePending
                // V1 ships no onboarding; rules FK a profile row, so the single local profile
                // is created on first launch (idempotent — see RulesRepository).
                LaunchedEffect(health) {
                    if (usable) {
                        runCatching { rulesRepository.ensureActiveProfile() }
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
                } else if (usable) {
                    AppShell(
                        store = rulesStore,
                        encryptionPending = health == DatabaseHealth.EncryptionUpgradePending,
                        repository = rulesRepository,
                        usageAccountingEngine = usageAccountingEngine,
                        recoveryStore = recoveryStore,
                        attempts = attemptRecorder,
                        monitor = monitor,
                        strictMode = strictMode,
                        settings = settings,
                        crashReporting = crashReporting,
                        widgetSettings = widgetSettings,
                        onWidgetSettingsChanged = widgetSnapshotWriter::publish,
                        degradation = degradation,
                        streaks = streaksViewModel,
                        partnerSource = partnerDashboardSource,
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
                            runCatching {
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
                        requestedTab = requestedTab,
                        onTabConsumed = { requestedTab = null },
                        requestedHabit = requestedHabit,
                        onHabitConsumed = { requestedHabit = null },
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
                                lifecycleScope.launch(Dispatchers.IO) { rulesStoreFactory.resetAndReopen() }
                            },
                            onRetry = {
                                lifecycleScope.launch(Dispatchers.IO) { rulesStoreFactory.open() }
                            },
                            modifier = Modifier.padding(innerPadding),
                        )
                    }
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
        val signature = signWithBiometric(title) ?: return false
        return when (approvalFlow.respond(requestEventId, approve, signature)) {
            is ApprovalFlow.RespondResult.Decided -> true
            is ApprovalFlow.RespondResult.Refused -> false
        }
    }

    /**
     * Step 14 initiator: resolve the pair's uids, biometric-gate the signed deletion request,
     * then run [BilateralDeletion.initiate] — journal, deliver, shred. A cancelled prompt or
     * refused signature aborts before anything is journaled, so the pair stays fully intact.
     */
    private suspend fun endPartnership(pairId: String): Boolean {
        val (myUid, partnerUid) = pairUidResolver.resolve(pairId) ?: return false
        val signature = signWithBiometric(getString(R.string.partner_unpair_action))
            ?: return false
        return when (bilateralDeletion.initiate(pairId, myUid, partnerUid, signature)) {
            is BilateralDeletion.DeletionResult.Deleted -> true
            is BilateralDeletion.DeletionResult.Failed -> false
        }
    }

    /**
     * BiometricPrompt signing, same two-path contract the pairing harness proved on the
     * X6532: when initSign succeeds, authenticate with the Signature as a CryptoObject;
     * when keystore2 rejects initSign without a fresh auth token, prompt WITHOUT crypto and
     * initSign inside the success callback (the 900s window, §NFR4, makes that valid).
     */
    private suspend fun signWithBiometric(title: String): Signature? = suspendCancellableCoroutine { cont ->
        val signature = Signature.getInstance(AccountabilitySigner.SIGNATURE_ALGORITHM)
        val cryptoReady = runCatching { signature.initSign(accountabilityKeyStore.signingPrivateKey()) }.isSuccess
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val signed = if (cryptoReady) {
                        result.cryptoObject?.signature
                    } else {
                        runCatching { signature.initSign(accountabilityKeyStore.signingPrivateKey()) }
                            .map { signature }
                            .getOrNull()
                    }
                    cont.resume(signed)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    Log.w(TAG, "approval biometric prompt: $errString")
                    cont.resume(null)
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
        const val TAB_RECOVERY = "Recovery"

        /** Milestone notifications land here: the splash card renders at the top of Home
         *  (plan §8 Step 5), so the notification's "open the app" call-to-action must show it. */
        const val TAB_HOME = "Home"

        /** Enforcement degradation alerts (§8 Step 6) land here: the Settings protection
         *  rows name each mechanism and carry its fix. */
        const val TAB_SETTINGS = "Settings"

        private const val TAG = "MyExodus"
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
            title = { Text("Reset MyExodus data?") },
            text = { Text("This permanently deletes all local data and creates a fresh, empty database. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showResetDialog = false
                    onReset()
                }) { Text("Reset") }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text("Cancel") }
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
                Text("Opening MyExodus…")
            }
            DatabaseHealth.Ready -> Unit
            DatabaseHealth.EncryptionUpgradePending -> {
                Text("Data protection upgrade pending", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text("MyExodus will finish securing your local data on a future launch. Try again now, or keep using the app.")
                Spacer(Modifier.height(16.dp))
                Button(onClick = onRetry) { Text("Try again") }
            }
            DatabaseHealth.RecoverableDataFound -> {
                Text("Recoverable data found", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text("A backup of a previously corrupted database exists on this device.")
                Spacer(Modifier.height(16.dp))
                Button(onClick = onDiscardCorrupt) { Text("Discard recoverable data") }
            }
            DatabaseHealth.DatabaseIssue -> {
                Text("Database issue detected", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text("Your database file could not be read. You can reset it, but existing data will be lost.")
                Spacer(Modifier.height(16.dp))
                Button(onClick = { showResetDialog = true }) { Text("Reset MyExodus data") }
            }
            DatabaseHealth.KeyUnavailable -> {
                Text("Data unavailable", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text("The encryption key for your data could not be recovered on this device. Reset to start fresh.")
                Spacer(Modifier.height(16.dp))
                Button(onClick = { showResetDialog = true }) { Text("Reset MyExodus data") }
            }
            DatabaseHealth.Transient -> {
                Text("Storage problem", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text("The database could not be opened, possibly due to low storage. Try again, or reset.")
                Spacer(Modifier.height(16.dp))
                Button(onClick = onRetry) { Text("Try again") }
                Spacer(Modifier.height(8.dp))
                Button(onClick = { showResetDialog = true }) { Text("Reset MyExodus data") }
            }
        }
    }
}

