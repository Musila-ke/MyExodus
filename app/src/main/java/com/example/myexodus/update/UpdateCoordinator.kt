package com.example.myexodus.update

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.myexodus.BuildConfig
import com.example.myexodus.blocking.ProtectionAuthority
import com.example.myexodus.blocking.ProtectionStatusMonitor
import com.example.myexodus.blocking.ProtectionStatusStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

internal enum class CheckOutcome { UPDATED, THROTTLED, NOT_CONFIGURED, FAILED }

internal sealed interface StartResult {
    data object Started : StartResult
    data object AlreadyActive : StartResult
    data class Failed(val failure: AttemptError) : StartResult
}

internal sealed interface InstallResult {
    data object Started : InstallResult
    data object AlreadyInstalled : InstallResult
    data object NeedsPermission : InstallResult
    data class Failed(val failure: AttemptError) : InstallResult
}

/** Public because [com.example.myexodus.ui.settings.UpdateUiState] carries it. */
data class UpgradeNotice(val notes: String?)

/** One in-app prompt per offered versionCode (2026-11-06 revision). */
data class AvailablePrompt(val versionName: String, val versionCode: Long)

/**
 * 2026-11-06 revision: automatic transfer only on unmetered, non-roaming networks, and only
 * when the standing consent is on. Every other gate in [UpdateCoordinator.startDownloadLocked]
 * still applies.
 */
internal fun shouldAutoDownload(
    enabled: Boolean,
    display: DisplayStatus?,
    network: NetworkState,
): Boolean = enabled &&
    display == DisplayStatus.AVAILABLE &&
    network.connected &&
    !network.metered &&
    !network.roaming

internal data class UpdateSnapshot(
    val configured: Boolean,
    val installedVersionCode: Long,
    val installedVersionName: String,
    val checkStatus: CheckStatus,
    val display: DisplayStatus?,
    val release: UpdateRelease?,
    val lastCheckAtMs: Long,
    val lastSuccessAtMs: Long,
    val checkError: AttemptError?,
    val attempt: UpdateAttempt?,
    val progress: DownloadSnapshot?,
    val canInstall: Boolean,
    val metered: Boolean,
    val roaming: Boolean,
    val autoDownload: Boolean,
    val protectionResumeRequired: Boolean,
)

/**
 * The single main-process owner of updater state (plan §8.3). The [Mutex] serializes
 * in-process commands only; ownership across process death and duplicate callbacks is
 * enforced by the persisted attempt/session ids.
 */
