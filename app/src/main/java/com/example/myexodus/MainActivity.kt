package com.example.myexodus

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
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
import com.example.myexodus.blocking.recovery.RecoveryStateStore
import com.example.myexodus.blocking.usage.UsageAccountingEngine
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.DatabaseHealth
import com.example.myexodus.data.db.DatabaseHealthStore
import com.example.myexodus.data.db.RecoveryManager
import com.example.myexodus.settings.EnhancedModeSettings
import com.example.myexodus.settings.SetupStateStore
import com.example.myexodus.settings.StrictModeManager
import com.example.myexodus.settings.readProtectionChecks
import com.example.myexodus.telemetry.CrashReporting
import com.example.myexodus.ui.AppShell
import com.example.myexodus.ui.setup.SetupScreen
import com.example.myexodus.ui.streaks.StreaksViewModel
import com.example.myexodus.ui.theme.MyExodusTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

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
                DisposableEffect(Unit) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            refreshProtectionChecks()
                            degradation = protectionStatus.degradation
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
                        degradation = degradation,
                        streaks = streaksViewModel,
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

