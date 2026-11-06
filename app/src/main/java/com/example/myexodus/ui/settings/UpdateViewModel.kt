package com.example.myexodus.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myexodus.R
import com.example.myexodus.update.AttemptError
import com.example.myexodus.update.AttemptState
import com.example.myexodus.update.AvailablePrompt
import com.example.myexodus.update.CheckStatus
import com.example.myexodus.update.DisplayStatus
import com.example.myexodus.update.DownloadSnapshot
import com.example.myexodus.update.InstallResult
import com.example.myexodus.update.StartResult
import com.example.myexodus.update.UpdateConfig
import com.example.myexodus.update.UpdateCoordinator
import com.example.myexodus.update.UpdateSnapshot
import com.example.myexodus.update.UpgradeNotice
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class LastOutcome { NONE, SUCCEEDED, FAILED, UNKNOWN }

enum class UpdatePhase {
    IDLE,
    NOT_CONFIGURED,
    CHECKING,
    UP_TO_DATE,
    HALTED,
    NOT_IN_ROLLOUT,
    CHECK_STALE,
    AVAILABLE,
    DOWNLOADING,
    PAUSED,
    VERIFYING,
    READY_TO_INSTALL,
    NEEDS_PERMISSION,
    STAGING,
    INSTALLING,
    AWAITING_CONFIRMATION,
    STATUS_UNKNOWN,
    FAILED,
}

data class UpdateUiState(
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val installedVersionName: String = "",
    val availableVersionName: String? = null,
    val notes: String? = null,
    val sizeBytes: Long? = null,
    val progressPercent: Int? = null,
    val error: AttemptError? = null,
    val checkError: AttemptError? = null,
    val lastCheckedAtMs: Long = 0L,
    val lastOutcome: LastOutcome = LastOutcome.NONE,
    val lastOutcomeError: AttemptError? = null,
    val canInstall: Boolean = false,
    val metered: Boolean = false,
    val roaming: Boolean = false,
    val busy: Boolean = false,
    val protectionResumeRequired: Boolean = false,
    val notice: UpgradeNotice? = null,
    val autoDownload: Boolean = false,
    /** The one-time in-app prompt; null once dismissed or already shown for this version. */
    val availablePrompt: AvailablePrompt? = null,
    /** Monotonic token: a notification tap asks the Settings dialog to open. */
    val openDialogRequest: Long = 0,
)

