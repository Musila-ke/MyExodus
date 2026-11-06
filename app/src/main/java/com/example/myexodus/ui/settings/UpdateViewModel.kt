package com.example.myexodus.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myexodus.BuildConfig
import com.example.myexodus.update.ApkDownloader
import com.example.myexodus.update.ApkVerifier
import com.example.myexodus.update.InstallStatus
import com.example.myexodus.update.ManifestVerification
import com.example.myexodus.update.UpdateCheckResult
import com.example.myexodus.update.UpdateChecker
import com.example.myexodus.update.UpdateCheckWorker
import com.example.myexodus.update.UpdateConfig
import com.example.myexodus.update.UpdateException
import com.example.myexodus.update.UpdateFailure
import com.example.myexodus.update.UpdateInstaller
import com.example.myexodus.update.UpdateManifest
import com.example.myexodus.update.UpdateStateStore
import com.example.myexodus.update.verifyManifest
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import javax.inject.Inject

enum class UpdatePhase {
    IDLE,
    NOT_CONFIGURED,
    CHECKING,
    UP_TO_DATE,
    NO_RELEASE,
    AVAILABLE,
    DOWNLOADING,
    INSTALLING,
    FAILED,
}

data class UpgradeNotice(val notes: String?)

data class UpdateUiState(
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val availableVersionName: String? = null,
    val notes: String? = null,
    val progressPercent: Int? = null,
    val failure: UpdateFailure? = null,
    val lastCheckedAtMs: Long = 0L,
    val canInstall: Boolean = false,
    val notice: UpgradeNotice? = null,
)