@Singleton
class UpdateCoordinator @Inject constructor(
    @ApplicationContext context: Context,
    private val protectionMonitor: ProtectionStatusMonitor,
    private val protectionStore: ProtectionStatusStore,
) {
    private val appContext = context.applicationContext
    private val store = UpdateStateStore(appContext)
    private val checker = UpdateChecker(packageName = appContext.packageName)
    private val downloads = UpdateDownloadController(appContext)
    private val installer = UpdateInstaller(appContext)
    private val verifier = ApkVerifier(appContext)
    private val diagnostics = UpdateDiagnostics(appContext)
    private val bus = Mutex()

    private var cachedEnvelope: String? = null
    private var cachedPolicy: UpdatePolicy? = null
    private var pendingNotice: UpgradeNotice? = null

    val configured: Boolean get() = UpdateConfig.configured

    internal fun snapshot(): UpdateSnapshot {
        val now = System.currentTimeMillis()
        val installed = installedVersionCode(appContext)
        val display = effectiveDisplay(now)
        val policy = verifiedPolicy()
        val attempt = store.activeAttempt()
        val network = downloads.networkState()
        return UpdateSnapshot(
            configured = configured,
            installedVersionCode = installed,
            installedVersionName = BuildConfig.VERSION_NAME,
            checkStatus = store.checkStatus(),
            display = display,
            release = policy?.release?.takeIf { display == DisplayStatus.AVAILABLE },
            lastCheckAtMs = store.lastCheckAtMs(),
            lastSuccessAtMs = store.lastSuccessAtMs(),
            checkError = store.lastCheckError(),
            attempt = attempt,
            progress = progress(),
            canInstall = installer.canInstall(),
            metered = network.metered,
            roaming = network.roaming,
            autoDownload = store.autoDownloadEnabled(),
            protectionResumeRequired = protectionResumeRequired(),
        )
    }

    /** First main-process touch of the updater record (plan §8.6 startup reconciliation). */
    fun onMainProcessStart() {
        val installed = installedVersionCode(appContext)
        store.ensureVersionFloor(installed)
        downloads.sweepOtherAttempts(store.activeAttempt()?.takeIf { it.state.active }?.attemptId)
        if (store.recordLaunch(installed)) {
            val notes = verifiedPolicy()
                ?.release
                ?.takeIf { it.versionCode == installed }
                ?.releaseNotes
                ?.takeIf { it.isNotBlank() }
            pendingNotice = UpgradeNotice(notes)
        }
        syncProtectionMarker()
        reconcileOwnedAttempt()
    }

    internal fun takeUpgradeNotice(): UpgradeNotice? =
        pendingNotice.also { pendingNotice = null }

    internal suspend fun check(manual: Boolean): CheckOutcome = bus.withLock {
        if (!configured) return CheckOutcome.NOT_CONFIGURED
        val now = System.currentTimeMillis()
        if (manual &&
            store.checkStatus() == CheckStatus.SUCCESS &&
            now - store.lastSuccessAtMs() < UpdateConfig.MANUAL_THROTTLE_MS
        ) {
            return CheckOutcome.THROTTLED
        }
        store.markChecking(now)
        val installed = installedVersionCode(appContext)
        store.ensureVersionFloor(installed)
        return when (val fetched = withContext(Dispatchers.IO) { checker.fetchPolicy() }) {
            PolicyFetch.NotConfigured -> CheckOutcome.NOT_CONFIGURED
            is PolicyFetch.Failed -> {
                store.recordCheckFailure(fetched.failure, now)
                diagnostics.record("check_failed", nowMs = now)
                CheckOutcome.FAILED
            }
            is PolicyFetch.Verified -> {
                val floor = store.recordVerifiedPolicy(fetched.policy, fetched.payloadHash, installed)
                if (floor != FloorResult.Accepted) {
                    val failure = if (floor == FloorResult.Conflict) {
                        AttemptError.POLICY_CONFLICT
                    } else {
                        AttemptError.POLICY_ROLLBACK
                    }
                    store.recordPolicyRejection(failure, now)
                    diagnostics.record(
                        code = "policy_rejected",
                        policySequence = fetched.policy.policySequence,
                        mode = fetched.policy.mode.name,
                        nowMs = now,
                    )
                    CheckOutcome.FAILED
                } else {
                    cache(fetched.envelopeJson, fetched.policy)
                    store.recordCheckSuccess(now, project(fetched.policy, installed), fetched.envelopeJson)
                    diagnostics.record(
                        code = "check_ok",
                        policySequence = fetched.policy.policySequence,
                        mode = fetched.policy.mode.name,
                        versionCode = fetched.policy.release?.versionCode,
                        nowMs = now,
                    )
                    CheckOutcome.UPDATED
                }
            }
        }
    }

    internal suspend fun startDownload(meteredConsent: Boolean): StartResult =
        bus.withLock { startDownloadLocked(meteredConsent) }

    /**
     * 2026-11-06 revision: post-check side effects. A background check notifies when an offer
     * becomes available; with standing consent it also starts the transfer on Wi-Fi. The
     * notification is de-duplicated per offered versionCode.
     */
    internal suspend fun backgroundCheckFinished(foreground: Boolean) = bus.withLock {
        val now = System.currentTimeMillis()
        val display = effectiveDisplay(now)
        val release = verifiedPolicy()?.release ?: return@withLock
        if (display != DisplayStatus.AVAILABLE) return@withLock
        if (!foreground && store.lastAvailableNotifiedVersionCode() < release.versionCode) {
            UpdateNotifications.notifyAvailable(appContext, release.versionName)
            store.markAvailableNotified(release.versionCode)
        }
        if (shouldAutoDownload(store.autoDownloadEnabled(), display, downloads.networkState())) {
            startDownloadLocked(meteredConsent = false)
        }
    }

    /** Returns the in-app prompt once per offered version; null after it was shown. */
    fun takeAvailablePrompt(): AvailablePrompt? {
        val storedDisplay = store.displayStatus() ?: return null
        val display = if (storedDisplay == DisplayStatus.POLICY_ROLLBACK) {
            storedDisplay
        } else {
            effectiveDisplay(System.currentTimeMillis()) ?: return null
        }
        if (display != DisplayStatus.AVAILABLE) return null
        if (store.activeAttempt()?.state?.active == true) return null
        val release = verifiedPolicy()?.release ?: return null
        if (store.availablePromptShownVersionCode() >= release.versionCode) return null
        store.markAvailablePromptShown(release.versionCode)
        return AvailablePrompt(release.versionName, release.versionCode)
    }

    fun setAutoDownload(enabled: Boolean) {
        store.setAutoDownloadEnabled(enabled)
    }

    private suspend fun startDownloadLocked(meteredConsent: Boolean): StartResult {
        val existing = store.activeAttempt()
        if (existing != null && existing.state.active) return StartResult.AlreadyActive
        val policy = verifiedPolicy() ?: return StartResult.Failed(AttemptError.NO_STABLE_POLICY)
        val release = policy.release ?: return StartResult.Failed(AttemptError.HALTED)
        if (policy.mode != PolicyMode.OFFER) return StartResult.Failed(AttemptError.HALTED)
        val installed = installedVersionCode(appContext)
        if (release.versionCode <= installed ||
            release.versionCode < store.highestVersionCode()
        ) {
            return StartResult.Failed(AttemptError.RELEASE_INCOMPATIBLE)
        }
        if (!isInRollout(store.cohortId(), policy)) {
            return StartResult.Failed(AttemptError.NOT_IN_ROLLOUT)
        }
        val network = downloads.networkState()
        if (!network.connected || network.roaming) {
            return StartResult.Failed(AttemptError.NETWORK_UNAVAILABLE)
        }
        if (network.metered && !meteredConsent) {
            return StartResult.Failed(AttemptError.NETWORK_UNAVAILABLE)
        }
        val attemptId = UUID.randomUUID().toString()
        if (downloads.destinationPath(attemptId) == null) {
            return StartResult.Failed(AttemptError.DOWNLOAD_DESTINATION_UNAVAILABLE)
        }
        if (!storageAdequate(release.apkSizeBytes)) {
            return StartResult.Failed(AttemptError.INSUFFICIENT_STORAGE)
        }
        val now = System.currentTimeMillis()
        val attempt = UpdateAttempt(
            attemptId = attemptId,
            state = AttemptState.DOWNLOAD_STARTING,
            policySequence = policy.policySequence,
            versionCode = release.versionCode,
            versionName = release.versionName,
            apkUrl = release.apkUrl,
            apkSha256Hex = release.apkSha256Hex,
            apkSizeBytes = release.apkSizeBytes,
            minSdk = release.minSdk,
            targetSdk = release.targetSdk,
            createdAtMs = now,
            updatedAtMs = now,
            meteredConsent = meteredConsent,
        )
        // Plan §8.4: the attempt is durable before the transfer exists.
        store.saveAttempt(attempt)
        downloads.sweepOtherAttempts(attemptId)
        return when (
            val enqueued = withContext(Dispatchers.IO) {
                downloads.enqueue(attemptId, release.apkUrl, meteredConsent)
            }
        ) {
            is DownloadEnqueue.Enqueued -> {
                store.saveAttempt(
                    attempt.copy(
                        state = AttemptState.DOWNLOADING,
                        downloadId = enqueued.downloadId,
                        destination = downloads.destinationPath(attemptId),
                        updatedAtMs = System.currentTimeMillis(),
                    ),
                )
                diagnostics.record(
                    code = "download_enqueued",
                    policySequence = policy.policySequence,
                    versionCode = release.versionCode,
                    bytes = release.apkSizeBytes,
                )
                StartResult.Started
            }
            is DownloadEnqueue.Failed -> {
                store.saveAttempt(
                    attempt.copy(
                        state = AttemptState.FAILED,
                        error = enqueued.failure,
                        updatedAtMs = System.currentTimeMillis(),
                    ),
                )
                downloads.deleteAttemptFiles(attemptId)
                StartResult.Failed(enqueued.failure)
            }
        }
    }

    /** Polls the owned DownloadManager entry and verifies a completed transfer (plan §8.4). */
    internal suspend fun refreshDownload(): UpdateAttempt? = bus.withLock {
        val attempt = store.activeAttempt() ?: return null
        if (attempt.state != AttemptState.DOWNLOADING &&
            attempt.state != AttemptState.DOWNLOAD_STARTING
        ) {
            return attempt
        }
        val downloadId = attempt.downloadId
        if (downloadId == null) {
            if (attempt.state == AttemptState.DOWNLOAD_STARTING) {
                return failAttempt(attempt, AttemptError.DOWNLOAD_FAILED)
            }
            return attempt
        }
        return when (
            val snapshot = withContext(Dispatchers.IO) { downloads.snapshot(downloadId) }
        ) {
            is DownloadSnapshot.Running -> {
                if (attempt.state != AttemptState.DOWNLOADING) {
                    store.saveAttempt(
                        attempt.copy(
                            state = AttemptState.DOWNLOADING,
                            updatedAtMs = System.currentTimeMillis(),
                        ),
                    )
                }
                store.activeAttempt()
            }
            DownloadSnapshot.Paused -> attempt
            DownloadSnapshot.Missing -> failAttempt(attempt, AttemptError.DOWNLOAD_FAILED)
            is DownloadSnapshot.Failed -> failAttempt(attempt, snapshot.failure)
            DownloadSnapshot.Successful -> verifyDownload(attempt)
        }
    }

    internal suspend fun install(): InstallResult = bus.withLock {
        val attempt = store.activeAttempt() ?: return InstallResult.Failed(AttemptError.INSTALL_FAILED)
        when (attempt.state) {
            AttemptState.AWAITING_USER_CONFIRMATION -> return InstallResult.Started
            AttemptState.READY_TO_INSTALL, AttemptState.NEEDS_INSTALL_PERMISSION -> Unit
            else -> return InstallResult.Failed(AttemptError.INSTALL_FAILED)
        }
        val installed = installedVersionCode(appContext)
        if (installed >= attempt.versionCode) {
            markInstalled(attempt)
            return InstallResult.AlreadyInstalled
        }
        if (!installer.canInstall()) {
            store.saveAttempt(
                attempt.copy(
                    state = AttemptState.NEEDS_INSTALL_PERMISSION,
                    error = AttemptError.NEEDS_INSTALL_PERMISSION,
                    updatedAtMs = System.currentTimeMillis(),
                ),
            )
            return InstallResult.NeedsPermission
        }
        // Plan §8.5: no first commit against cached authority.
        val fetched = withContext(Dispatchers.IO) { checker.fetchPolicy() }
        val verified = fetched as? PolicyFetch.Verified
        if (verified == null) {
            if (fetched is PolicyFetch.Failed) {
                store.recordCheckFailure(fetched.failure, System.currentTimeMillis())
            }
            return deferCommit(attempt)
        }
        val policy = verified.policy
        val release = policy.release
        val authorized = policy.mode == PolicyMode.OFFER &&
            release != null &&
            release.versionCode == attempt.versionCode &&
            release.apkSha256Hex == attempt.apkSha256Hex &&
            release.apkSizeBytes == attempt.apkSizeBytes &&
            release.apkUrl == attempt.apkUrl &&
            policy.policySequence >= attempt.policySequence &&
            isInRollout(store.cohortId(), policy)
        if (!authorized) {
            cache(verified.envelopeJson, policy)
            store.recordVerifiedPolicy(policy, verified.payloadHash, installed)
            store.recordCheckSuccess(
                System.currentTimeMillis(),
                project(policy, installed),
                verified.envelopeJson,
            )
            return deferCommit(attempt)
        }
        if (store.recordVerifiedPolicy(policy, verified.payloadHash, installed) !=
            FloorResult.Accepted
        ) {
            return deferCommit(attempt)
        }
        cache(verified.envelopeJson, policy)
        store.recordCheckSuccess(
            System.currentTimeMillis(),
            project(policy, installed),
            verified.envelopeJson,
        )

        val staging = attempt.copy(
            state = AttemptState.STAGING,
            error = null,
            updatedAtMs = System.currentTimeMillis(),
        )
        store.saveAttempt(staging)
        val outcome = withContext(Dispatchers.IO) {
            installer.stageAndCommit(
                attempt = staging,
                open = { staging.downloadId?.let(downloads::openStream) },
                onSessionCreated = { sessionId ->
                    val current = store.activeAttempt()
                    if (current != null && current.attemptId == staging.attemptId) {
                        store.saveAttempt(
                            current.copy(
                                sessionId = sessionId,
                                updatedAtMs = System.currentTimeMillis(),
                            ),
                        )
                    }
                },
            )
        }
        return when (outcome) {
            UpdateInstaller.StageResult.Committed -> {
                val current = store.activeAttempt()
                if (current != null &&
                    current.attemptId == staging.attemptId &&
                    current.state == AttemptState.STAGING
                ) {
                    store.saveAttempt(
                        current.copy(
                            state = AttemptState.INSTALLING,
                            committed = true,
                            error = null,
                            updatedAtMs = System.currentTimeMillis(),
                        ),
                    )
                }
                diagnostics.record(
                    code = "install_committed",
                    policySequence = staging.policySequence,
                    versionCode = staging.versionCode,
                )
                InstallResult.Started
            }
            is UpdateInstaller.StageResult.Failed -> {
                val current = store.activeAttempt()
                if (current != null &&
                    current.attemptId == staging.attemptId &&
                    current.state == AttemptState.STAGING
                ) {
                    val state = if (outcome.failure == AttemptError.NEEDS_INSTALL_PERMISSION) {
                        AttemptState.NEEDS_INSTALL_PERMISSION
                    } else {
                        AttemptState.FAILED
                    }
                    store.saveAttempt(
                        current.copy(
                            state = state,
                            error = outcome.failure,
                            updatedAtMs = System.currentTimeMillis(),
                        ),
                    )
                    if (outcome.failure == AttemptError.APK_INTEGRITY_FAILURE) {
                        current.downloadId?.let(downloads::cancel)
                        downloads.deleteAttemptFiles(current.attemptId)
                    }
                }
                diagnostics.record(
                    code = "install_failed",
                    policySequence = staging.policySequence,
                    versionCode = staging.versionCode,
                )
                if (outcome.failure == AttemptError.NEEDS_INSTALL_PERMISSION) {
                    InstallResult.NeedsPermission
                } else {
                    InstallResult.Failed(outcome.failure)
                }
            }
        }
    }

    suspend fun reconcileAfterReplace() = bus.withLock {
        val attempt = store.activeAttempt() ?: return@withLock
        val installed = installedVersionCode(appContext)
        store.ensureVersionFloor(installed)
        if (attempt.state.active && installed >= attempt.versionCode) {
            markInstalled(attempt)
            return@withLock
        }
        if (attempt.state == AttemptState.INSTALLING ||
            attempt.state == AttemptState.AWAITING_USER_CONFIRMATION
        ) {
            val session = attempt.sessionId
            if (session == null || !withContext(Dispatchers.IO) { installer.sessionExists(session) }) {
                store.saveAttempt(
                    attempt.copy(
                        state = AttemptState.UPDATE_STATUS_UNKNOWN,
                        error = AttemptError.UPDATE_STATUS_UNKNOWN,
                        updatedAtMs = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    fun cancelAttempt() {
        val attempt = store.activeAttempt() ?: return
        if (!attempt.state.active) return
        attempt.downloadId?.let(downloads::cancel)
        if (!attempt.committed) attempt.sessionId?.let(installer::abandonSession)
        downloads.deleteAttemptFiles(attempt.attemptId)
        store.saveAttempt(
            attempt.copy(
                state = AttemptState.CANCELLED,
                error = null,
                updatedAtMs = System.currentTimeMillis(),
            ),
        )
    }

    fun continueInstallIntent(): Intent? {
        val attempt = store.activeAttempt()
            ?.takeIf { it.state == AttemptState.AWAITING_USER_CONFIRMATION }
            ?: return null
        val uri = attempt.userActionIntentUri ?: return null
        return runCatching { Intent.parseUri(uri, Intent.URI_INTENT_SCHEME) }.getOrNull()
    }

    fun openPermissionSettings() {
        runCatching { installer.openPermissionSettings() }
    }

    fun diagnosticSummary(): String = diagnostics.summary()

    fun protectionResumeRequired(): Boolean {
        val required = store.protectionResumptionRequired() &&
            !protectionHealthy()
        if (store.protectionResumptionRequired() && !required) {
            store.setProtectionResumptionRequired(false)
        }
        return required
    }

    /** Marks a real post-update protection gap for the neutral in-app repair action. */
    fun syncProtectionMarker() {
        val impaired = !protectionHealthy()
        if (store.protectionResumptionRequired() != impaired) {
            store.setProtectionResumptionRequired(impaired)
        }
    }

    private fun protectionHealthy(): Boolean = runCatching {
        protectionMonitor.authority.value == ProtectionAuthority.FULL &&
            protectionStore.degradation == null &&
            !protectionStore.requiresExplicitReEnable
    }.getOrDefault(false)

    private fun reconcileOwnedAttempt() {
        val attempt = store.activeAttempt() ?: return
        val installed = installedVersionCode(appContext)
        val now = System.currentTimeMillis()
        if (attempt.state.active && installed >= attempt.versionCode) {
            markInstalled(attempt)
            return
        }
        if (attempt.state == AttemptState.DOWNLOAD_STARTING && attempt.downloadId == null) {
            store.saveAttempt(
                attempt.copy(
                    state = AttemptState.FAILED,
                    error = AttemptError.DOWNLOAD_FAILED,
                    updatedAtMs = now,
                ),
            )
            return
        }
        if (attempt.state == AttemptState.INSTALLING) {
            val session = attempt.sessionId
            if (session == null || !installer.sessionExists(session)) {
                store.saveAttempt(
                    attempt.copy(
                        state = AttemptState.UPDATE_STATUS_UNKNOWN,
                        error = AttemptError.UPDATE_STATUS_UNKNOWN,
                        updatedAtMs = now,
                    ),
                )
            }
        }
    }

    private fun markInstalled(attempt: UpdateAttempt) {
        store.saveAttempt(
            attempt.copy(
                state = AttemptState.SUCCEEDED,
                committed = true,
                error = null,
                updatedAtMs = System.currentTimeMillis(),
            ),
        )
        downloads.deleteAttemptFiles(attempt.attemptId)
    }

    private suspend fun verifyDownload(attempt: UpdateAttempt): UpdateAttempt? {
        val downloadId = attempt.downloadId ?: return failAttempt(attempt, AttemptError.APK_INTEGRITY_FAILURE)
        store.saveAttempt(
            attempt.copy(state = AttemptState.VERIFYING, updatedAtMs = System.currentTimeMillis()),
        )
        val bytesOk = withContext(Dispatchers.IO) {
            verifier.verifyBytes({ downloads.openStream(downloadId) }, attempt.asRelease())
        }
        val archiveFailure = if (!bytesOk) {
            AttemptError.APK_INTEGRITY_FAILURE
        } else {
            withContext(Dispatchers.IO) {
                verifier.verifyArchive(
                    path = attempt.destination ?: downloads.destinationPath(attempt.attemptId),
                    release = attempt.asRelease(),
                    installedVersionCode = installedVersionCode(appContext),
                    highestTrustedVersionCode = store.highestVersionCode(),
                )
            }
        }
        if (archiveFailure != null) return failAttempt(attempt, archiveFailure)
        val current = store.activeAttempt()
            ?.takeIf { it.attemptId == attempt.attemptId && it.state == AttemptState.VERIFYING }
            ?: return store.activeAttempt()
        val ready = current.copy(
            state = AttemptState.READY_TO_INSTALL,
            error = null,
            updatedAtMs = System.currentTimeMillis(),
        )
        store.saveAttempt(ready)
        diagnostics.record(
            code = "download_verified",
            policySequence = ready.policySequence,
            versionCode = ready.versionCode,
            bytes = ready.apkSizeBytes,
        )
        return ready
    }

    private fun failAttempt(attempt: UpdateAttempt, failure: AttemptError): UpdateAttempt? {
        val current = store.activeAttempt()
        if (current == null ||
            current.attemptId != attempt.attemptId ||
            !current.state.active
        ) {
            return current
        }
        current.downloadId?.let(downloads::cancel)
        downloads.deleteAttemptFiles(current.attemptId)
        val failed = current.copy(
            state = AttemptState.FAILED,
            error = failure,
            updatedAtMs = System.currentTimeMillis(),
        )
        store.saveAttempt(failed)
        diagnostics.record(
            code = "download_failed",
            policySequence = current.policySequence,
            versionCode = current.versionCode,
        )
        return failed
    }

    private fun deferCommit(attempt: UpdateAttempt): InstallResult {
        store.saveAttempt(
            attempt.copy(
                state = AttemptState.READY_TO_INSTALL,
                error = AttemptError.WAITING_FOR_POLICY_CONFIRMATION,
                updatedAtMs = System.currentTimeMillis(),
            ),
        )
        return InstallResult.Failed(AttemptError.WAITING_FOR_POLICY_CONFIRMATION)
    }

    private fun progress(): DownloadSnapshot? {
        val attempt = store.activeAttempt() ?: return null
        if (attempt.state != AttemptState.DOWNLOADING &&
            attempt.state != AttemptState.DOWNLOAD_STARTING
        ) {
            return null
        }
        return attempt.downloadId?.let(downloads::snapshot)
    }

    private fun effectiveDisplay(now: Long): DisplayStatus? {
        val stored = store.displayStatus() ?: return null
        if (stored == DisplayStatus.POLICY_ROLLBACK) return stored
        val lastSuccess = store.lastSuccessAtMs()
        if (lastSuccess > 0 && now - lastSuccess > UpdateConfig.CHECK_STALE_MS) {
            return DisplayStatus.CHECK_STALE
        }
        return stored
    }

    private fun project(policy: UpdatePolicy, installedVersionCode: Long): DisplayStatus {
        if (policy.mode == PolicyMode.HALT) return DisplayStatus.HALTED
        val release = policy.release ?: return DisplayStatus.HALTED
        return when {
            release.versionCode <= installedVersionCode -> DisplayStatus.UP_TO_DATE
            release.versionCode < store.highestVersionCode() -> DisplayStatus.NOT_IN_ROLLOUT
            !isInRollout(store.cohortId(), policy) -> DisplayStatus.NOT_IN_ROLLOUT
            else -> DisplayStatus.AVAILABLE
        }
    }

    private fun verifiedPolicy(): UpdatePolicy? {
        val envelope = store.latestEnvelope()
        if (envelope == null) {
            cache(null, null)
            return null
        }
        cachedEnvelope?.let { if (it == envelope) return cachedPolicy }
        val verified = verifyPolicyEnvelope(
            envelopeJson = envelope,
            publicKeyBase64 = UpdateConfig.MANIFEST_PUBLIC_KEY_BASE64,
            expectedPackageName = appContext.packageName,
            releaseRepository = UpdateConfig.releaseRepository,
        )
        val policy = (verified as? PolicyVerification.Valid)?.policy
        cache(envelope, policy)
        return policy
    }

    private fun cache(envelope: String?, policy: UpdatePolicy?) {
        cachedEnvelope = envelope
        cachedPolicy = policy
    }

    // getAllocatableBytes would count clearable caches as free; the plan's 2x + 64 MiB margin
    // is a conservative usable-space check, so keep usableSpace.
    @SuppressLint("UsableSpace")
    private fun storageAdequate(apkSizeBytes: Long): Boolean {
        val free = appContext.getExternalFilesDir(null)?.usableSpace ?: return false
        return free > apkSizeBytes * 2 + STORAGE_MARGIN_BYTES
    }

    private fun UpdateAttempt.asRelease(): UpdateRelease = UpdateRelease(
        versionCode = versionCode,
        versionName = versionName,
        minSdk = minSdk,
        targetSdk = targetSdk,
        apkUrl = apkUrl,
        apkSizeBytes = apkSizeBytes,
        apkSha256Hex = apkSha256Hex,
        releaseNotes = "",
    )

    private companion object {
        const val STORAGE_MARGIN_BYTES = 64L * 1024 * 1024
    }
}