@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val coordinator: UpdateCoordinator,
) : ViewModel() {
    private val _state = MutableStateFlow(UpdateUiState())
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private var pollJob: Job? = null
    private var busy = false

    init {
        _state.update { it.copy(notice = coordinator.takeUpgradeNotice()) }
        viewModelScope.launch {
            reload()
            // Plan revision 2026-11-06: one prompt per offered version, on app open only.
            withContext(Dispatchers.IO) { coordinator.takeAvailablePrompt() }?.let { prompt ->
                _state.update { it.copy(availablePrompt = prompt) }
            }
        }
    }

    fun dismissAvailablePrompt() {
        _state.update { it.copy(availablePrompt = null) }
    }

    fun requestOpenDialog() {
        _state.update { it.copy(openDialogRequest = it.openDialogRequest + 1) }
    }

    fun consumeOpenDialog() {
        _state.update { it.copy(openDialogRequest = 0) }
    }

    fun setAutoDownload(enabled: Boolean) {
        coordinator.setAutoDownload(enabled)
        viewModelScope.launch { reload() }
    }

    fun onResume() {
        viewModelScope.launch { reload() }
    }

    fun checkForUpdates() {
        if (busy) return
        busy = true
        _state.update {
            it.copy(
                busy = true,
                phase = if (it.phase in ACTIVE_PHASES) it.phase else UpdatePhase.CHECKING,
                error = null,
            )
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { coordinator.check(manual = true) }
            busy = false
            reload()
        }
    }

    fun download(meteredConsent: Boolean) {
        if (busy) return
        busy = true
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { coordinator.startDownload(meteredConsent) }
            busy = false
            reload()
            when (result) {
                StartResult.Started -> startPolling()
                is StartResult.Failed -> _state.update { it.copy(error = result.failure) }
                StartResult.AlreadyActive -> Unit
            }
        }
    }

    fun install() {
        if (busy) return
        busy = true
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { coordinator.install() }
            busy = false
            if (result == InstallResult.NeedsPermission) coordinator.openPermissionSettings()
            reload()
            if (result is InstallResult.Failed) {
                _state.update { it.copy(error = result.failure) }
            }
        }
    }

    fun retry() {
        val current = _state.value
        when {
            current.phase == UpdatePhase.NEEDS_PERMISSION -> coordinator.openPermissionSettings()
            current.phase == UpdatePhase.READY_TO_INSTALL -> install()
            current.phase == UpdatePhase.STATUS_UNKNOWN -> viewModelScope.launch {
                withContext(Dispatchers.IO) { coordinator.reconcileAfterReplace() }
                reload()
            }
            // §9: retry only transient failures; an authority rejection needs a fresh check.
            current.phase == UpdatePhase.FAILED &&
                current.error != null &&
                current.error !in AUTHORITY_ERRORS -> download(current.metered)
            else -> checkForUpdates()
        }
    }

    fun cancelDownload() {
        coordinator.cancelAttempt()
        pollJob?.cancel()
        viewModelScope.launch { reload() }
    }

    fun continueInstall(context: Context) {
        coordinator.continueInstallIntent()?.let {
            runCatching { context.startActivity(it) }
        }
    }

    fun openReleasePage(context: Context) {
        val intent = Intent(Intent.ACTION_VIEW, UpdateConfig.releasePageUrl.toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun copyDiagnostics(context: Context) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(
            ClipData.newPlainText("MyExodus updater diagnostics", coordinator.diagnosticSummary()),
        )
        Toast.makeText(context, R.string.settings_update_diagnostics_copied, Toast.LENGTH_SHORT).show()
    }

    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }

    private suspend fun reload() {
        val snapshot = withContext(Dispatchers.IO) { coordinator.snapshot() }
        _state.update { snapshot.toUi(it) }
        val state = snapshot.attempt?.state
        if (state == AttemptState.DOWNLOADING || state == AttemptState.DOWNLOAD_STARTING) {
            startPolling()
        }
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                val attempt = withContext(Dispatchers.IO) { coordinator.refreshDownload() }
                reload()
                val state = attempt?.state
                if (state != AttemptState.DOWNLOADING && state != AttemptState.DOWNLOAD_STARTING) break
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun UpdateSnapshot.toUi(previous: UpdateUiState): UpdateUiState {
        val phase = phaseOf()
        val activish = phase in ACTIVE_PHASES
        val shownVersion = when {
            phase == UpdatePhase.AVAILABLE -> release?.versionName
            activish -> attempt?.versionName
            else -> null
        }
        return UpdateUiState(
            phase = phase,
            installedVersionName = installedVersionName,
            availableVersionName = shownVersion,
            notes = release?.releaseNotes?.takeIf { phase == UpdatePhase.AVAILABLE && it.isNotBlank() },
            sizeBytes = release?.apkSizeBytes ?: attempt?.apkSizeBytes,
            progressPercent = percent(progress),
            error = attempt?.error?.takeIf {
                phase == UpdatePhase.FAILED ||
                    phase == UpdatePhase.NEEDS_PERMISSION ||
                    phase == UpdatePhase.STATUS_UNKNOWN
            },
            checkError = checkError,
            lastCheckedAtMs = lastSuccessAtMs,
            lastOutcome = when (attempt?.state) {
                AttemptState.SUCCEEDED -> LastOutcome.SUCCEEDED
                AttemptState.FAILED -> LastOutcome.FAILED
                AttemptState.UPDATE_STATUS_UNKNOWN -> LastOutcome.UNKNOWN
                else -> LastOutcome.NONE
            },
            lastOutcomeError = attempt?.takeIf { it.state == AttemptState.FAILED }?.error,
            canInstall = canInstall,
            metered = metered,
            roaming = roaming,
            busy = busy,
            protectionResumeRequired = protectionResumeRequired,
            notice = previous.notice,
            autoDownload = autoDownload,
            availablePrompt = previous.availablePrompt,
            openDialogRequest = previous.openDialogRequest,
        )
    }

    private fun UpdateSnapshot.phaseOf(now: Long = System.currentTimeMillis()): UpdatePhase {
        if (!configured) return UpdatePhase.NOT_CONFIGURED
        val active = attempt?.takeIf { it.state.active }
        if (active != null) {
            return when (active.state) {
                AttemptState.DOWNLOAD_STARTING, AttemptState.DOWNLOADING ->
                    if (progress is DownloadSnapshot.Paused) UpdatePhase.PAUSED else UpdatePhase.DOWNLOADING
                AttemptState.VERIFYING -> UpdatePhase.VERIFYING
                AttemptState.READY_TO_INSTALL -> UpdatePhase.READY_TO_INSTALL
                AttemptState.NEEDS_INSTALL_PERMISSION -> UpdatePhase.NEEDS_PERMISSION
                AttemptState.STAGING -> UpdatePhase.STAGING
                AttemptState.INSTALLING -> UpdatePhase.INSTALLING
                AttemptState.AWAITING_USER_CONFIRMATION -> UpdatePhase.AWAITING_CONFIRMATION
                AttemptState.UPDATE_STATUS_UNKNOWN -> UpdatePhase.STATUS_UNKNOWN
                else -> UpdatePhase.IDLE
            }
        }
        // A newer successful check outranks an older failed attempt; otherwise the failure
        // would shadow a re-offered release and leave the dialog with no way forward.
        val failedAttempt = attempt?.takeIf {
            it.state == AttemptState.FAILED && it.updatedAtMs >= lastSuccessAtMs
        }
        return when (display) {
            DisplayStatus.UP_TO_DATE -> UpdatePhase.UP_TO_DATE
            DisplayStatus.HALTED -> UpdatePhase.HALTED
            DisplayStatus.NOT_IN_ROLLOUT -> UpdatePhase.NOT_IN_ROLLOUT
            DisplayStatus.POLICY_ROLLBACK -> UpdatePhase.FAILED
            DisplayStatus.CHECK_STALE -> UpdatePhase.CHECK_STALE
            DisplayStatus.AVAILABLE ->
                if (failedAttempt != null) UpdatePhase.FAILED else UpdatePhase.AVAILABLE
            null -> when {
                checkStatus == CheckStatus.FAILED -> UpdatePhase.FAILED
                checkStatus == CheckStatus.CHECKING &&
                    now - lastCheckAtMs < CHECKING_TIMEOUT_MS -> UpdatePhase.CHECKING
                else -> UpdatePhase.IDLE
            }
        }
    }

    private fun percent(progress: DownloadSnapshot?): Int? {
        val running = progress as? DownloadSnapshot.Running ?: return null
        if (running.totalBytes <= 0) return null
        return ((running.downloadedBytes * 100) / running.totalBytes).toInt().coerceIn(0, 100)
    }

    private companion object {
        const val POLL_INTERVAL_MS = 1000L
        const val CHECKING_TIMEOUT_MS = 2L * 60 * 1000
        val AUTHORITY_ERRORS = setOf(
            AttemptError.NO_STABLE_POLICY,
            AttemptError.POLICY_INVALID,
            AttemptError.POLICY_ROLLBACK,
            AttemptError.POLICY_CONFLICT,
            AttemptError.HALTED,
            AttemptError.NOT_IN_ROLLOUT,
            AttemptError.RELEASE_INCOMPATIBLE,
        )
        val ACTIVE_PHASES = setOf(
            UpdatePhase.CHECKING,
            UpdatePhase.DOWNLOADING,
            UpdatePhase.PAUSED,
            UpdatePhase.VERIFYING,
            UpdatePhase.READY_TO_INSTALL,
            UpdatePhase.NEEDS_PERMISSION,
            UpdatePhase.STAGING,
            UpdatePhase.INSTALLING,
            UpdatePhase.AWAITING_CONFIRMATION,
            UpdatePhase.STATUS_UNKNOWN,
        )
    }
}