@HiltViewModel
class UpdateViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val store = UpdateStateStore(context)
    private val installer = UpdateInstaller(context)
    private val configured = UpdateConfig.MANIFEST_PUBLIC_KEY_BASE64.isNotBlank()
    private val _state = MutableStateFlow(UpdateUiState(canInstall = installer.canInstall()))
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private var availableManifest: UpdateManifest? = null
    private var busy = false

    init {
        val installed = BuildConfig.VERSION_CODE.toLong()
        val storedEnvelope = store.availableEnvelope()
        val storedManifest = storedEnvelope?.let {
            (verifyManifest(it, UpdateConfig.MANIFEST_PUBLIC_KEY_BASE64) as? ManifestVerification.Valid)
                ?.manifest
        }
        val upgraded = store.recordLaunch(BuildConfig.VERSION_CODE)
        val notice = if (upgraded) {
            UpgradeNotice(
                storedManifest
                    ?.takeIf { it.versionCode == installed }
                    ?.notes
                    ?.takeIf { it.isNotBlank() },
            )
        } else {
            null
        }
        if (storedManifest != null && storedManifest.versionCode > installed) {
            availableManifest = storedManifest
        } else if (storedEnvelope != null) {
            store.clearAvailable()
        }
        val failure = store.lastFailure()
        _state.update {
            it.copy(
                phase = when {
                    !configured -> UpdatePhase.NOT_CONFIGURED
                    availableManifest != null -> UpdatePhase.AVAILABLE
                    failure == UpdateFailure.NO_RELEASE -> UpdatePhase.NO_RELEASE
                    failure != null -> UpdatePhase.FAILED
                    store.lastCheckAtMs() > 0 -> UpdatePhase.UP_TO_DATE
                    else -> UpdatePhase.IDLE
                },
                availableVersionName = availableManifest?.versionName,
                notes = availableManifest?.notes?.takeIf { note -> note.isNotBlank() },
                failure = failure,
                lastCheckedAtMs = store.lastCheckAtMs(),
                notice = notice,
            )
        }
        reconcileInstallStatus()
    }

    fun onResume() {
        reconcileInstallStatus()
        val installed = BuildConfig.VERSION_CODE.toLong()
        if (availableManifest != null && availableManifest!!.versionCode <= installed) {
            availableManifest = null
            store.clearAvailable()
            _state.update {
                it.copy(phase = UpdatePhase.UP_TO_DATE, availableVersionName = null, notes = null)
            }
        }
        _state.update { it.copy(canInstall = installer.canInstall()) }
    }

    fun checkForUpdates() {
        if (busy) return
        if (!configured) {
            _state.update { it.copy(phase = UpdatePhase.NOT_CONFIGURED) }
            return
        }
        busy = true
        _state.update { it.copy(phase = UpdatePhase.CHECKING, failure = null, progressPercent = null) }
        viewModelScope.launch {
            val installed = BuildConfig.VERSION_CODE.toLong()
            val result = withContext(Dispatchers.IO) { UpdateChecker().check(installed) }
            val now = System.currentTimeMillis()
            store.recordCheck(result, now)
            busy = false
            when (result) {
                is UpdateCheckResult.Available -> {
                    availableManifest = result.manifest
                    _state.update {
                        it.copy(
                            phase = UpdatePhase.AVAILABLE,
                            availableVersionName = result.manifest.versionName,
                            notes = result.manifest.notes.takeIf { note -> note.isNotBlank() },
                            failure = null,
                            lastCheckedAtMs = now,
                        )
                    }
                }
                UpdateCheckResult.UpToDate -> {
                    availableManifest = null
                    _state.update {
                        it.copy(
                            phase = UpdatePhase.UP_TO_DATE,
                            availableVersionName = null,
                            notes = null,
                            failure = null,
                            lastCheckedAtMs = now,
                        )
                    }
                }
                UpdateCheckResult.NotConfigured ->
                    _state.update { it.copy(phase = UpdatePhase.NOT_CONFIGURED) }
                is UpdateCheckResult.Failure -> _state.update {
                    it.copy(
                        phase = if (result.reason == UpdateFailure.NO_RELEASE) {
                            UpdatePhase.NO_RELEASE
                        } else {
                            UpdatePhase.FAILED
                        },
                        failure = result.reason,
                        lastCheckedAtMs = now,
                    )
                }
            }
        }
    }

    fun downloadAndInstall() {
        val manifest = availableManifest ?: return
        if (busy) return
        if (!installer.canInstall()) {
            _state.update { it.copy(canInstall = false) }
            runCatching { installer.openPermissionSettings() }.onFailure {
                _state.update { s -> s.copy(phase = UpdatePhase.FAILED, failure = UpdateFailure.PERMISSION) }
            }
            return
        }
        busy = true
        _state.update { it.copy(phase = UpdatePhase.DOWNLOADING, progressPercent = 0, failure = null) }
        viewModelScope.launch {
            try {
                val dir = UpdateCheckWorker.updatesDir(context)
                val url = URL(URL(UpdateConfig.BASE_URL), manifest.apkAssetName)
                val file = withContext(Dispatchers.IO) {
                    ApkDownloader(dir).download(url, manifest) { downloaded ->
                        val percent = ((downloaded * 100) / manifest.apkSizeBytes)
                            .toInt()
                            .coerceIn(0, 100)
                        if (percent != _state.value.progressPercent) {
                            _state.update { it.copy(progressPercent = percent) }
                        }
                    }
                }
                withContext(Dispatchers.IO) {
                    ApkVerifier(context).verify(file, manifest, BuildConfig.VERSION_CODE.toLong())
                }
                store.recordInstallStart(System.currentTimeMillis())
                busy = false
                _state.update { it.copy(phase = UpdatePhase.INSTALLING, progressPercent = null) }
                withContext(Dispatchers.IO) { installer.install(file) }
            } catch (e: UpdateException) {
                busy = false
                // The session never committed: a stored PENDING would read as INSTALLING on the
                // next resume and bury this failure.
                store.clearInstallStatus()
                if (e.failure in DISCARD_STAGED) {
                    runCatching {
                        File(UpdateCheckWorker.updatesDir(context), "${manifest.versionCode}.apk").delete()
                    }
                }
                _state.update {
                    it.copy(phase = UpdatePhase.FAILED, failure = e.failure, progressPercent = null)
                }
            } catch (e: Exception) {
                busy = false
                store.clearInstallStatus()
                _state.update {
                    it.copy(phase = UpdatePhase.FAILED, failure = UpdateFailure.INSTALL, progressPercent = null)
                }
            }
        }
    }

    fun retry() {
        val failure = _state.value.failure
        val downloadFailure = failure in DISCARD_STAGED ||
            failure == UpdateFailure.DOWNLOAD ||
            failure == UpdateFailure.PERMISSION ||
            failure == UpdateFailure.INSTALL ||
            failure == UpdateFailure.STORAGE
        if (availableManifest != null && downloadFailure) downloadAndInstall() else checkForUpdates()
    }

    fun openInstallSettings() {
        runCatching { installer.openPermissionSettings() }.onFailure {
            _state.update { s -> s.copy(failure = UpdateFailure.PERMISSION) }
        }
    }

    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }

    private fun reconcileInstallStatus() {
        when (val status = store.installStatus()) {
            null -> Unit
            InstallStatus.SUCCEEDED -> store.clearInstallStatus()
            InstallStatus.PENDING -> {
                val started = store.installStartedAtMs()
                if (started > 0 && System.currentTimeMillis() - started > INSTALL_TIMEOUT_MS) {
                    store.clearInstallStatus()
                    _state.update { it.copy(phase = UpdatePhase.FAILED, failure = UpdateFailure.INSTALL) }
                } else {
                    _state.update { it.copy(phase = UpdatePhase.INSTALLING) }
                }
            }
            InstallStatus.FAILED_ABORTED -> {
                store.clearInstallStatus()
                _state.update { it.copy(phase = UpdatePhase.AVAILABLE) }
            }
            InstallStatus.FAILED_BLOCKED -> installFailed(UpdateFailure.PERMISSION)
            InstallStatus.FAILED_STORAGE -> installFailed(UpdateFailure.STORAGE)
            else -> installFailed(UpdateFailure.INSTALL)
        }
    }

    private fun installFailed(reason: UpdateFailure) {
        store.clearInstallStatus()
        _state.update { it.copy(phase = UpdatePhase.FAILED, failure = reason) }
    }

    private companion object {
        const val INSTALL_TIMEOUT_MS = 30L * 60 * 1000
        val DISCARD_STAGED = setOf(
            UpdateFailure.INTEGRITY,
            UpdateFailure.INVALID_APK,
            UpdateFailure.WRONG_PACKAGE,
            UpdateFailure.VERSION_MISMATCH,
            UpdateFailure.DOWNGRADE,
            UpdateFailure.SIGNER_MISMATCH,
        )
    }
}
